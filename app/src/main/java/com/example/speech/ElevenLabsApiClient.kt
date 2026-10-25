package com.example.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ElevenLabsApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    suspend fun synthesizeAndPlay(text: String, apiKeyOverride: String? = null, voiceIdOverride: String? = null): Boolean = withContext(Dispatchers.IO) {
        val apiKey = apiKeyOverride?.trim()?.ifEmpty { null } ?: run {
            try {
                val field = com.example.BuildConfig::class.java.getField("ELEVENLABS_API_KEY")
                field.get(null) as? String ?: ""
            } catch (e: Exception) {
                ""
            }
        }

        if (apiKey.isBlank() || apiKey == "MY_ELEVENLABS_API_KEY") {
            return@withContext false
        }

        val voiceId = voiceIdOverride?.trim()?.ifEmpty { null } ?: run {
            try {
                val field = com.example.BuildConfig::class.java.getField("ELEVENLABS_VOICE_ID")
                val v = field.get(null) as? String
                if (v.isNullOrBlank() || v == "MY_ELEVENLABS_VOICE_ID") "21m00Tcm4TlvDq8ikWAM" else v
            } catch (e: Exception) {
                "21m00Tcm4TlvDq8ikWAM"
            }
        }

        val modelId = "eleven_multilingual_v2"
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"

        try {
            val json = JSONObject().apply {
                put("text", text)
                put("model_id", modelId)
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                })
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("xi-api-key", apiKey)
                .header("Accept", "audio/mpeg")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("ElevenLabs", "API error: ${response.code}")
                return@withContext false
            }

            val audioBytes = response.body?.bytes() ?: return@withContext false
            if (audioBytes.isEmpty()) return@withContext false

            val tempFile = File.createTempFile("elevenlabs_", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            withContext(Dispatchers.Main) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        it.release()
                        tempFile.delete()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("ElevenLabs", "Synthesis failed", e)
            false
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
