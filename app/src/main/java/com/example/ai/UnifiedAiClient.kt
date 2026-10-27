package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.ai.local.LocalLlmClient
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class UnifiedAiClient(
    val localLlmClient: LocalLlmClient,
    val cloudApiClient: GeminiApiClient,
    private val settingsRepository: SettingsRepository? = null,
    private val modeSupplier: (() -> AiInferenceMode)? = null
) {
    private val tag = "UnifiedAiClient"

    suspend fun getActiveMode(): AiInferenceMode {
        if (modeSupplier != null) return modeSupplier.invoke()
        val savedId = try {
            settingsRepository?.aiInferenceModeFlow?.first() ?: "automatic"
        } catch (e: Exception) {
            "automatic"
        }
        return AiInferenceMode.fromId(savedId)
    }

    suspend fun generateResponse(
        prompt: String,
        systemInstruction: String? = null,
        screenshotBitmap: Bitmap? = null,
        jsonSchemaResponse: Boolean = false,
        modelName: String = "gemini-3.5-flash"
    ): Result<UnifiedAiResponse> = withContext(Dispatchers.IO) {
        val mode = getActiveMode()
        Log.i(tag, "Generating response with AI mode: $mode")

        when (mode) {
            AiInferenceMode.LOCAL -> {
                // LOCAL MODE: Strictly local on-device inference. Must NEVER silently fall back to cloud.
                if (screenshotBitmap != null) {
                    val err = "النموذج المحلي المدمج يعالج الأوامر والنصوص فقط ولا يدعم قراءة الصور ولقطات الشاشة بصرياً. لتشغيل تحليل الشاشة، يمكنك التبديل للوضع التلقائي أو السحابي."
                    return@withContext Result.failure(UnsupportedOperationException(err))
                }

                if (!localLlmClient.isModelAvailable()) {
                    val err = "النموذج المحلي غير مثبت على جهازك. بما أنك اخترت وضع (محلي فقط)، فلن يتم إرسال أي طلبات للسحابة احتراماً لخصوصيتك. يرجى تحميل النموذج من صفحة إعدادات الذكاء الاصطناعي."
                    return@withContext Result.failure(IllegalStateException(err))
                }

                val fullPrompt = buildLocalPrompt(prompt, systemInstruction, jsonSchemaResponse)
                var localResult: Result<String>
                val duration = measureTimeMillis {
                    localResult = localLlmClient.generateResponse(fullPrompt)
                }

                localResult.fold(
                    onSuccess = { generatedText ->
                        Result.success(
                            UnifiedAiResponse(
                                text = generatedText,
                                providerUsed = "Local (${localLlmClient.loadedModelName ?: "LiteRT-LM"})",
                                isLocal = true,
                                executionTimeMs = duration,
                                wasFallback = false
                            )
                        )
                    },
                    onFailure = { error ->
                        val localErrorMsg = "فشل تنفيذ الذكاء الاصطناعي المحلي: ${error.message}. (وضع محلي فقط مفعل - لم يتم الاتصال بالسحابة)."
                        Result.failure(Exception(localErrorMsg, error))
                    }
                )
            }

            AiInferenceMode.CLOUD -> {
                // CLOUD MODE: Preserves cloud provider (Gemini / OpenAI Compatible)
                var cloudResult: Result<String>
                val duration = measureTimeMillis {
                    cloudResult = cloudApiClient.generateResponse(
                        prompt = prompt,
                        systemInstruction = systemInstruction,
                        screenshotBitmap = screenshotBitmap,
                        jsonSchemaResponse = jsonSchemaResponse,
                        modelName = modelName
                    )
                }

                cloudResult.fold(
                    onSuccess = { text ->
                        Result.success(
                            UnifiedAiResponse(
                                text = text,
                                providerUsed = "Cloud Provider",
                                isLocal = false,
                                executionTimeMs = duration,
                                wasFallback = false
                            )
                        )
                    },
                    onFailure = { error ->
                        Result.failure(error)
                    }
                )
            }

            AiInferenceMode.AUTOMATIC -> {
                // AUTOMATIC / HYBRID MODE: Uses local model when ready & suitable, with explicit visible fallback policy
                if (screenshotBitmap != null) {
                    // Visual analysis requires cloud multimodal model
                    Log.i(tag, "Automatic mode: Multimodal input detected -> routing to cloud")
                    return@withContext executeCloudWithFallbackNotice(
                        prompt = prompt,
                        systemInstruction = systemInstruction,
                        screenshotBitmap = screenshotBitmap,
                        jsonSchemaResponse = jsonSchemaResponse,
                        modelName = modelName,
                        fallbackReason = "تحليل لقطة الشاشة يتطلب نموذج الرؤية السحابي"
                    )
                }

                if (localLlmClient.isModelAvailable()) {
                    val fullPrompt = buildLocalPrompt(prompt, systemInstruction, jsonSchemaResponse)
                    var localResult: Result<String>
                    val localDuration = measureTimeMillis {
                        localResult = localLlmClient.generateResponse(fullPrompt)
                    }

                    if (localResult.isSuccess) {
                        return@withContext Result.success(
                            UnifiedAiResponse(
                                text = localResult.getOrThrow(),
                                providerUsed = "Local (${localLlmClient.loadedModelName ?: "LiteRT-LM"})",
                                isLocal = true,
                                executionTimeMs = localDuration,
                                wasFallback = false
                            )
                        )
                    } else {
                        // Local execution failed, fallback to cloud with explicit notice
                        val localError = localResult.exceptionOrNull()?.message ?: "خطأ غير معروف"
                        Log.w(tag, "Local inference failed ($localError), falling back to cloud")
                        return@withContext executeCloudWithFallbackNotice(
                            prompt = prompt,
                            systemInstruction = systemInstruction,
                            screenshotBitmap = screenshotBitmap,
                            jsonSchemaResponse = jsonSchemaResponse,
                            modelName = modelName,
                            fallbackReason = "تعذر التوليد المحلي ($localError) - تم التبديل للسحابة تلقائياً"
                        )
                    }
                } else {
                    // Local model not yet installed, routing to cloud
                    Log.i(tag, "Automatic mode: Local model not downloaded -> routing to cloud")
                    return@withContext executeCloudWithFallbackNotice(
                        prompt = prompt,
                        systemInstruction = systemInstruction,
                        screenshotBitmap = screenshotBitmap,
                        jsonSchemaResponse = jsonSchemaResponse,
                        modelName = modelName,
                        fallbackReason = "النموذج المحلي غير مثبت - تم استخدام المزوّد السحابي"
                    )
                }
            }
        }
    }

    private suspend fun executeCloudWithFallbackNotice(
        prompt: String,
        systemInstruction: String?,
        screenshotBitmap: Bitmap?,
        jsonSchemaResponse: Boolean,
        modelName: String,
        fallbackReason: String
    ): Result<UnifiedAiResponse> {
        var cloudResult: Result<String>
        val duration = measureTimeMillis {
            cloudResult = cloudApiClient.generateResponse(
                prompt = prompt,
                systemInstruction = systemInstruction,
                screenshotBitmap = screenshotBitmap,
                jsonSchemaResponse = jsonSchemaResponse,
                modelName = modelName
            )
        }

        return cloudResult.fold(
            onSuccess = { text ->
                Result.success(
                    UnifiedAiResponse(
                        text = text,
                        providerUsed = "Cloud Provider",
                        isLocal = false,
                        executionTimeMs = duration,
                        wasFallback = true,
                        fallbackReason = fallbackReason
                    )
                )
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }

    companion object {
        /**
         * Formats prompt for Gemma / on-device LLMs adhering strictly to Gemma turn specifications:
         * <start_of_turn>user\n{instruction}\n\n{prompt}<end_of_turn>\n<start_of_turn>model\n
         */
        fun buildLocalPrompt(
            prompt: String,
            systemInstruction: String? = null,
            jsonSchemaResponse: Boolean = false
        ): String {
            val trimmed = prompt.trim()
            if (trimmed.startsWith("<start_of_turn>")) {
                return trimmed
            }
            val sb = StringBuilder()
            sb.append("<start_of_turn>user\n")
            if (!systemInstruction.isNullOrBlank()) {
                sb.append(systemInstruction.trim()).append("\n\n")
            }
            if (jsonSchemaResponse) {
                sb.append("تنبيه: أجب بتنسيق JSON حصراً بدون أي نصوص تمهيدية.\n\n")
            }
            sb.append(trimmed)
            sb.append("<end_of_turn>\n<start_of_turn>model\n")
            return sb.toString()
        }
    }
}
