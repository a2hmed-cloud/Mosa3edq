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
    object Verifying : ModelDownloadState()
    data class Ready(val file: File, val sizeBytes: Long) : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

class ModelManager(
    private val context: Context,
    private val customClient: OkHttpClient? = null
) {
    private val tag = "ModelManager"

    private val httpClient = customClient ?: OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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
        // A valid model file must exist and have substantial size (minimum 10MB)
        return file.exists() && file.length() > 10_000_000L
    }

    fun refreshStatus(modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL) {
        val file = getModelFile(modelInfo)
        if (isModelInstalled(modelInfo)) {
            _downloadState.value = ModelDownloadState.Ready(file, file.length())
        } else {
            _downloadState.value = ModelDownloadState.Idle(false)
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
     * Downloads the specified model from its official source with full progress and speed metrics.
     */
    suspend fun downloadModel(
        modelInfo: LocalModelInfo = LocalModelInfo.DEFAULT_MODEL,
        customUrl: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(modelInfo)
        val tempFile = File(modelsDir, "${modelInfo.fileName}.downloading")

        // 1. Verify storage requirements before initiating download
        val availableStorage = getAvailableStorageBytes()
        val requiredSafetyMargin = modelInfo.sizeBytes + 200_000_000L // model size + 200MB safety
        if (availableStorage < requiredSafetyMargin) {
            val errorMsg = "مساحة التخزين المتاحة (${getFormattedAvailableStorage()}) غير كافية لتحميل النموذج (${modelInfo.formattedSize}). يتطلب توفير 2 جيجابايت على الأقل."
            _downloadState.value = ModelDownloadState.Error(errorMsg)
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        val url = customUrl ?: modelInfo.downloadUrl
        Log.i(tag, "Starting download for ${modelInfo.displayName} from $url")

        try {
            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = "فشل بدء التحميل من المصدر (كود الاستجابة: ${response.code}). يرجى التحقق من اتصال الإنترنت."
                _downloadState.value = ModelDownloadState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            val body = response.body ?: run {
                val err = "استجابة فارغة من خادم التحميل."
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

            _downloadState.value = ModelDownloadState.Verifying

            // 2. Checksum verification if available
            if (!modelInfo.sha256Checksum.isNullOrBlank()) {
                val calculatedHash = calculateSha256(tempFile)
                if (!calculatedHash.equals(modelInfo.sha256Checksum, ignoreCase = true)) {
                    tempFile.delete()
                    val err = "فشل التحقق من صحة الملف بعد التحميل (عدم تطابق البصمة SHA-256)."
                    _downloadState.value = ModelDownloadState.Error(err)
                    return@withContext Result.failure(SecurityException(err))
                }
            }

            // Move temp file to permanent destination
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(tag, "Model successfully downloaded and saved to ${targetFile.absolutePath}")
            _downloadState.value = ModelDownloadState.Ready(targetFile, targetFile.length())
            Result.success(targetFile)

        } catch (e: CancellationException) {
            Log.w(tag, "Model download was cancelled by user")
            tempFile.delete()
            refreshStatus(modelInfo)
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Model download failed", e)
            tempFile.delete()
            val msg = "تعذر إكمال تحميل النموذج: ${e.message}"
            _downloadState.value = ModelDownloadState.Error(msg)
            Result.failure(e)
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
