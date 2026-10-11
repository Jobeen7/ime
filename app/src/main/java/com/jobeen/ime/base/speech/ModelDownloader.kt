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
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

object ModelDownloader {
    private const val STAGE_DIR = ".extract-stage"
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

    /** 语音模型唯一可信的 GitHub 仓库：下载链接指向别处一律拒绝 */
    private const val GITHUB_OWNER = "k2-fsa"
    private const val GITHUB_REPO = "sherpa-onnx"

    /** 语音模型资产所在的 Release 标签（k2-fsa 全部 ASR 模型挂在这一个滚动发布下） */
    private const val ASR_MODELS_TAG = "asr-models"

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

    /** 本地已安装模型的标识（现行是 gh identity；旧版本记录的是清单 md5/link，见迁移逻辑） */
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
     * 本次要用的语音模型目标：资产名 + 其 GitHub 登记 SHA-256。
     * [identity] 是写进本地记录、供检查更新比对的版本标识
     * （"gh:<资产名>:<登记摘要>"）：以摘要为版本事实，原地换包
     * 不换名也判得出更新（与语法模型只比发布时间的教训同理）。
     */
    private data class SpeechModelTarget(
        val name: String,
        val sha256: String,
    ) {
        val identity: String = "gh:$name:$sha256"
        val downloadUrl: String =
            "https://gh-proxy.org/https://github.com/$GITHUB_OWNER/$GITHUB_REPO" +
                "/releases/download/$ASR_MODELS_TAG/$name"
    }

    /**
     * 解析目标资产：直接查 k2-fsa/sherpa-onnx 的 asr-models 资产
     * 清单（分页端点），按家族规则选最新日期的一份（见
     * [selectSpeechModelAsset]），摘要用其登记值（缺失退钉死表）。
     * 清单整体拉不到（GitHub 不可达等）时退到内置钉死表中的同家族
     * 条目；再没有返回 null（调用方判检查失败/中止下载）。不再问
     * 任何清单服务器要包：选包与版本事实都以官方仓库为准。
     */
    private fun resolveTarget(): SpeechModelTarget? {
        val assets = com.jobeen.ime.base.net.fetchGithubReleaseAssets(
            client, GITHUB_OWNER, GITHUB_REPO, ASR_MODELS_TAG,
        )
        val picked = selectSpeechModelAsset(assets.map { it.first })
        if (picked != null) {
            val sha = assets.firstOrNull { it.first == picked }?.second
                ?.ifEmpty { null }
                ?: PINNED_SPEECH_SHA256[picked]
            if (!sha.isNullOrEmpty()) return SpeechModelTarget(picked, sha)
        }
        val pinnedPicked = selectSpeechModelAsset(PINNED_SPEECH_SHA256.keys.toList())
        if (pinnedPicked != null) {
            return SpeechModelTarget(pinnedPicked, PINNED_SPEECH_SHA256.getValue(pinnedPicked))
        }
        return null
    }

    /**
     * 检查语音模型是否有新版：目标资产的 identity 与本地记录比对。
     * 本地无记录时：
     * - 模型文件已存在（老用户在该机制引入前已下载）→ 视为已是最新，
     *   把当前 identity 记为基线，避免首次检查误报"有更新"诱使用户重下大模型；
     * - 模型不存在 → 才是真的需要下载，返回有更新。
     * 下载成功后即纳入比对。
     */
    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val target = resolveTarget()
        if (target == null) {
            Timber.w("Speech model target unavailable, check failed")
            return@withContext UpdateCheckResult.Failed
        }
        val localId = getStoredManifestId(context)
        if (localId != null) {
            if (localId.equals(target.identity, ignoreCase = true)) {
                return@withContext UpdateCheckResult.UpToDate
            }
            if (localId.startsWith("gh:")) {
                return@withContext UpdateCheckResult.HasUpdate
            }
            return@withContext migrateLegacyRecord(context, target)
        }
        return@withContext if (isModelInstalled()) {
            storeManifestId(context, target.identity)
            Timber.i("Speech model id baseline recorded for existing install")
            UpdateCheckResult.UpToDate
        } else {
            UpdateCheckResult.HasUpdate
        }
    }

    /**
     * 旧记录迁移：记录是清单时代的 md5/link（不带 gh: 前缀）时的
     * 一次性判定，判完把记录改写为现行 identity，之后走正常比对。
     * - 模型未安装：记录无意义，判有更新（需要下载）；
     * - 缓存归档还在：以其真实 SHA-256 与目标摘要定判；
     * - 归档不在（安装成功后归档即被删，这是常态）：旧清单服务器
     *   当时回的正是同一 GitHub 资产（2026-10-11 实测：清单 link
     *   指向本家族的 GitHub 包、md5 与该包对应），已装模型即该资产
     *   内容，本次判无更新并改写记录。若本地其实是更旧的同家族包
     *   本次会漏报一次，但旧记录本身不含可比对的版本信息，无从
     *   更准；记录迁移后下次检查恢复准确。
     */
    private suspend fun migrateLegacyRecord(
        context: Context,
        target: SpeechModelTarget,
    ): UpdateCheckResult {
        if (!isModelInstalled()) return UpdateCheckResult.HasUpdate
        val archiveFile = File(App.downloadDir, target.name)
        if (archiveFile.isFile) {
            val sha = sha256OfFile(archiveFile)
            return if (sha.equals(target.sha256, ignoreCase = true)) {
                storeManifestId(context, target.identity)
                UpdateCheckResult.UpToDate
            } else {
                UpdateCheckResult.HasUpdate
            }
        }
        storeManifestId(context, target.identity)
        Timber.i("Speech model id migrated from legacy manifest id")
        return UpdateCheckResult.UpToDate
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
        // 目标与下载地址都由本类按 GitHub 官方资产解析（见 resolveTarget），
        // 不再向清单服务器要包；代理前缀形态与旧清单给的地址一致（国内
        // 直连 github.com 下载 Release 资产基本不可达），完整性不依赖
        // 代理、由既有 resolveExpectedSha256 锚 GitHub 登记摘要保证
        val target = resolveTarget()
        if (target == null) {
            Timber.w("Speech model target unavailable, download aborted")
            return@withContext false
        }
        val link = target.downloadUrl
        val tempDir = App.downloadDir
        val modelDir = App.speechModelDir
        val archiveFile = File(tempDir, target.name)
        val stageDir = File(tempDir, STAGE_DIR)
        if (!currentCoroutineContext().isActive) return@withContext false
        // 先取可信摘要再下载：来源不可信、或查不到摘要且无内置钉死兜底时，
        // 整包根本无从校验，提前中止，不白下数百 MB（校验口径见
        // resolveExpectedSha256，与下载后比对完全一致）
        val expectedSha = resolveExpectedSha256(link, archiveFile) ?: return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!ensureArchive(link, archiveFile, expectedSha, onProgress)) return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!verifyArchiveSha256(archiveFile, expectedSha)) return@withContext false
        if (!extractAndInstall(archiveFile, stageDir, modelDir, onExtract)) return@withContext false
        // 安装成功后记录目标 identity，供后续检查更新时比对
        storeManifestId(context, target.identity)
        true
    }

    private data class DownloadResult(val md5: String)

    private suspend fun ensureArchive(
        url: String,
        archiveFile: File,
        expectedSha: ExpectedSha256,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        // 复用只认内容：缓存归档的真实 SHA-256 与可信期望摘要一致才
        // 复用。旧口径靠清单服务器的 MD5，新路径已无清单 MD5；只看
        // 结构完整性会把上次中断的截断包/旧版本包当作可复用（其后
        // 虽有统一 SHA 校验，但那只会让整次下载失败、不会自愈重下）
        val reusable = archiveFile.isFile &&
            sha256OfFile(archiveFile).equals(expectedSha.value, ignoreCase = true)
        if (reusable) {
            Timber.i("Reusing cached archive: %s", archiveFile.absolutePath)
            return true
        }
        if (archiveFile.exists()) archiveFile.delete()
        Timber.i("Speech model download start: %s", url)
        // downloadFile 返回的过程 MD5 不再与任何外部值比对：完整性
        // 由 download() 随后以官方登记 SHA-256 统一校验
        downloadFile(url, archiveFile) { read, total ->
            onProgress(
                Progress(DOWNLOAD_FILE_INDEX, DOWNLOAD_FILE_COUNT, archiveFile.name, read, total)
            )
        } ?: run {
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
     * 下载前解析归档包的可信期望 SHA-256。下载地址现由本类按
     * GitHub 资产解析构造（见 resolveTarget），但期望摘要仍只认
     * 独立的信任锚、且必须在下载前取到（下载域名是代理、且此处
     * 对仓库坐标再做一次钉死复核，防解析被改向）：查不到且无钉死
     * 兜底时整包无从校验，直接中止、不再白下载整包（fail-closed）：
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
            // 仓库坐标必须与钉死的官方仓库一致：链接虽由本类构造，
            // 仍在此复核——指向别家仓库的链接，那里的「官方登记
            // 摘要」会对得上它自己的恶意包。只认 k2-fsa/sherpa-onnx
            // 的资产，其余 GitHub 链接一律拒绝。
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

}

/** 语音模型资产家族前缀：流式 Zipformer 中英带标点、int8 量化版（fp32 同家族不带 -int8-，不匹配） */
private const val SPEECH_MODEL_ASSET_PREFIX =
    "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-"

private val SPEECH_MODEL_DATE_REGEX = Regex("""-(\d{4})-(\d{2})-(\d{2})\.tar\.bz2$""")

/**
 * 从资产名列表中选语音模型目标（纯函数）：只认 [SPEECH_MODEL_ASSET_PREFIX]
 * 前缀 + ".tar.bz2" 后缀、且文件名带合法 yyyy-MM-dd 日期者，取日期
 * 最大的一份；同日期取字典序大者（名字更长/后缀更新）。无匹配、
 * 或候选全无合法日期时返回 null——选包规则写死在这里：上游改
 * 命名规则时跟着发版适配，不做模糊猜测。
 */
internal fun selectSpeechModelAsset(names: List<String>): String? {
    var best: String? = null
    var bestDateKey = -1
    for (name in names) {
        if (!name.startsWith(SPEECH_MODEL_ASSET_PREFIX)) continue
        if (!name.endsWith(".tar.bz2")) continue
        val match = SPEECH_MODEL_DATE_REGEX.find(name) ?: continue
        val (y, m, d) = match.destructured
        val month = m.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) continue
        // yyyyMMdd 拼成整数即与日期同序，可直接比大小
        val dateKey = y.toInt() * 10000 + month * 100 + day
        if (dateKey > bestDateKey ||
            (dateKey == bestDateKey && best != null && name > best)
        ) {
            best = name
            bestDateKey = dateKey
        }
    }
    return best
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
 * 任何换链接/换包都无法让客户端接受内置表之外的语音包。
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
