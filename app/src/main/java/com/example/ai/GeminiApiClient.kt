package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiApiClient(
    private var customApiKeyProvider: (() -> String)? = null,
    private var customProviderSupplier: (() -> String)? = null,
    private var customBaseUrlSupplier: (() -> String)? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getEffectiveApiKey(): String {
        val userKey = customApiKeyProvider?.invoke()?.trim()
        if (!userKey.isNullOrEmpty()) {
            return userKey
        }
        return BuildConfig.GEMINI_API_KEY
    }

    private fun getEffectiveProvider(): String {
        return customProviderSupplier?.invoke()?.trim()?.ifEmpty { "gemini" } ?: "gemini"
    }

    private fun getEffectiveBaseUrl(): String {
        val url = customBaseUrlSupplier?.invoke()?.trim()
        if (!url.isNullOrEmpty()) {
            return url
        }
        return "https://generativelanguage.googleapis.com"
    }

    suspend fun generateResponse(
        prompt: String,
        systemInstruction: String? = null,
        screenshotBitmap: Bitmap? = null,
        jsonSchemaResponse: Boolean = false,
        modelName: String = "gemini-3.5-flash"
    ): Result<String> = withContext(Dispatchers.IO) {
        val provider = getEffectiveProvider()
        if (provider.equals("openai_compatible", ignoreCase = true)) {
            generateOpenAiResponse(prompt, systemInstruction, screenshotBitmap, jsonSchemaResponse, modelName)
        } else {
            generateGeminiResponse(prompt, systemInstruction, screenshotBitmap, jsonSchemaResponse, modelName)
        }
    }

    private suspend fun generateGeminiResponse(
        prompt: String,
        systemInstruction: String?,
        screenshotBitmap: Bitmap?,
        jsonSchemaResponse: Boolean,
        modelName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException("مفتاح Gemini API غير موجود! يرجى إدخال المفتاح في صفحة الإعدادات أو لوحة الأسرار.")
            )
        }

        try {
            val rootJson = JSONObject()

            // System Instruction
            if (!systemInstruction.isNullOrBlank()) {
                val sysContent = JSONObject()
                val sysParts = JSONArray()
                sysParts.put(JSONObject().put("text", systemInstruction))
                sysContent.put("parts", sysParts)
                rootJson.put("systemInstruction", sysContent)
            }

            // Contents
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()

            // Text part
            partsArray.put(JSONObject().put("text", prompt))

            // Multimodal image part
            screenshotBitmap?.let { bitmap ->
                val base64Image = bitmapToBase64(bitmap)
                val inlineDataObj = JSONObject()
                inlineDataObj.put("mimeType", "image/jpeg")
                inlineDataObj.put("data", base64Image)
                partsArray.put(JSONObject().put("inlineData", inlineDataObj))
            }

            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            rootJson.put("contents", contentsArray)

            // Generation config
            if (jsonSchemaResponse) {
                val genConfig = JSONObject()
                genConfig.put("responseMimeType", "application/json")
                rootJson.put("generationConfig", genConfig)
            }

            val cleanModel = if (modelName.startsWith("models/")) modelName.removePrefix("models/") else modelName
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
            val requestBody = rootJson.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("خطأ من Gemini API (${response.code}): $responseStr")
                )
            }

            val respJson = JSONObject(responseStr)
            val candidates = respJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(Exception("لم يعثر Gemini على رد مناسب."))
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            if (text.isNullOrBlank()) {
                return@withContext Result.failure(Exception("وصل رد فارغ من النموذج."))
            }

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun generateOpenAiResponse(
        prompt: String,
        systemInstruction: String?,
        screenshotBitmap: Bitmap?,
        jsonSchemaResponse: Boolean,
        modelName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        var baseUrl = getEffectiveBaseUrl().trimEnd('/')

        if (baseUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("يرجى إدخال عنوان API الأساسي (Base URL) للمزوّد."))
        }

        val endpointUrl = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            "$baseUrl/chat/completions"
        }

        try {
            val rootJson = JSONObject()
            rootJson.put("model", modelName)

            val messagesArray = JSONArray()

            // System Message
            if (!systemInstruction.isNullOrBlank()) {
                val sysMsg = JSONObject()
                sysMsg.put("role", "system")
                sysMsg.put("content", systemInstruction)
                messagesArray.put(sysMsg)
            }

            // User Message
            val userMsg = JSONObject()
            userMsg.put("role", "user")

            if (screenshotBitmap != null) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().put("type", "text").put("text", prompt))

                val imageObj = JSONObject()
                val base64Img = bitmapToBase64(screenshotBitmap)
                imageObj.put("url", "data:image/jpeg;base64,$base64Img")
                contentArray.put(JSONObject().put("type", "image_url").put("image_url", imageObj))

                userMsg.put("content", contentArray)
            } else {
                userMsg.put("content", prompt)
            }

            messagesArray.put(userMsg)
            rootJson.put("messages", messagesArray)

            if (jsonSchemaResponse) {
                rootJson.put("response_format", JSONObject().put("type", "json_object"))
            }

            val requestBuilder = Request.Builder()
                .url(endpointUrl)
                .post(rootJson.toString().toRequestBody("application/json".toMediaType()))

            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("خطأ من المزوّد (${response.code}): $responseStr")
                )
            }

            val respJson = JSONObject(responseStr)
            val choices = respJson.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.failure(Exception("لم يُرجع المزوّد اختيار إجابة صالحاً."))
            }

            val firstChoice = choices.getJSONObject(0)
            val messageObj = firstChoice.optJSONObject("message")
            val text = messageObj?.optString("content")

            if (text.isNullOrBlank()) {
                return@withContext Result.failure(Exception("وصل رد فارغ من المزوّد."))
            }

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAvailableModels(
        provider: String,
        baseUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val modelsList = mutableListOf<String>()

        try {
            if (provider.equals("openai_compatible", ignoreCase = true)) {
                var cleanBase = baseUrl.trim().trimEnd('/')
                if (cleanBase.endsWith("/chat/completions")) {
                    cleanBase = cleanBase.substringBeforeLast("/chat/completions")
                }
                val modelsUrl = "$cleanBase/models"

                val requestBuilder = Request.Builder().url(modelsUrl).get()
                if (apiKey.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val responseStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("فشل جلب النماذج ($modelsUrl - كود ${response.code}): $responseStr")
                    )
                }

                val json = JSONObject(responseStr)
                val dataArray = json.optJSONArray("data") ?: json.optJSONArray("models")

                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.opt(i)
                        if (item is JSONObject) {
                            val id = item.optString("id", item.optString("name", ""))
                            if (id.isNotBlank()) modelsList.add(id)
                        } else if (item is String) {
                            if (item.isNotBlank()) modelsList.add(item)
                        }
                    }
                }
            } else {
                // Gemini Provider Models Fetching
                val effectiveKey = apiKey.trim().ifEmpty { BuildConfig.GEMINI_API_KEY }
                if (effectiveKey.isEmpty() || effectiveKey == "MY_GEMINI_API_KEY") {
                    return@withContext Result.failure(
                        IllegalStateException("يرجى إدخال مفتاح Gemini API لجلب النماذج المتاحة.")
                    )
                }

                val modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$effectiveKey"
                val request = Request.Builder().url(modelsUrl).get().build()

                val response = client.newCall(request).execute()
                val responseStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("فشل جلب نماذج Gemini (كود ${response.code}): $responseStr")
                    )
                }

                val json = JSONObject(responseStr)
                val modelsArray = json.optJSONArray("models")
                if (modelsArray != null) {
                    for (i in 0 until modelsArray.length()) {
                        val mObj = modelsArray.getJSONObject(i)
                        val name = mObj.optString("name", "")
                        val cleanName = if (name.startsWith("models/")) name.removePrefix("models/") else name
                        if (cleanName.isNotBlank() && cleanName.contains("gemini")) {
                            modelsList.add(cleanName)
                        }
                    }
                }
            }

            if (modelsList.isEmpty()) {
                return@withContext Result.failure(Exception("لم تعثر نقطة النهاية على نماذج معلنة."))
            }

            Result.success(modelsList.distinct())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
