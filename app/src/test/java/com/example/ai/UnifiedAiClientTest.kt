package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.ai.local.LocalLlmClient
import com.example.ai.local.LocalModelInfo
import com.example.ai.local.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedAiClientTest {

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager
    private lateinit var localLlmClient: LocalLlmClient
    private lateinit var cloudApiClient: GeminiApiClient

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        modelManager = ModelManager(context)
        localLlmClient = LocalLlmClient(context, modelManager)
        cloudApiClient = GeminiApiClient(
            customApiKeyProvider = { "test-api-key" },
            customProviderSupplier = { "gemini" },
            customBaseUrlSupplier = { "https://generativelanguage.googleapis.com" }
        )
    }

    @Test
    fun `test LOCAL mode fails with explicit message when model is not installed`() = runBlocking {
        // Ensure model is deleted
        modelManager.deleteModel()
        assertFalse(localLlmClient.isModelAvailable())

        val unifiedClient = UnifiedAiClient(
            localLlmClient = localLlmClient,
            cloudApiClient = cloudApiClient,
            modeSupplier = { AiInferenceMode.LOCAL }
        )

        val result = unifiedClient.generateResponse("مرحباً يا حمادة")

        assertTrue(result.isFailure)
        val errorMessage = result.exceptionOrNull()?.message ?: ""
        assertTrue(errorMessage.contains("النموذج المحلي غير مثبت"))
        assertTrue(errorMessage.contains("لن يتم إرسال أي طلبات للسحابة"))
    }

    @Test
    fun `test LOCAL mode rejects screenshot with clear explanation`() = runBlocking {
        val unifiedClient = UnifiedAiClient(
            localLlmClient = localLlmClient,
            cloudApiClient = cloudApiClient,
            modeSupplier = { AiInferenceMode.LOCAL }
        )

        val fakeBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = unifiedClient.generateResponse(
            prompt = "اقرأ الشاشة",
            screenshotBitmap = fakeBitmap
        )

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(msg.contains("النموذج المحلي المدمج يعالج الأوامر والنصوص فقط"))
    }

    @Test
    fun `test AUTOMATIC mode fallback policy when local model is not downloaded`() = runBlocking {
        modelManager.deleteModel()
        assertFalse(localLlmClient.isModelAvailable())

        val unifiedClient = UnifiedAiClient(
            localLlmClient = localLlmClient,
            cloudApiClient = cloudApiClient,
            modeSupplier = { AiInferenceMode.AUTOMATIC }
        )

        assertEquals(AiInferenceMode.AUTOMATIC, unifiedClient.getActiveMode())
    }

    @Test
    fun `test AiInferenceMode fromId parsing`() {
        assertEquals(AiInferenceMode.LOCAL, AiInferenceMode.fromId("local"))
        assertEquals(AiInferenceMode.LOCAL, AiInferenceMode.fromId("LOCAL"))
        assertEquals(AiInferenceMode.CLOUD, AiInferenceMode.fromId("cloud"))
        assertEquals(AiInferenceMode.AUTOMATIC, AiInferenceMode.fromId("automatic"))
        assertEquals(AiInferenceMode.AUTOMATIC, AiInferenceMode.fromId("unknown"))
    }

    @Test
    fun `test cleanLocalOutput removes model tokens and extracts json`() {
        val rawGemmaOutput = """
            <start_of_turn>model
            <thought>
            The user wants to set an alarm.
            </thought>
            ```json
            {"action": "set_alarm_or_timer", "type": "alarm", "hour": 7, "minute": 0}
            ```
            <end_of_turn>
        """.trimIndent()

        val cleaned = LocalLlmClient.cleanLocalOutput(rawGemmaOutput)
        assertEquals("""{"action": "set_alarm_or_timer", "type": "alarm", "hour": 7, "minute": 0}""", cleaned)
    }

    @Test
    fun `test ModelManager available storage detection`() {
        val availableBytes = modelManager.getAvailableStorageBytes()
        assertTrue("Storage should be positive", availableBytes > 0L)
        val formatted = modelManager.getFormattedAvailableStorage()
        assertTrue(formatted.contains("جيجابايت"))
    }

    @Test
    fun `test LocalModelInfo default metadata`() {
        val defaultModel = LocalModelInfo.DEFAULT_MODEL
        assertEquals("gemma-2b-it-cpu-int4.bin", defaultModel.fileName)
        assertEquals("4-bit (INT4)", defaultModel.quantization)
        assertTrue(defaultModel.sizeBytes > 1_000_000_000L)
        assertTrue(defaultModel.downloadUrl.startsWith("https://huggingface.co/"))
    }
}
