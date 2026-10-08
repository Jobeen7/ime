package com.jobeen.ime.base.speech

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.base.net.HttpUtil
import com.jobeen.ime.data.App
import com.jobeen.ime.base.util.TarBz2ExtractorUtil
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
    private const val BPE_MODEL_FILE = "bpe.model"
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

    /** 检查服务端模型是否有新版的结果 */
    sealed interface UpdateCheckResult {
        data object HasUpdate : UpdateCheckResult
        data object UpToDate : UpdateCheckResult
        data object Failed : UpdateCheckResult
    }

    private const val PREFS_NAME = "speech_model_prefs"
    private const val KEY_MANIFEST_ID = "manifest_id"

    /** 本地已安装模型的服务端标识（manifest 的 md5，缺失时用下载链接） */
    fun getStoredManifestId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST_ID, null)
    }

    private fun storeManifestId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_MANIFEST_ID, id)
        }
    }

    /**
     * 检查服务端是否有新版语音模型。
     * 比对服务端 manifest 标识与本地下载时记录的标识；本地无记录时：
     * - 模型文件已存在（老用户在该机制引入前已下载）→ 视为已是最新，
     *   把当前服务端标识记为基线，避免首次检查误报"有更新"诱使用户重下大模型；
     * - 模型不存在 → 才是真的需要下载，返回有更新。
     * 下载成功后即纳入比对。
     */
    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val manifest = SpeechModelApi.fetchManifest(context)
        val serverId = manifest.md5.trim().ifEmpty { manifest.link.trim() }
        if (serverId.isEmpty()) {
            Timber.w("Speech model manifest unavailable, check failed")
            return@withContext UpdateCheckResult.Failed
        }
        val localId = getStoredManifestId(context)
        if (localId != null) {
            return@withContext if (localId.equals(serverId, ignoreCase = true)) {
                UpdateCheckResult.UpToDate
            } else {
                UpdateCheckResult.HasUpdate
            }
        }
        return@withContext if (isModelInstalled()) {
            storeManifestId(context, serverId)
            Timber.i("Speech model id baseline recorded for existing install")
            UpdateCheckResult.UpToDate
        } else {
            UpdateCheckResult.HasUpdate
        }
    }

    /** 本地是否已有完整语音模型（tokens + encoder/decoder/joiner） */
    private fun isModelInstalled(): Boolean {
        val dir = App.speechModelDir
        if (!File(dir, TOKENS_FILE).isFile) return false
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        fun pick(prefix: String, ext: String) = files.firstOrNull {
            it.extension.equals(ext, ignoreCase = true) &&
                it.nameWithoutExtension.contains(prefix, ignoreCase = true)
        }
        return (pick(ENCODER_NAME, ONNX_EXTENSION) != null &&
            pick(DECODER_NAME, ONNX_EXTENSION) != null &&
            pick(JOINER_NAME, ONNX_EXTENSION) != null) ||
            (pick(ENCODER_NAME, BIN_EXTENSION) != null &&
                pick(DECODER_NAME, BIN_EXTENSION) != null &&
                pick(JOINER_NAME, BIN_EXTENSION) != null)
    }

    private data class ModelFiles(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
        // bpe.model 是热词（bpe 建模单元）导出词表的源文件，此前解压时
        // 被丢弃；保留它，模型换代时才能重新导出匹配的 bpe.vocab。
        val bpeModel: File? = null,
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
        val tempDir = App.downloadDir
        val modelDir = App.speechModelDir
        val archiveName = link.substringAfterLast('/').ifBlank { DEFAULT_ARCHIVE_NAME }
        val archiveFile = File(tempDir, archiveName)
        val stageDir = File(tempDir, STAGE_DIR)
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!ensureArchive(link, archiveFile, manifest.md5, onProgress)) return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!extractAndInstall(archiveFile, stageDir, modelDir, onExtract)) return@withContext false
        // 安装成功后记录服务端标识，供后续检查更新时比对
        val serverId = manifest.md5.trim().ifEmpty { link.trim() }
        if (serverId.isNotEmpty()) storeManifestId(context, serverId)
        true
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
        if (fileName.equals(BPE_MODEL_FILE, true)) return true
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
        val bpeModel = findFile(root) { it.name.equals(BPE_MODEL_FILE, true) }
        return ModelFiles(tokens, encoder, decoder, joiner, bpeModel)
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
        model.bpeModel?.let { moveTo(staging, it) }
        // 替换前再次校验新模型已完整
        if (findModel(staging) == null) {
            staging.deleteRecursively()
            throw IOException("New model is incomplete before installation")
        }
        // 三步换名（同父目录 rename 都是原子操作）：旧目录先改名备份、
        // 新目录就位、最后删备份。此前是先 deleteRecursively 再 rename，
        // 两步之间进程被杀或 rename 失败即新旧模型全失、只能整包重下
        val backup = File(parent, modelDir.name + ".old")
        // 自愈：上次安装恰好中断在换名之间（正目录缺失、备份还在）先回滚
        if (!modelDir.exists() && backup.exists()) {
            backup.renameTo(modelDir)
        }
        backup.deleteRecursively()
        val hadOld = modelDir.exists()
        if (hadOld && !modelDir.renameTo(backup)) {
            staging.deleteRecursively()
            throw IOException("Failed to move old model aside: $modelDir")
        }
        if (!staging.renameTo(modelDir)) {
            // 新目录就位失败：把旧模型改回来，不能留下空目录
            if (hadOld) backup.renameTo(modelDir)
            staging.deleteRecursively()
            throw IOException("Failed to move new model into place: $staging")
        }
        backup.deleteRecursively()
        Timber.i("Speech model installed: %s", modelDir.absolutePath)
    }

    private fun moveTo(destination: File, source: File) {
        val target = File(destination, source.name)
        if (target.exists()) target.delete()
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    /**
     * 下载到 `target.part` 临时文件，支持断点续传：中断（取消/失败）保留
     * 已下部分，下次请求带 Range 续传；服务器不支持 Range（回 200）时
     * 自动从零重下。完整下完并算完 MD5 后才 rename 到 target。
     * 返回的 MD5 覆盖整个文件（含续传前已下的部分），供上层校验。
     */
    private suspend fun downloadFile(
        url: String,
        target: File,
        onRead: (Long, Long) -> Unit,
    ): DownloadResult? {
        val part = File(target.path + ".part")
        val resumeFrom = if (part.isFile) part.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        if (resumeFrom > 0) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Model download failed: HTTP %d", response.code)
                    HttpUtil.showToast("语音模型下载失败：HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                val append = response.code == 206 && resumeFrom > 0
                val startAt = if (append) resumeFrom else 0L
                val total = if (append) startAt + body.contentLength() else body.contentLength()
                val digest = MessageDigest.getInstance("MD5")
                if (append) {
                    // 续传部分的 MD5 要含已下内容：先把 .part 已有字节喂给 digest
                    part.inputStream().use { existing ->
                        val buf = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val n = existing.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                }
                java.io.FileOutputStream(part, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = startAt
                        var lastReported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return null
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            val finished = total > 0 && downloaded >= total
                            if (downloaded - lastReported >= REPORT_STEP || finished) {
                                onRead(downloaded, total)
                                lastReported = downloaded
                            }
                            if (finished) break
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    Timber.w("Model download: rename .part failed")
                    return null
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
