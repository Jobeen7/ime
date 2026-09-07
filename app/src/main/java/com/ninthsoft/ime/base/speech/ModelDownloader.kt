package com.ninthsoft.ime.base.speech

import android.content.Context
import com.ninthsoft.ime.base.net.HttpUtil
import com.ninthsoft.ime.base.util.TarBz2ExtractorUtil
import com.ninthsoft.ime.engine.rime.data.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

object ModelDownloader {
    private const val STAGE_DIR = ".extract-stage"
    private const val DEFAULT_ARCHIVE_NAME = "model.tar.bz2"
    private const val BUFFER_SIZE = 32768
    private const val REPORT_STEP = 256 * 1024L
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val TOKENS_FILE = "tokens.txt"
    private const val ENCODER_NAME = "encoder"
    private const val DECODER_NAME = "decoder"
    private const val JOINER_NAME = "joiner"
    private const val BIN_EXTENSION = "bin"
    private const val ONNX_EXTENSION = "onnx"
    private const val SO_EXTENSION = "so"
    private const val DOWNLOAD_FILE_INDEX = 1
    private const val DOWNLOAD_FILE_COUNT = 1

    private val MODEL_EXTENSIONS = setOf(BIN_EXTENSION, ONNX_EXTENSION, SO_EXTENSION)
    private val MODEL_COMPONENTS = listOf(ENCODER_NAME, DECODER_NAME, JOINER_NAME)
    private val client =
        OkHttpClient.Builder().connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val downloaded: Long,
        val total: Long,
    )

    private data class ModelFiles(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
    )

    suspend fun download(
        context: Context,
        onProgress: (Progress) -> Unit = {},
        onExtract: (current: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val manifest = SpeechModelApi.fetchManifest(context)
        val link = manifest.link.trim()
        if (link.isEmpty()) {
            Timber.w("Speech model manifest has no download link")
            return@withContext false
        }
        val tempDir = DataManager.downloadDir
        val modelDir = DataManager.speechModelDir
        val archiveName = link.substringAfterLast('/').ifBlank { DEFAULT_ARCHIVE_NAME }
        val archiveFile = File(tempDir, archiveName)
        val stageDir = File(tempDir, STAGE_DIR)
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!ensureArchive(link, archiveFile, manifest.md5, onProgress)) return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        extractAndInstall(archiveFile, stageDir, modelDir, onExtract)
    }

    private data class DownloadResult(val md5: String)

    private suspend fun ensureArchive(
        url: String,
        archiveFile: File,
        expectedMd5: String,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val md5 = expectedMd5.trim()
        val reusable = archiveFile.isFile && (md5.isEmpty() || md5Of(archiveFile).equals(md5, true))
        if (reusable) {
            Timber.i("Reusing cached archive: %s", archiveFile.absolutePath)
            return true
        }
        if (archiveFile.exists()) archiveFile.delete()
        Timber.i("Speech model download start: %s", url)
        val result = downloadFile(url, archiveFile) { read, total ->
            onProgress(
                Progress(DOWNLOAD_FILE_INDEX, DOWNLOAD_FILE_COUNT, archiveFile.name, read, total)
            )
        } ?: run {
            archiveFile.delete()
            return false
        }
        if (md5.isNotEmpty() && !result.md5.equals(md5, true)) {
            Timber.e("Speech model archive MD5 mismatch: %s", archiveFile.absolutePath)
            archiveFile.delete()
            return false
        }
        Timber.i(
            "Speech model archive ready: %s (%d bytes)",
            archiveFile.absolutePath,
            archiveFile.length()
        )
        return true
    }

    private fun extractAndInstall(
        archiveFile: File,
        stageDir: File,
        modelDir: File,
        onExtract: (Long, Long) -> Unit,
    ): Boolean {
        return runCatching {
            stageDir.deleteRecursively()
            check(stageDir.mkdirs()) { "Failed to create staging directory: ${stageDir.absolutePath}" }
            Timber.i("Extracting speech model: %s", archiveFile.name)
            TarBz2ExtractorUtil.extract(archiveFile, stageDir, onExtract, ::shouldExtract)
            val model = findModel(stageDir) ?: error("Speech model is incomplete")
            Timber.i(
                "Model files located: tokens=%s encoder=%s decoder=%s joiner=%s",
                model.tokens.name,
                model.encoder.name,
                model.decoder.name,
                model.joiner.name
            )
            installModel(model, modelDir)
            stageDir.deleteRecursively()
            archiveFile.delete()
            true
        }.onFailure { e ->
            Timber.e(e, "Failed to extract/install speech model")
            // 失败时清理阶段目录残存，避免后续解压受到干扰
            runCatching { stageDir.deleteRecursively() }
        }.getOrDefault(false)
    }

    private fun shouldExtract(name: String): Boolean {
        val fileName = name.substringAfterLast('/')
        if (fileName.equals(TOKENS_FILE, true)) return true
        return MODEL_COMPONENTS.any { isModelFile(fileName, it) }
    }

    private fun isModelFile(fileName: String, component: String): Boolean {
        val lowerName = fileName.lowercase()
        return lowerName.contains(component) && MODEL_EXTENSIONS.any { lowerName.endsWith(".$it") }
    }

    private fun findModel(root: File): ModelFiles? {
        val tokens = findFile(root) { it.name.equals(TOKENS_FILE, true) }
        val encoder = findModelFile(root, ENCODER_NAME)
        val decoder = findModelFile(root, DECODER_NAME)
        val joiner = findModelFile(root, JOINER_NAME)
        if (tokens == null || encoder == null || decoder == null || joiner == null) {
            Timber.w(
                "Incomplete speech model: tokens=%s encoder=%s decoder=%s joiner=%s",
                tokens,
                encoder,
                decoder,
                joiner
            )
            return null
        }
        return ModelFiles(tokens, encoder, decoder, joiner)
    }

    private fun findModelFile(root: File, component: String): File? =
        findFile(root) { isModelFile(it.name, component) }

    private fun findFile(root: File, predicate: (File) -> Boolean): File? {
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            if (file.isDirectory) {
                file.listFiles()?.forEach(queue::addLast)
            } else if (predicate(file)) {
                return file
            }
        }
        return null
    }

    private fun installModel(model: ModelFiles, modelDir: File) {
        val parent = modelDir.parentFile
            ?: error("Model directory has no parent: ${modelDir.absolutePath}")
        parent.mkdirs()
        // 先把完整的新模型组装到同目录临时目录，确认完整后再整体替换目标，
        // 避免把半套文件写进最终目录。
        val staging = File(parent, modelDir.name + ".tmp")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Failed to create install staging: ${staging.absolutePath}" }
        moveTo(staging, model.tokens)
        moveTo(staging, model.encoder)
        moveTo(staging, model.decoder)
        moveTo(staging, model.joiner)
        // 替换前再次校验新模型已完整
        if (findModel(staging) == null) {
            staging.deleteRecursively()
            throw IOException("New model is incomplete before installation")
        }
        if (modelDir.exists()) modelDir.deleteRecursively()
        if (!staging.renameTo(modelDir)) {
            staging.deleteRecursively()
            throw IOException("Failed to move new model into place: $staging")
        }
        Timber.i("Speech model installed: %s", modelDir.absolutePath)
    }

    private fun moveTo(destination: File, source: File) {
        val target = File(destination, source.name)
        if (target.exists()) target.delete()
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        onRead: (Long, Long) -> Unit,
    ): DownloadResult? {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Model download failed: HTTP %d", response.code)
                    HttpUtil.showToast("语音模型下载失败：HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("MD5")
                target.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return null
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReported >= REPORT_STEP || (total in 1 downTo downloaded)) {
                                onRead(downloaded, total)
                                lastReported = downloaded
                            }
                            if (total in 1 downTo downloaded) break
                        }
                    }
                }
                DownloadResult(digest.digest().joinToString("") { "%02x".format(it) })
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Speech model download failed")
            HttpUtil.showToast("语音模型下载失败：${e.message ?: "网络错误"}")
            null
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
