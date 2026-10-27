package com.example.ai.local

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    data class Idle(val isInstalled: Boolean, val installedSizeBytes: Long = 0L) : ModelDownloadState()
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedMbPerSec: Float
    ) : ModelDownloadState()
    data class Verifying(val message: String = "جاري التحقق من سلامة النموذج وتهيئة محرك LiteRT...") : ModelDownloadState()
    data class Ready(val file: File, val sizeBytes: Long) : ModelDownloadState()
    data class Error(val message: String, val statusCode: Int? = null) : ModelDownloadState()
}

class ModelManager(
    private val context: Context,
    private val customClient: OkHttpClient? = null
) {
    private val tag = "ModelManager"

    private val httpClient = customClient ?: OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle(false))
    val downloadState: StateFlow<ModelDownloadState> = _downloadState

    val modelsDir: File by lazy {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    init {
        refreshStatus(LocalModelInfo.DEFAULT_MODEL)
    }

    fun getModelFile(modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL): File {
        return File(modelsDir, modelInfo.fileName)
    }

    fun isModelInstalled(modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL): Boolean {
        val file = getModelFile(modelInfo)
        // Must exist and be larger than 50MB (lite models are at least 150MB)
        return file.exists() && file.length() > 50_000_000L
    }

    fun refreshStatus(modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL) {
        val file = getModelFile(modelInfo)
        if (isModelInstalled(modelInfo)) {
            _downloadState.value = ModelDownloadState.Ready(file, file.length())
        } else {
            _downloadState.value = ModelDownloadState.Idle(false, 0L)
        }
    }

    /**
     * Checks available storage space in bytes where models are stored.
     */
    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(modelsDir.path)
            val bytes = stat.availableBlocksLong * stat.blockSizeLong
            if (bytes > 0L) bytes else modelsDir.usableSpace.coerceAtLeast(10_000_000_000L)
        } catch (e: Exception) {
            Log.e(tag, "Failed to read storage stats", e)
            modelsDir.usableSpace.coerceAtLeast(10_000_000_000L)
        }
    }

    fun getFormattedAvailableStorage(): String {
        val bytes = getAvailableStorageBytes()
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.2f جيجابايت", gb)
    }

    /**
     * Downloads the specified model from its official source with full progress, speed metrics,
     * checksum verification, and LiteRT runtime verification.
     */
    suspend fun downloadModel(
        modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL,
        customUrl: String? = null,
        authToken: String? = null,
        onVerifyCallback: (suspend (File) -> Result<Unit>)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(modelInfo)
        val tempFile = File(modelsDir, "${modelInfo.fileName}.downloading")

        // 1. Verify storage requirements before initiating download
        val availableStorage = getAvailableStorageBytes()
        val requiredSafetyMargin = modelInfo.sizeBytes + 150_000_000L // model size + 150MB safety
        if (availableStorage < requiredSafetyMargin) {
            val errorMsg = "مساحة التخزين غير كافية لتحميل النموذج (المتاح: ${getFormattedAvailableStorage()}، المطلوب: ${modelInfo.formattedSize}). يرجى تحرير مساحة على الهاتف والمحاولة مجدداً."
            _downloadState.value = ModelDownloadState.Error(errorMsg)
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        val url = customUrl ?: modelInfo.downloadUrl
        Log.i(tag, "Initiating download for ${modelInfo.displayName} [File: ${modelInfo.fileName}]")

        try {
            val requestBuilder = Request.Builder().url(url)
            val token = authToken?.trim()
            if (!token.isNullOrEmpty()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val request = requestBuilder.build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val statusCode = response.code
                val errorMsg = mapHttpError(statusCode, modelInfo)
                Log.e(tag, "Download request failed with HTTP $statusCode")
                _downloadState.value = ModelDownloadState.Error(errorMsg, statusCode)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val body = response.body ?: run {
                val err = "استجابة فارغة من خادم التنزيل."
                _downloadState.value = ModelDownloadState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            val totalBytes = if (body.contentLength() > 0) body.contentLength() else modelInfo.sizeBytes
            var downloadedBytes = 0L
            var lastUpdateTimestamp = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L

            _downloadState.value = ModelDownloadState.Downloading(
                progressPercent = 0,
                downloadedBytes = 0,
                totalBytes = totalBytes,
                speedMbPerSec = 0f
            )

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        bytesSinceLastUpdate += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdateTimestamp
                        if (elapsed >= 300) {
                            val speed = (bytesSinceLastUpdate / 1024f / 1024f) / (elapsed / 1000f)
                            val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                            _downloadState.value = ModelDownloadState.Downloading(
                                progressPercent = percent,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                speedMbPerSec = speed
                            )

                            lastUpdateTimestamp = now
                            bytesSinceLastUpdate = 0L
                        }
                    }
                    output.flush()
                }
            }

            // 2. Checksum verification against trusted publisher metadata
            if (!modelInfo.sha256Checksum.isNullOrBlank()) {
                _downloadState.value = ModelDownloadState.Verifying("جاري التحقق من البصمة الرقمية (SHA-256)...")
                val calculatedHash = calculateSha256(tempFile)
                if (!calculatedHash.equals(modelInfo.sha256Checksum, ignoreCase = true)) {
                    tempFile.delete()
                    val err = "فشل التحقق من سلامة الملف بعد اكتمال التنزيل (عدم تطابق البصمة الرقمية SHA-256). تم حذف الملف التالف للحفاظ على أمان الجهاز."
                    Log.e(tag, "Checksum mismatch for ${modelInfo.fileName}. Expected: ${modelInfo.sha256Checksum}, Calculated: $calculatedHash")
                    _downloadState.value = ModelDownloadState.Error(err)
                    return@withContext Result.failure(SecurityException(err))
                }
                Log.i(tag, "Checksum verified successfully for ${modelInfo.fileName}")
            }

            // Move temp file to permanent destination
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // 3. Post-download runtime verification: Initialize LiteRT and verify the model can load and run
            if (onVerifyCallback != null) {
                _downloadState.value = ModelDownloadState.Verifying("جاري تهيئة محرك LiteRT واختبار تشغيل النموذج محلياً...")
                val verificationResult = onVerifyCallback(targetFile)
                if (verificationResult.isFailure) {
                    val verifyErr = verificationResult.exceptionOrNull()?.message ?: "خطأ غير معروف في تهيئة المحرك"
                    val fullErr = "تم تنزيل الملف بنجاح، لكن تعذرت تهيئة محرك الذكاء الاصطناعي LiteRT: $verifyErr"
                    Log.e(tag, fullErr)
                    _downloadState.value = ModelDownloadState.Error(fullErr)
                    return@withContext Result.failure(Exception(fullErr))
                }
            }

            Log.i(tag, "Model successfully downloaded, verified, and ready at ${targetFile.absolutePath}")
            _downloadState.value = ModelDownloadState.Ready(targetFile, targetFile.length())
            Result.success(targetFile)

        } catch (e: CancellationException) {
            Log.w(tag, "Model download was cancelled by user")
            tempFile.delete()
            refreshStatus(modelInfo)
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            val msg = mapExceptionError(e, modelInfo)
            Log.e(tag, "Model download failed: $msg", e)
            _downloadState.value = ModelDownloadState.Error(msg)
            Result.failure(Exception(msg, e))
        }
    }

    /**
     * Maps HTTP status codes to clear, informative, actionable Arabic error messages.
     */
    fun mapHttpError(statusCode: Int, modelInfo: LocalModelInfo): String {
        return when (statusCode) {
            401 -> {
                if (modelInfo.requiresAuth) {
                    "فشل التحميل (خطأ 401 Unauthorized / غير مصرح - Gated Model): هذا المستودع مقيّد من الناشر ويتطلب الموافقة على شروط الاستخدام في Hugging Face وإدخال رمز الوصول (HF Token). يُنصح باختيار نموذج Qwen 2.5 المتاح مجاناً للعامة بدون تسجيل."
                } else {
                    "فشل التحميل (خطأ 401 Unauthorized / غير مصرح): الخادم يتطلب مصادقة للوصول. يرجى التحقق من صلاحيات الرابط أو استخدام أحد النماذج المفتوحة للعامة مثل Qwen 2.5."
                }
            }
            403 -> {
                if (modelInfo.requiresAuth) {
                    "فشل التحميل (خطأ 403 Forbidden / وصول محظور - Gated Model): ليس لديك إذن لتحميل هذا النموذج. تأكد من قبول ترخيص النموذج على Hugging Face وصلاحية الرمز (HF Token)."
                } else {
                    "فشل التحميل (خطأ 403 Forbidden / وصول محظور): الخادم رفض الوصول. يرجى التحقق من صلاحيات الرابط أو استخدام أحد النماذج المفتوحة للعامة مثل Qwen 2.5."
                }
            }
            404 -> "الملف غير موجود على خادم المصدر (خطأ 404 Not Found). قد يكون الرابط تغير أو تم نقله في المستودع."
            408, 504 -> "انتهت مهلة الاتصال واستجابة الخادم أثناء بدء التحميل (خطأ $statusCode Timeout). يرجى المحاولة بعد قليل."
            429 -> "تم تجاوز عدد الطلبات المسموح به لخوادم Hugging Face (خطأ 429 Too Many Requests). يرجى الانتظار بضع دقائق وإعادة المحاولة."
            in 500..599 -> "خوادم Hugging Face للتنزيل تواجه مشكلة داخلية مؤقتة (خطأ $statusCode Server Error). يرجى إعادة المحاولة لاحقاً."
            else -> "فشل بدء التنزيل من خادم المصدر (كود الاستجابة: $statusCode)."
        }
    }

    /**
     * Maps system and network exceptions to clear, accurate Arabic diagnostic messages.
     */
    fun mapExceptionError(e: Throwable, modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL): String {
        return when (e) {
            is SocketTimeoutException -> "انتهت مهلة قراءة البيانات من الخادم (Socket Timeout). انقطع الاتصال أو أن سرعة الإنترنت بطيئة جداً."
            is UnknownHostException -> "تعذر الوصول إلى خادم التنزيل (فشل في استعلام DNS). يرجى التحقق من اتصال الهاتف بشبكة الإنترنت."
            is ConnectException -> "تعذر الاتصال بخادم التنزيل (Connection Refused). يرجى التأكد من عدم حجب الاتصال."
            is InterruptedIOException -> "تمت مقاطعة عملية التنزيل أو إلغاؤها."
            is SecurityException -> e.message ?: "فشل التحقق من أمان وبصمة الملف."
            is IllegalStateException -> e.message ?: "حالة غير صالحة لبدء التنزيل."
            else -> "تعذر إكمال تنزيل النموذج: ${e.message ?: "خطأ غير متوقع"}"
        }
    }

    /**
     * Deletes the installed model file and releases storage space.
     */
    fun deleteModel(modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL): Boolean {
        val file = getModelFile(modelInfo)
        val temp = File(modelsDir, "${modelInfo.fileName}.downloading")
        if (temp.exists()) temp.delete()

        return if (file.exists()) {
            val deleted = file.delete()
            _downloadState.value = ModelDownloadState.Idle(false, 0L)
            deleted
        } else {
            _downloadState.value = ModelDownloadState.Idle(false, 0L)
            true
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
