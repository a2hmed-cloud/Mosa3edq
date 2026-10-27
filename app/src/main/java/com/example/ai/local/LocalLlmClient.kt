package com.example.ai.local

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

sealed class LocalLlmState {
    object Uninitialized : LocalLlmState()
    data class Loading(val message: String) : LocalLlmState()
    data class Ready(val modelName: String, val loadTimeMs: Long) : LocalLlmState()
    data class Generating(val currentPromptPreview: String) : LocalLlmState()
    data class Error(val message: String) : LocalLlmState()
}

data class InferencePerformanceMetrics(
    val lastLoadTimeMs: Long = 0L,
    val lastInferenceTimeMs: Long = 0L,
    val lastTokensGenerated: Int = 0,
    val tokensPerSecond: Float = 0f,
    val approximateMemoryUsageMb: Long = 0L
)

data class LocalDiagnosticResult(
    val isSuccess: Boolean,
    val stageFailed: String? = null,
    val modelFileName: String = "",
    val modelFileSizeBytes: Long = 0L,
    val initDurationMs: Long = 0L,
    val inferenceDurationMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val testPrompt: String = "",
    val rawOutput: String = "",
    val cleanedOutput: String = "",
    val errorMessage: String? = null
)

class LocalLlmClient(
    private val context: Context,
    private val modelManager: ModelManager
) {
    private val tag = "LocalLlmClient"

    private val _engineState = MutableStateFlow<LocalLlmState>(LocalLlmState.Uninitialized)
    val engineState: StateFlow<LocalLlmState> = _engineState

    private val _metrics = MutableStateFlow(InferencePerformanceMetrics())
    val metrics: StateFlow<InferencePerformanceMetrics> = _metrics

    private var llmInference: LlmInference? = null
    private var loadedModelFile: File? = null
    val loadedModelName: String? get() = loadedModelFile?.name
    private val inferenceMutex = Mutex()

    /**
     * Checks if a local model is installed and ready to be initialized or used.
     */
    fun isModelAvailable(modelInfo: LocalModelInfo? = null): Boolean {
        return modelManager.isModelInstalled(modelInfo)
    }

    /**
     * Resolves the active installed model file on the device.
     */
    fun getInstalledModelFile(modelInfo: LocalModelInfo? = null): File? {
        return modelManager.findInstalledModelFile(modelInfo)
    }

    /**
     * Initializes the LiteRT / MediaPipe GenAI runtime with the installed model.
     * This loads model weights into memory off the main thread.
     */
    suspend fun initialize(
        modelFileOverride: File? = null,
        modelInfo: LocalModelInfo? = null,
        maxTokens: Int = 2048,
        topK: Int = 40,
        temperature: Float = 0.7f
    ): Result<Unit> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            val targetFile = modelFileOverride ?: modelManager.findInstalledModelFile(modelInfo) ?: modelManager.getModelFile(modelInfo)

            if (!targetFile.exists() || targetFile.length() < 10_000_000L) {
                val err = "ملف النموذج المحلي غير متوفر (${targetFile.name}). يرجى تحميله أولاً من شاشة إعدادات الذكاء الاصطناعي."
                Log.w(tag, "[Stage 3] Model file not found or invalid: ${targetFile.absolutePath}")
                _engineState.value = LocalLlmState.Error(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            // If already loaded the same file, reuse it
            if (llmInference != null && loadedModelFile?.absolutePath == targetFile.absolutePath) {
                Log.d(tag, "[Stage 4] LlmInference already loaded: ${targetFile.name}")
                return@withContext Result.success(Unit)
            }

            // Release any previously loaded instance first to prevent OOM
            closeInternal()

            _engineState.value = LocalLlmState.Loading("جاري فحص الذاكرة وتهيئة معالج LiteRT...")
            Log.i(tag, "[Stage 4] Initializing LiteRT-LM LlmInference from ${targetFile.name} (${targetFile.length() / (1024 * 1024)}MB)")

            try {
                val loadDuration = measureTimeMillis {
                    val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(targetFile.absolutePath)
                        .setMaxTokens(maxTokens)
                        .setTopK(topK)
                        .setTemperature(temperature)

                    llmInference = LlmInference.createFromOptions(context, optionsBuilder.build())
                }

                loadedModelFile = targetFile
                val usedMemoryMb = getApproximateMemoryUsageMb()

                _metrics.value = _metrics.value.copy(
                    lastLoadTimeMs = loadDuration,
                    approximateMemoryUsageMb = usedMemoryMb
                )

                _engineState.value = LocalLlmState.Ready(targetFile.name, loadDuration)
                Log.i(tag, "[Stage 4] LiteRT-LM initialized successfully in ${loadDuration}ms (~${usedMemoryMb}MB RAM)")
                Result.success(Unit)

            } catch (oom: OutOfMemoryError) {
                Log.e(tag, "[Stage 4] Out of memory initializing local model", oom)
                closeInternal()
                val err = "نفدت ذاكرة الهاتف (RAM) أثناء محاولة تحميل النموذج (${targetFile.name}). يرجى إغلاق التطبيقات الأخرى في الخلفية والمحاولة مرة أخرى."
                _engineState.value = LocalLlmState.Error(err)
                Result.failure(oom)
            } catch (linkErr: LinkageError) {
                Log.e(tag, "[Stage 4] Native linkage/JNI error loading LiteRT runtime", linkErr)
                closeInternal()
                val err = "تعذر تحميل مكتبة LiteRT الأصلية على المعالج: ${linkErr.message}"
                _engineState.value = LocalLlmState.Error(err)
                Result.failure(linkErr)
            } catch (t: Throwable) {
                Log.e(tag, "[Stage 4] Failed to initialize LlmInference: ${t.message}", t)
                closeInternal()
                val err = "فشل تهيئة محرك الذكاء الاصطناعي المحلي: ${t.message ?: "خطأ غير متوقع"}"
                _engineState.value = LocalLlmState.Error(err)
                Result.failure(t)
            }
        }
    }

    /**
     * Verifies that the model file can be loaded by LiteRT and can generate text successfully.
     */
    suspend fun verifyAndWarmup(modelFileOverride: File? = null): Result<String> = withContext(Dispatchers.Default) {
        val targetFile = modelFileOverride ?: modelManager.findInstalledModelFile() ?: modelManager.getModelFile()
        Log.d(tag, "[Warmup] Verifying model warmup for ${targetFile.name}")
        val initResult = initialize(modelFileOverride = targetFile)
        if (initResult.isFailure) {
            return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("فشل تهيئة المحرك"))
        }
        val warmupPrompt = "<start_of_turn>user\nمرحبا<end_of_turn>\n<start_of_turn>model\n"
        generateResponse(warmupPrompt, maxTokens = 32)
    }

    /**
     * Runs a real, standalone inference diagnostic on device with no cloud fallbacks or mocks.
     */
    suspend fun runStandaloneDiagnostic(
        testPrompt: String = "Reply with exactly: LOCAL_OK"
    ): LocalDiagnosticResult = withContext(Dispatchers.Default) {
        val totalStart = System.currentTimeMillis()
        Log.d(tag, "[Diagnostic] Starting standalone local LLM diagnostic: '$testPrompt'")

        // Stage 1: Check model file existence and size
        val modelFile = getInstalledModelFile() ?: modelManager.getModelFile()
        if (!modelFile.exists() || modelFile.length() < 1024L) {
            val err = "ملف النموذج غير موجود على الجهاز أو حجمه غير صالح (${modelFile.name}). يرجى تنزيل النموذج أولاً."
            Log.e(tag, "[Diagnostic] Stage 1 failed: $err")
            return@withContext LocalDiagnosticResult(
                isSuccess = false,
                stageFailed = "Stage 1: فحص ملف النموذج على القرص (Model File Check)",
                modelFileName = modelFile.name,
                modelFileSizeBytes = if (modelFile.exists()) modelFile.length() else 0L,
                errorMessage = err,
                totalDurationMs = System.currentTimeMillis() - totalStart
            )
        }

        // Stage 2: Check RAM Headroom
        val freeMemMb = Runtime.getRuntime().freeMemory() / (1024 * 1024)
        val maxMemMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        Log.d(tag, "[Diagnostic] Memory check: free=${freeMemMb}MB, max=${maxMemMb}MB")

        // Stage 3: Initialize Runtime
        val initResult = initialize(modelFileOverride = modelFile, maxTokens = 2048)
        if (initResult.isFailure) {
            val ex = initResult.exceptionOrNull()
            val err = "فشل تهيئة محرك LiteRT: ${ex?.message ?: "خطأ في تحميل المكتبة الأصلية أو الأوزان"}"
            Log.e(tag, "[Diagnostic] Stage 3 failed: $err", ex)
            return@withContext LocalDiagnosticResult(
                isSuccess = false,
                stageFailed = "Stage 3: تهيئة محرك LiteRT في الذاكرة (Runtime Initialization)",
                modelFileName = modelFile.name,
                modelFileSizeBytes = modelFile.length(),
                errorMessage = err,
                totalDurationMs = System.currentTimeMillis() - totalStart
            )
        }
        val initDuration = _metrics.value.lastLoadTimeMs

        // Stage 4: Run Standalone Test Prompt
        val formattedPrompt = if (!testPrompt.contains("<start_of_turn>")) {
            "<start_of_turn>user\n$testPrompt<end_of_turn>\n<start_of_turn>model\n"
        } else testPrompt

        val inferenceStart = System.currentTimeMillis()
        val inferenceResult = generateResponse(formattedPrompt, maxTokens = 64)
        val inferenceDuration = System.currentTimeMillis() - inferenceStart

        inferenceResult.fold(
            onSuccess = { rawText ->
                val cleaned = cleanLocalOutput(rawText)
                Log.d(tag, "[Diagnostic] Standalone test SUCCESS: output='$cleaned' in ${inferenceDuration}ms")
                LocalDiagnosticResult(
                    isSuccess = true,
                    modelFileName = modelFile.name,
                    modelFileSizeBytes = modelFile.length(),
                    initDurationMs = initDuration,
                    inferenceDurationMs = inferenceDuration,
                    totalDurationMs = System.currentTimeMillis() - totalStart,
                    testPrompt = testPrompt,
                    rawOutput = rawText,
                    cleanedOutput = cleaned
                )
            },
            onFailure = { error ->
                val err = "فشل توليد الرد من المعالج المحلي: ${error.message}"
                Log.e(tag, "[Diagnostic] Stage 4 failed: $err", error)
                LocalDiagnosticResult(
                    isSuccess = false,
                    stageFailed = "Stage 4: توليد الرد المحلي (Inference Execution)",
                    modelFileName = modelFile.name,
                    modelFileSizeBytes = modelFile.length(),
                    initDurationMs = initDuration,
                    inferenceDurationMs = inferenceDuration,
                    totalDurationMs = System.currentTimeMillis() - totalStart,
                    testPrompt = testPrompt,
                    errorMessage = err
                )
            }
        )
    }

    /**
     * Generates a complete text response locally on device without any network requests.
     */
    suspend fun generateResponse(
        prompt: String,
        maxTokens: Int = 512
    ): Result<String> = withContext(Dispatchers.Default) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("نص الطلب فارغ."))
        }

        inferenceMutex.withLock {
            // Auto-initialize with standard 2048 context window if not already loaded
            if (llmInference == null) {
                Log.d(tag, "[Stage 4] Engine not initialized. Auto-initializing with 2048 context window...")
                val initResult = initialize(maxTokens = 2048)
                if (initResult.isFailure) {
                    val ex = initResult.exceptionOrNull() ?: Exception("فشل تهيئة المحرك المحلي")
                    Log.e(tag, "[Stage 4] Auto-initialization failed: ${ex.message}")
                    return@withContext Result.failure(ex)
                }
            }

            val inference = llmInference ?: run {
                return@withContext Result.failure(IllegalStateException("محرك الذكاء الاصطناعي المحلي غير متوفر."))
            }

            _engineState.value = LocalLlmState.Generating(trimmedPrompt.take(40))

            try {
                // Ensure turn tags are preserved while staying within model context bounds
                val safePrompt = fitPromptToContext(trimmedPrompt, maxChars = 3500)
                Log.d(tag, "[Stage 6] Invoking native inference.generateResponse (prompt length=${safePrompt.length})...")

                var rawResponse = ""
                val generationDuration = measureTimeMillis {
                    rawResponse = inference.generateResponse(safePrompt)
                }
                Log.d(tag, "[Stage 7] Native generation finished in ${generationDuration}ms")

                val cleaned = cleanLocalOutput(rawResponse)
                val estimatedTokens = (cleaned.length / 4).coerceAtLeast(1)
                val tokensPerSec = if (generationDuration > 0) (estimatedTokens.toFloat() / (generationDuration / 1000f)) else 0f

                _metrics.value = _metrics.value.copy(
                    lastInferenceTimeMs = generationDuration,
                    lastTokensGenerated = estimatedTokens,
                    tokensPerSecond = tokensPerSec,
                    approximateMemoryUsageMb = getApproximateMemoryUsageMb()
                )

                _engineState.value = LocalLlmState.Ready(loadedModelFile?.name ?: "Local Model", _metrics.value.lastLoadTimeMs)
                Log.i(tag, "[Stage 8] Local inference successful: generated ${cleaned.length} chars in ${generationDuration}ms (~$estimatedTokens tokens)")

                Result.success(cleaned)

            } catch (oom: OutOfMemoryError) {
                Log.e(tag, "[Stage 6] OOM during local generation", oom)
                _engineState.value = LocalLlmState.Error("نفدت الذاكرة أثناء توليد الإجابة.")
                Result.failure(oom)
            } catch (t: Throwable) {
                Log.e(tag, "[Stage 6] Local inference error: ${t.message}", t)
                _engineState.value = LocalLlmState.Error("خطأ أثناء المعالجة المحلية: ${t.message}")
                Result.failure(t)
            }
        }
    }

    /**
     * Streams generated tokens asynchronously into the UI callback.
     */
    suspend fun generateResponseStream(
        prompt: String,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.Default) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("نص الطلب فارغ."))
        }

        inferenceMutex.withLock {
            if (llmInference == null) {
                val initResult = initialize(maxTokens = 2048)
                if (initResult.isFailure) {
                    return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("فشل تهيئة المحرك المحلي"))
                }
            }

            val inference = llmInference ?: run {
                return@withContext Result.failure(IllegalStateException("محرك الذكاء الاصطناعي المحلي غير متوفر."))
            }

            _engineState.value = LocalLlmState.Generating(trimmedPrompt.take(40))

            try {
                val safePrompt = fitPromptToContext(trimmedPrompt, maxChars = 3500)
                val accumulated = StringBuilder()
                val generationDuration = measureTimeMillis {
                    val fullOutput = inference.generateResponse(safePrompt)
                    accumulated.append(fullOutput)
                    onToken(fullOutput)
                }

                val cleaned = cleanLocalOutput(accumulated.toString())
                val estimatedTokens = (cleaned.length / 4).coerceAtLeast(1)
                val tokensPerSec = if (generationDuration > 0) (estimatedTokens.toFloat() / (generationDuration / 1000f)) else 0f

                _metrics.value = _metrics.value.copy(
                    lastInferenceTimeMs = generationDuration,
                    lastTokensGenerated = estimatedTokens,
                    tokensPerSecond = tokensPerSec
                )

                _engineState.value = LocalLlmState.Ready(loadedModelFile?.name ?: "Local Model", _metrics.value.lastLoadTimeMs)
                Result.success(cleaned)

            } catch (t: Throwable) {
                _engineState.value = LocalLlmState.Error("خطأ في توليد الرد المحلي: ${t.message}")
                Result.failure(t)
            }
        }
    }

    /**
     * Closes the native runtime and frees memory.
     */
    fun close() {
        closeInternal()
        _engineState.value = LocalLlmState.Uninitialized
    }

    private fun closeInternal() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing LlmInference", e)
        } finally {
            llmInference = null
            loadedModelFile = null
        }
    }

    fun clearMemory() {
        close()
        System.gc()
    }

    private fun getApproximateMemoryUsageMb(): Long {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedBytes / (1024 * 1024)
    }

    companion object {
        /**
         * Safely truncates long prompts to fit within mobile context while keeping
         * outer turn tags (<start_of_turn>user ... <end_of_turn>\n<start_of_turn>model\n) strictly intact.
         */
        fun fitPromptToContext(prompt: String, maxChars: Int = 3500): String {
            if (prompt.length <= maxChars) return prompt

            val userTag = "<start_of_turn>user\n"
            val modelTag = "<end_of_turn>\n<start_of_turn>model\n"
            val userIdx = prompt.indexOf(userTag)
            val modelIdx = prompt.indexOf(modelTag)

            if (userIdx != -1 && modelIdx > userIdx) {
                val innerContent = prompt.substring(userIdx + userTag.length, modelIdx)
                val budget = maxChars - userTag.length - modelTag.length
                val truncatedInner = if (innerContent.length > budget) innerContent.takeLast(budget) else innerContent
                return userTag + truncatedInner + modelTag
            }

            return prompt.takeLast(maxChars)
        }

        fun cleanLocalOutput(raw: String): String {
            if (raw.isBlank()) return raw
            var text = raw.replace(Regex("(?s)<start_of_turn>.*?</start_of_turn>"), "")
                .replace("<start_of_turn>model", "")
                .replace("<start_of_turn>user", "")
                .replace("<start_of_turn>", "")
                .replace("<end_of_turn>", "")
                .replace("<eos>", "")
                .replace("<bos>", "")
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .replace(Regex("(?s)<thought>.*?</thought>"), "")
                .trim()

            val jsonMatch = Regex("(?s)```(?:json)?\\s*(\\{.*?\\}|\\[.*?\\])\\s*```").find(text)?.groupValues?.get(1)
            if (jsonMatch != null) {
                return jsonMatch.trim()
            }

            return text
        }
    }
}
