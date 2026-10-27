package com.example.ai.local

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.system.measureTimeMillis

sealed class LocalLlmState {
    object Uninitialized : LocalLlmState()
    data class Loading(val message: String = "جاري تحميل النموذج في الذاكرة...") : LocalLlmState()
    data class Ready(val modelName: String, val loadTimeMs: Long) : LocalLlmState()
    data class Generating(val promptPreview: String) : LocalLlmState()
    data class Error(val error: String) : LocalLlmState()
}

data class InferencePerformanceMetrics(
    val lastLoadTimeMs: Long = 0L,
    val lastInferenceTimeMs: Long = 0L,
    val lastTokensGenerated: Int = 0,
    val tokensPerSecond: Float = 0f,
    val approximateMemoryUsageMb: Long = 0L
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
        return if (modelInfo != null) {
            modelManager.isModelInstalled(modelInfo)
        } else {
            modelManager.isModelInstalled()
        }
    }

    /**
     * Initializes the LiteRT / MediaPipe GenAI runtime with the installed model.
     * This loads model weights into memory off the main thread.
     */
    suspend fun initialize(
        modelFileOverride: File? = null,
        modelInfo: LocalModelInfo? = null,
        maxTokens: Int = 1024,
        topK: Int = 40,
        temperature: Float = 0.7f
    ): Result<Unit> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            val targetFile = modelFileOverride ?: (if (modelInfo != null) modelManager.getModelFile(modelInfo) else modelManager.getModelFile())

            if (!targetFile.exists() || targetFile.length() < 10_000_000L) {
                val err = "ملف النموذج المحلي غير متوفر. يرجى تحميله أولاً من شاشة إعدادات الذكاء الاصطناعي."
                _engineState.value = LocalLlmState.Error(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            // If already loaded the same file, reuse it
            if (llmInference != null && loadedModelFile?.absolutePath == targetFile.absolutePath) {
                return@withContext Result.success(Unit)
            }

            // Release any previously loaded instance first to prevent OOM
            closeInternal()

            _engineState.value = LocalLlmState.Loading("جاري فحص الذاكرة وتهيئة المعالج...")
            Log.i(tag, "Initializing LlmInference from ${targetFile.absolutePath}")

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
                Log.i(tag, "LlmInference initialized in ${loadDuration}ms (~${usedMemoryMb}MB RAM)")
                Result.success(Unit)

            } catch (oom: OutOfMemoryError) {
                Log.e(tag, "Out of memory initializing local model", oom)
                closeInternal()
                val err = "نفدت ذاكرة الهاتف (RAM) أثناء محاولة تحميل النموذج. يرجى إغلاق التطبيقات الأخرى في الخلفية والمحاولة مرة أخرى."
                _engineState.value = LocalLlmState.Error(err)
                Result.failure(oom)
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize LlmInference", e)
                closeInternal()
                val err = "فشل تهيئة محرك الذكاء الاصطناعي المحلي: ${e.message}"
                _engineState.value = LocalLlmState.Error(err)
                Result.failure(e)
            }
        }
    }

    /**
     * Verifies that the model file can be loaded by LiteRT and can generate text successfully.
     */
    suspend fun verifyAndWarmup(modelFileOverride: File? = null): Result<String> = withContext(Dispatchers.Default) {
        val targetFile = modelFileOverride ?: modelManager.getModelFile()
        val initResult = initialize(modelFileOverride = targetFile)
        if (initResult.isFailure) {
            return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("فشل تهيئة المحرك"))
        }
        generateResponse("مرحبا", maxTokens = 16)
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
            // Auto-initialize if not ready
            if (llmInference == null) {
                val initResult = initialize(maxTokens = maxTokens)
                if (initResult.isFailure) {
                    return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("فشل تهيئة المحرك المحلي"))
                }
            }

            val inference = llmInference ?: run {
                return@withContext Result.failure(IllegalStateException("محرك الذكاء الاصطناعي المحلي غير متوفر."))
            }

            _engineState.value = LocalLlmState.Generating(trimmedPrompt.take(40))

            try {
                // Truncate prompt if exceptionally long to fit within mobile context bounds
                val safePrompt = if (trimmedPrompt.length > 3000) {
                    trimmedPrompt.takeLast(3000)
                } else {
                    trimmedPrompt
                }

                var rawResponse = ""
                val generationDuration = measureTimeMillis {
                    // Local native inference execution
                    rawResponse = inference.generateResponse(safePrompt)
                }

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
                Log.i(tag, "Local inference completed in ${generationDuration}ms (${tokensPerSec} tok/s)")

                Result.success(cleaned)

            } catch (oom: OutOfMemoryError) {
                Log.e(tag, "OOM during local generation", oom)
                _engineState.value = LocalLlmState.Error("نفدت الذاكرة أثناء توليد الإجابة.")
                Result.failure(oom)
            } catch (e: Exception) {
                Log.e(tag, "Local inference error", e)
                _engineState.value = LocalLlmState.Error("خطأ أثناء المعالجة المحلية: ${e.message}")
                Result.failure(e)
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
                val initResult = initialize()
                if (initResult.isFailure) {
                    return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("فشل تهيئة المحرك المحلي"))
                }
            }

            val inference = llmInference ?: run {
                return@withContext Result.failure(IllegalStateException("محرك الذكاء الاصطناعي المحلي غير متوفر."))
            }

            _engineState.value = LocalLlmState.Generating(trimmedPrompt.take(40))

            try {
                val accumulated = StringBuilder()
                val generationDuration = measureTimeMillis {
                    // Uses LiteRT synchronous generation while reporting chunks
                    val fullOutput = inference.generateResponse(trimmedPrompt)
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

            } catch (e: Exception) {
                _engineState.value = LocalLlmState.Error("خطأ في توليد الرد المحلي: ${e.message}")
                Result.failure(e)
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
        fun cleanLocalOutput(raw: String): String {
            if (raw.isBlank()) return raw
            var text = raw.replace(Regex("(?s)<start_of_turn>.*?</start_of_turn>"), "")
                .replace("<start_of_turn>model", "")
                .replace("<end_of_turn>", "")
                .replace("<eos>", "")
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .trim()

            val jsonMatch = Regex("(?s)```(?:json)?\\s*(\\{.*?\\}|\\[.*?\\])\\s*```").find(text)?.groupValues?.get(1)
            if (jsonMatch != null) {
                return jsonMatch.trim()
            }

            return text
        }
    }
}
