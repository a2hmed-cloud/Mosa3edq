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
    private var customApiKeyProvider: (() -> String)? = null
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

    suspend fun generateResponse(
        prompt: String,
        systemInstruction: String? = null,
        screenshotBitmap: Bitmap? = null,
        jsonSchemaResponse: Boolean = false,
        modelName: String = "gemini-3.5-flash"
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

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
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

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
