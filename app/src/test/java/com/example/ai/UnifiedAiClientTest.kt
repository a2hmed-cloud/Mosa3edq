package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.ai.local.LocalLlmClient
import com.example.ai.local.LocalModelInfo
import com.example.ai.local.ModelDownloadState
import com.example.ai.local.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    fun `test LocalModelInfo default metadata is un-gated public Qwen model`() {
        val defaultModel = LocalModelInfo.DEFAULT_MODEL
        assertEquals("Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", defaultModel.fileName)
        assertEquals("INT8 LiteRT Task", defaultModel.quantization)
        assertEquals(546_660_344L, defaultModel.sizeBytes)
        assertFalse("Default model must not require auth", defaultModel.requiresAuth)
        assertEquals("e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2", defaultModel.sha256Checksum)
        assertTrue(defaultModel.downloadUrl.contains("litert-community/Qwen2.5-0.5B-Instruct"))
    }

    @Test
    fun `test LocalModelInfo findById and available models`() {
        assertEquals(4, LocalModelInfo.AVAILABLE_MODELS.size)
        val qwen05 = LocalModelInfo.findById("qwen2.5-0.5b-instruct-litert")
        assertEquals("Qwen 2.5 0.5B Instruct (موصى به)", qwen05.displayName)
        assertFalse(qwen05.requiresAuth)

        val qwen15 = LocalModelInfo.findById("qwen2.5-1.5b-instruct-litert")
        assertEquals("Qwen 2.5 1.5B Instruct (عالي الدقة)", qwen15.displayName)
        assertFalse(qwen15.requiresAuth)

        val smol = LocalModelInfo.findById("smollm-135m-instruct-litert")
        assertEquals(166_754_726L, smol.sizeBytes)
        assertFalse(smol.requiresAuth)

        val gemma = LocalModelInfo.findById("gemma3-1b-it-litert")
        assertTrue("Gemma model must require auth", gemma.requiresAuth)

        // Fallback for unknown ID
        val fallback = LocalModelInfo.findById("non-existent-id")
        assertEquals(LocalModelInfo.DEFAULT_MODEL.id, fallback.id)
    }

    @Test
    fun `test mapHttpError 401 diagnosing gated model requirement`() {
        val gemmaModel = LocalModelInfo.GEMMA_3_1B
        val errorMsg = modelManager.mapHttpError(401, gemmaModel)

        assertTrue(errorMsg.contains("401 Unauthorized"))
        assertTrue(errorMsg.contains("Gated Model"))
        assertTrue(errorMsg.contains("Hugging Face"))
        assertTrue(errorMsg.contains("HF Token"))
    }

    @Test
    fun `test mapHttpError other status codes`() {
        val model = LocalModelInfo.DEFAULT_MODEL

        val err403 = modelManager.mapHttpError(403, model)
        assertTrue(err403.contains("403 Forbidden"))

        val err404 = modelManager.mapHttpError(404, model)
        assertTrue(err404.contains("404 Not Found"))

        val err408 = modelManager.mapHttpError(408, model)
        assertTrue(err408.contains("مهلة الاتصال"))

        val err429 = modelManager.mapHttpError(429, model)
        assertTrue(err429.contains("تجاوز عدد الطلبات المسموح به"))

        val err500 = modelManager.mapHttpError(500, model)
        assertTrue(err500.contains("500"))
        assertTrue(err500.contains("خوادم Hugging Face"))
    }

    @Test
    fun `test mapExceptionError provides distinct diagnostic messages`() {
        val timeoutErr = modelManager.mapExceptionError(SocketTimeoutException("Read timed out"))
        assertTrue(timeoutErr.contains("مهلة قراءة البيانات"))

        val dnsErr = modelManager.mapExceptionError(UnknownHostException("huggingface.co"))
        assertTrue(dnsErr.contains("DNS"))

        val connErr = modelManager.mapExceptionError(ConnectException("Connection refused"))
        assertTrue(connErr.contains("الاتصال بخادم التنزيل"))

        val secErr = modelManager.mapExceptionError(SecurityException("Checksum mismatch"))
        assertTrue(secErr.contains("Checksum mismatch"))

        val cancelErr = modelManager.mapExceptionError(InterruptedIOException("Cancelled"))
        assertTrue(cancelErr.contains("مقاطعة عملية التنزيل"))
    }

    @Test
    fun `test ModelDownloadState Error contains optional status code`() {
        val errorWithCode = ModelDownloadState.Error("HTTP 401 error", 401)
        assertEquals(401, errorWithCode.statusCode)
        assertEquals("HTTP 401 error", errorWithCode.message)

        val errorWithoutCode = ModelDownloadState.Error("Storage error")
        assertNull(errorWithoutCode.statusCode)
    }
}
