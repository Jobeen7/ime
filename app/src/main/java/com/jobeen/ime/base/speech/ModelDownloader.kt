package com.jobeen.ime.base.speech

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.base.net.HttpUtil
import com.jobeen.ime.data.App
import com.jobeen.ime.base.util.TarBz2ExtractorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.BufferedInputStream
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
    private const val DOWNLOAD_FILE_INDEX = 1
    private const val DOWNLOAD_FILE_COUNT = 1

    // 解压白名单不含 .so：下载包里的原生库从不被加载（QNN 运行时只从
    // 应用自身 nativeLibraryDir 加载），留在白名单里只是无谓的信任面
    private val MODEL_EXTENSIONS = setOf(BIN_EXTENSION, ONNX_EXTENSION)
    private val MODEL_COMPONENTS = listOf(ENCODER_NAME, DECODER_NAME, JOINER_NAME)
    // 这些客户端只访问写死的 HTTPS 地址：禁止 https↔http 重定向，
    // 避免全局放开明文后被降级（明文仅供 WebDAV 在用户显式开启后使用）
    private val client =
        OkHttpClient.Builder().followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    /** 语音模型唯一可信的 GitHub 仓库：清单链接指向别处一律拒绝 */
    private const val GITHUB_OWNER = "k2-fsa"
    private const val GITHUB_REPO = "sherpa-onnx"

    /**
     * APK 内置钉死的语音模型包摘要（按资产名）：GitHub 查询失败
     * （国内网络不可达/匿名限流）时的兜底信任锚，避免 fail-closed
     * 误伤正常安装。上游换代新包时 GitHub 查询照常工作；查询失败
     * 且文件与钉死值对不上时拒绝安装。
     */
    private val PINNED_SPEECH_SHA256 = mapOf(
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2" to
            "8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399",
        "sherpa-onnx-qnn-SM8850-binary-x-asr-streaming-zipformer-transducer-zh-en-punct-2026-06-05-chunk-size-160ms.tar.bz2" to
            "01dbcdbc6260a3bd55f902088a156c691888bb2c44f36299ae209797049f00a5",
    )

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
        // 先取可信摘要再下载：来源不可信、或查不到摘要且无内置钉死兜底时，
        // 整包根本无从校验，提前中止，不白下数百 MB（校验口径见
        // resolveExpectedSha256，与下载后比对完全一致）
        val expectedSha = resolveExpectedSha256(link, archiveFile) ?: return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!ensureArchive(link, archiveFile, manifest.md5, onProgress)) return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!verifyArchiveSha256(archiveFile, expectedSha)) return@withContext false
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
        // 有 md5 时按 md5 判定复用（主路径不变）；manifest 未给 md5 时不能
        // 凭文件存在就复用——旧包可能是上次中断留下的截断包，先验包完整性
        // （可完整解压且含预期条目），验不过走下面删除重下
        val reusable = archiveFile.isFile && if (md5.isNotEmpty()) {
            md5Of(archiveFile).equals(md5, true)
        } else {
            isArchiveComplete(archiveFile)
        }
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

    /** 已解析的可信期望摘要；[fromGithubLink] 区分不一致时的提示措辞（官方登记 vs 内置钉死） */
    private data class ExpectedSha256(
        val value: String,
        val fromGithubLink: Boolean,
    )

    /**
     * 下载前解析归档包的可信期望 SHA-256（MD5 之外）。清单只给地址与
     * MD5 且同出一台服务器，MD5 只能防损坏、防不住服务器被攻破后连摘要
     * 一起替换，因此期望摘要只认独立于清单服务器的信任锚，且必须在
     * 下载前取到：查不到且无钉死兜底时整包无从校验，直接中止、不再
     * 白下载整包（fail-closed）：
     * - 链接是 GitHub Release 资产（可含代理前缀）→ 仓库必须是钉死的
     *   官方仓库（k2-fsa/sherpa-onnx），否则拒绝；摘要向 GitHub API 查
     *   该资产登记的官方值，查询失败退 APK 内置钉死表。此分支中止时
     *   不删已缓存的包：缓存包已通过 MD5，摘要查询恢复后重试可直接
     *   校验落盘，不必重下整包；
     * - 非 GitHub 链接 → 只认 APK 内置钉死摘要（按文件名查表）：旧
     *   TOFU（首次见到即钉住）在服务器被攻破时等于没有校验——换一个
     *   链接就能让客户端把新包钉为可信，已废除；内置表没有的包拒绝。
     * 拒绝时已按既有口径 Toast 说明，返回 null 表示调用方直接中止。
     */
    private fun resolveExpectedSha256(link: String, archiveFile: File): ExpectedSha256? {
        val ref = parseGithubReleaseAssetUrl(link)
        if (ref != null) {
            // 仓库坐标必须与钉死的官方仓库一致：链接与 MD5 同出清单
            // 服务器，服务器被攻破时可把链接改指向攻击者自己的仓库，
            // 那里的「官方登记摘要」会对得上它自己的恶意包。只认
            // k2-fsa/sherpa-onnx 的资产，其余 GitHub 链接一律拒绝。
            if (ref.owner != GITHUB_OWNER || ref.repo != GITHUB_REPO) {
                Timber.e(
                    "Speech model: link points at untrusted repo %s/%s, refusing install",
                    ref.owner, ref.repo,
                )
                HttpUtil.showToast("语音模型校验失败：下载来源不可信，已拒绝安装")
                archiveFile.delete()
                return null
            }
            val expected = fetchGithubAssetSha256(ref)
                .ifEmpty { PINNED_SPEECH_SHA256[ref.assetName] ?: "" }
            if (expected.isEmpty()) {
                // GitHub 查询失败且无内置钉死摘要：明确告知校验环节
                // 出问题，而不是笼统的「下载失败」
                Timber.w("Speech model: GitHub digest unavailable, refusing install")
                HttpUtil.showToast("语音模型校验失败：无法获取官方校验值")
                return null
            }
            return ExpectedSha256(expected, fromGithubLink = true)
        }
        // 非 GitHub 链接：只认 APK 内置钉死摘要（按链接尾部的文件名查表）
        val expected = expectedShaForNonGithubLink(link, PINNED_SPEECH_SHA256)
        if (expected.isEmpty()) {
            Timber.w("Speech model: non-GitHub link without pinned digest, refusing install")
            HttpUtil.showToast("语音模型校验失败：下载来源无内置校验值，已拒绝安装")
            archiveFile.delete()
            return null
        }
        return ExpectedSha256(expected, fromGithubLink = false)
    }

    /**
     * 归档包 SHA-256 强校验：文件内容与下载前已解析的可信期望摘要比对，
     * 对不上拒绝安装并删包（摘要本身的来源与 fail-closed 口径见
     * [resolveExpectedSha256]）。
     */
    private suspend fun verifyArchiveSha256(
        archiveFile: File,
        expected: ExpectedSha256,
    ): Boolean {
        val actual = sha256OfFile(archiveFile)
        if (actual.equals(expected.value, ignoreCase = true)) return true
        if (expected.fromGithubLink) {
            Timber.e("Speech model archive SHA-256 mismatch vs GitHub digest")
            HttpUtil.showToast("语音模型校验失败：文件与官方摘要不一致，已拒绝安装")
        } else {
            Timber.e("Speech model archive SHA-256 mismatch vs pinned digest (non-GitHub link)")
            HttpUtil.showToast("语音模型校验失败：文件与内置摘要不一致，已拒绝安装")
        }
        archiveFile.delete()
        return false
    }

    /** 向 GitHub API 查 Release 资产登记的 sha256；任何失败返回空串 */
    private fun fetchGithubAssetSha256(ref: GithubAssetRef): String =
        com.jobeen.ime.base.net.fetchGithubReleaseAssetSha256(
            client, ref.owner, ref.repo, ref.tag, ref.assetName,
        )

    private suspend fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            var chunks = 0
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
                if (++chunks % 32 == 0) currentCoroutineContext().ensureActive()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
                if (response.code == 416 && resumeFrom > 0) {
                    // 416 Range Not Satisfiable：本地 .part 已不小于远端文件
                    // （远端换过更小的文件等），续传永远撞 416。删掉 .part
                    // 后不带 Range 全量重下一次（重入时 resumeFrom=0，不会循环）
                    Timber.w("Model download: HTTP 416, dropping .part and retrying full download")
                    part.delete()
                    return downloadFile(url, target, onRead)
                }
                if (!response.isSuccessful) {
                    Timber.w("Model download failed: HTTP %d", response.code)
                    HttpUtil.showToast("语音模型下载失败：HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                val append = response.code == 206 && resumeFrom > 0
                if (append) {
                    // 206 只证明服务器支持续传，不证明它给的正是我们要的
                    // 区间：Content-Range 起点必须与续传点一致，缺失或
                    // 对不上时把这段内容追加进 .part 只会拼出损坏文件。
                    // 弃用本次响应、删 .part 全量重下（重入时 resumeFrom
                    // 为 0，不会再进本分支）
                    val rangeStart = parseContentRangeStart(
                        response.header("Content-Range")
                    )
                    if (rangeStart != resumeFrom) {
                        Timber.w(
                            "Model download: Content-Range start %s != resume point %d, restarting full download",
                            rangeStart, resumeFrom,
                        )
                        part.delete()
                        return downloadFile(url, target, onRead)
                    }
                }
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

    /**
     * 无 md5 时的旧包完整性校验：完整遍历一遍 tar.bz2（遍历会把每个条目
     * 的数据都解压读过，流损坏/截断会抛异常），并确认含 tokens 与
     * encoder/decoder/joiner 预期条目（判定口径与解压过滤、findModel 一致）。
     */
    private fun isArchiveComplete(archiveFile: File): Boolean {
        return runCatching {
            var hasTokens = false
            val foundComponents = mutableSetOf<String>()
            java.io.FileInputStream(archiveFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val fileName = entry.name.substringAfterLast('/')
                                    if (fileName.equals(TOKENS_FILE, true)) hasTokens = true
                                    MODEL_COMPONENTS.filter { isModelFile(fileName, it) }
                                        .forEach { foundComponents.add(it) }
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }
            }
            hasTokens && foundComponents.containsAll(MODEL_COMPONENTS)
        }.onFailure {
            Timber.w(it, "Cached speech model archive incomplete, will re-download")
        }.getOrDefault(false)
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

/**
 * 解析 Content-Range 响应头（`bytes <start>-<end>/<total|*>`）的区间
 * 起点；格式不符返回 null。续传校验用：服务器回 206 时必须核对它
 * 给的确实是从本地续传点开始的那一段。
 */
internal fun parseContentRangeStart(header: String?): Long? {
    val h = header?.trim() ?: return null
    if (!h.startsWith("bytes ")) return null
    val rangePart = h.substring("bytes ".length).substringBefore('/')
    return rangePart.substringBefore('-').trim().toLongOrNull()
}

/** GitHub Release 资产坐标（从下载链接解析） */
internal data class GithubAssetRef(
    val owner: String,
    val repo: String,
    val tag: String,
    val assetName: String,
)

/**
 * 从下载链接解析 GitHub Release 资产坐标。链接可带任意代理前缀
 * （如 https://gh-proxy.org/https://github.com/...），只要路径里含
 * github.com/<owner>/<repo>/releases/download/<tag>/<asset> 即可；
 * 不是这种形态返回 null。
 */
internal fun parseGithubReleaseAssetUrl(url: String): GithubAssetRef? {
    val clean = url.substringBefore('?').substringBefore('#')
    val marker = "github.com/"
    val idx = clean.indexOf(marker)
    if (idx < 0) return null
    val parts = clean.substring(idx + marker.length).split('/')
    if (parts.size < 6) return null
    if (parts[2] != "releases" || parts[3] != "download") return null
    val assetName = parts.subList(5, parts.size).joinToString("/")
    if (parts[0].isBlank() || parts[1].isBlank() || parts[4].isBlank() ||
        assetName.isBlank()
    ) {
        return null
    }
    return GithubAssetRef(parts[0], parts[1], parts[4], assetName)
}

/** TOFU 期望值：钉住记录与当前链接一致时返回钉住的 sha256，否则空串 */
/**
 * 非 GitHub 下载链接的期望摘要：按链接尾部的文件名查 APK 内置钉死表。
 * 查不到返回空串（调用方拒绝安装）——不再有 TOFU 式的首次信任，
 * 清单服务器换链接/换包都无法让客户端接受内置表之外的语音包。
 */
internal fun expectedShaForNonGithubLink(
    link: String,
    pinned: Map<String, String>,
): String {
    val fileName = link.substringBefore('?').substringBefore('#')
        .substringAfterLast('/').trim()
    if (fileName.isEmpty()) return ""
    return pinned[fileName]?.trim().orEmpty()
}
