package com.jobeen.ime.base.speech

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.base.net.HttpUtil
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.data.App
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.base.util.TarBz2ExtractorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
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
    private const val ONNX_EXTENSION = "onnx"
    private const val DOWNLOAD_FILE_INDEX = 1
    private const val DOWNLOAD_FILE_COUNT = 1

    // 解压白名单不含 .so：下载包里的原生库从不被加载，
    // 留在白名单里只是无谓的信任面；也不含 .bin（QNN 时代格式，
    // 运行时已只认 onnx，见 isModelInstalled）
    private val MODEL_EXTENSIONS = setOf(ONNX_EXTENSION)
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

        /** 目标只来自内置钉死表（服务端不可达）、未与服务端核对过：不得报「已是最新」 */
        data object Unverified : UpdateCheckResult
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
     * （国内网络不可达/匿名限流）时 [resolveTarget] 的回落目标来
     * 源——离线也能装上这份包并按此摘要校验。目标只来自钉死表时
     * 检查更新报「未验证」，不用它与本地记录推断新旧（D5 已拆）。
     */
    private val PINNED_SPEECH_SHA256 = mapOf(
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2" to
            "8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399",
    )

    /** 本地已安装模型的标识（现行是 gh identity；旧版本记录的清单 md5/link 不含可比对的版本信息，视同无记录） */
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
        /** 目标是否来自实时 GitHub 资产清单（否＝服务端不可达时回落钉死表选出） */
        val fromLiveApi: Boolean = false,
        /** 摘要是否来自 APK 内置钉死表（而非 GitHub 资产清单登记值）：只影响校验失败时的提示措辞 */
        val shaFromPinned: Boolean = false,
    ) {
        val identity: String = "gh:$name:$sha256"
        val downloadUrl: String =
            "https://gh-proxy.org/https://github.com/$GITHUB_OWNER/$GITHUB_REPO" +
                "/releases/download/$ASR_MODELS_TAG/$name"
    }

    /**
     * 最近一次检查更新解析出的目标（进程内）：download 优先固定
     * 使用它——检查已经向用户公示过这个目标，下载时现解析若因
     * GitHub 限流退到钉死回落，会静默换成钉死表里的旧包装上，
     * 下次检查又报有更新，形成「报更新→装旧包」循环。进程重启
     * 即失效（检查本来就先于下载发生），失效时 download 走现解析，
     * 见 download 内注释。
     */
    @Volatile
    private var announcedTarget: SpeechModelTarget? = null

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
            val registered = assets.firstOrNull { it.first == picked }?.second
                ?.ifEmpty { null }
            if (registered != null) return SpeechModelTarget(picked, registered, fromLiveApi = true)
            val pinned = PINNED_SPEECH_SHA256[picked]
            if (!pinned.isNullOrEmpty()) {
                return SpeechModelTarget(picked, pinned, shaFromPinned = true, fromLiveApi = true)
            }
        }
        val pinnedPicked = selectSpeechModelAsset(PINNED_SPEECH_SHA256.keys.toList())
        if (pinnedPicked != null) {
            return SpeechModelTarget(
                pinnedPicked,
                PINNED_SPEECH_SHA256.getValue(pinnedPicked),
                shaFromPinned = true,
            )
        }
        return null
    }

    /**
     * 检查语音模型是否有新版：目标资产的 identity 与本地记录比对。
     * 记录与目标一致时还要确认模型文件真在盘上（记录会随备份/迁移
     * 跨设备而来、文件不会），不在盘一律判有更新，见函数内注释。
     * 本地无记录时：
     * - 模型文件已存在（老用户在该机制引入前已下载）→ 视为已是最新，
     *   把当前 identity 记为基线，避免首次检查误报"有更新"诱使用户重下大模型；
     * - 模型不存在 → 才是真的需要下载，返回有更新。
     * 下载成功后即纳入比对。
     */
    /** 「已是最新」只在目标来自实时清单时成立；钉死回落目标未经服务端核对，降级为未确认（V7b） */
    private fun verified(result: UpdateCheckResult, target: SpeechModelTarget): UpdateCheckResult =
        if (result == UpdateCheckResult.UpToDate && !target.fromLiveApi) {
            UpdateCheckResult.Unverified
        } else {
            result
        }

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        cleanupLegacyCdspDir()
        val target = resolveTarget()
        if (target == null) {
            Timber.w("Speech model target unavailable, check failed")
            return@withContext UpdateCheckResult.Failed
        }
        // 解析成功即记为已公示目标，供 download 固定使用（见字段注释）
        announcedTarget = target
        val localId = getStoredManifestId(context)
        if (localId != null && localId.startsWith("gh:")) {
            if (localId.equals(target.identity, ignoreCase = true)) {
                // 记录一致也要确认模型真的在盘：记录是设备本地状态，
                // 会随备份/迁移来到新设备、文件却不会，只看记录会把
                // 「实际没装」误报成「已是最新」，用户永远得不到重下
                // 提示。在盘检查本就在 IO 线程、低频调用，开销可忽略
                return@withContext if (isModelInstalled()) {
                    verified(UpdateCheckResult.UpToDate, target)
                } else {
                    UpdateCheckResult.HasUpdate
                }
            }
            // identity 不同（相等支已在上方处理）：目标来自实时
            // 清单即有更新；只拿到钉死回落目标时，它与本地记录谁
            // 新谁旧无从判定——既不报有更新、也不报已是最新，如
            // 实报未验证。不再用资产日期或摘要来源做新旧裁决
            // （原日期防降级＋钉死盲区已按 D5 拆除）
            return@withContext if (target.fromLiveApi) {
                UpdateCheckResult.HasUpdate
            } else {
                UpdateCheckResult.Unverified
            }
        }
        // 无记录，或清单时代的旧记录（md5/link，不带 gh: 前缀）：
        // 旧记录不含可比对的版本信息，视同无记录——已装则把当
        // 前目标记为基线（旧用户在该机制引入前已下载的情形同理，
        // 避免首次检查误报「有更新」诱使用户重下大模型），未装
        // 判有更新
        return@withContext if (isModelInstalled()) {
            storeManifestId(context, target.identity)
            Timber.i("Speech model id baseline recorded for existing install")
            verified(UpdateCheckResult.UpToDate, target)
        } else {
            UpdateCheckResult.HasUpdate
        }
    }

    /**
     * 本地是否已有完整语音模型：与运行时就绪判定共用同一份定位
     * 规则（[findSpeechModelFiles]，只认 onnx）。此前本函数另有
     * 一份实现且认 .bin 三件套（QNN 时代格式），与运行时口径分
     * 叉：只剩 .bin 的目录会被判「已装、已是最新」，键盘侧却报
     * 模型缺失（V5）
     */
    private fun isModelInstalled(): Boolean =
        findSpeechModelFiles(App.speechModelDir) != null

    /** 旧版 cdsp 残留清理是否已在本进程尝试过（每进程至多一次） */
    @Volatile
    private var legacyCdspCleanupAttempted = false

    /**
     * 一次性清理旧版残留：QNN 时代启动时拷到 filesDir/cdsp 的库
     * （约 13 MB）已无任何加载方，留着只是白占空间。幂等：目录
     * 不存在即无操作；删除失败不挡正事，下次进程启动再试。
     */
    private fun cleanupLegacyCdspDir() {
        if (legacyCdspCleanupAttempted) return
        legacyCdspCleanupAttempted = true
        val dir = File(appContext.filesDir, "cdsp")
        if (!dir.exists()) return
        runCatching { dir.deleteRecursively() }
            .onSuccess { Timber.i("Legacy cdsp dir removed: %s", dir.absolutePath) }
            .onFailure { Timber.w(it, "Legacy cdsp dir cleanup failed: %s", dir.absolutePath) }
    }

    private data class ModelFiles(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
    )

    /**
     * 语-O2：下载器本身的进程内互斥（协程 Mutex，与本文件 suspend +
     * Dispatchers.IO 风格一致）。UI 层已有「下载中禁重复触发」，但
     * Activity 重建后新旧 composition 可并发调 downloadModel→download，
     * 两路会同时写同一组归档/staging/模型路径。持锁范围覆盖整次下载
     * （下载＋解压＋安装）：第二路挂起等待、不占线程，可取消
     * （等待中取消抛 CancellationException，与现有取消口径一致）；
     * 等到锁后自己再完整跑一遍（归档按 SHA 复用、安装换名幂等），
     * 返回自己这一遍的结果。checkForUpdate 不写模型文件、不持此锁
     * （只读＋volatile 公示目标）。锁只在 download 内单次获取，
     * 体内无任何再取此锁的调用，无嵌套死锁。
     */
    private val downloadMutex = Mutex()

    suspend fun download(
        context: Context,
        onProgress: (Progress) -> Unit = {},
        onExtract: (current: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean {
        // 锁在 withContext 之外获取：等待挂起在调用方协程、不占线程；
        // 持锁期间体内的 return@withContext 仍走 finally 解锁。
        downloadMutex.lock()
        try {
            return withContext(Dispatchers.IO) {
        // 目标与下载地址都由本类按 GitHub 官方资产解析（见 resolveTarget），
        // 不再向清单服务器要包；代理前缀形态与旧清单给的地址一致（国内
        // 直连 github.com 下载 Release 资产基本不可达），完整性不依赖
        // 代理、由目标摘要（GitHub 登记值或钉死回落值）校验保证。
        // 目标优先用检查时已公示的那份（announcedTarget）：下载时现解析
        // 可能因限流退到钉死回落、静默换上旧包。无已公示目标时才现
        // 解析。不再对目标做新旧裁决：下载是用户明确发起的动作，
        // 装哪份由公示/现解析的目标决定（原日期防降级守卫已按
        // D5 拆除）
        cleanupLegacyCdspDir()
        val announced = announcedTarget
        val target = if (announced != null) {
            announced
        } else {
            val resolved = resolveTarget()
            if (resolved == null) {
                Timber.w("Speech model target unavailable, download aborted")
                return@withContext false
            }
            resolved
        }
        val tempDir = App.downloadDir
        val modelDir = App.speechModelDir
        val stageDir = File(tempDir, STAGE_DIR)
        val archiveFile = File(tempDir, target.name)
        val link = target.downloadUrl
        if (!currentCoroutineContext().isActive) return@withContext false
        // 期望摘要直接复用 resolveTarget 已取得的 target.sha256
        // （GitHub 登记值或钉死回落值），不为同一资产二次查询
        val expectedSha = ExpectedSha256(target.sha256, fromGithubLink = !target.shaFromPinned)
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!ensureArchive(link, archiveFile, expectedSha, onProgress)) return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        // 摘要失配即删包并明确失败（S21）：不再猜测上游原地换包、
        // 在同一次下载内换目标整轮重下——请用户重新检查更新后再来
        if (!verifyArchiveSha256(archiveFile, expectedSha, mismatchToast = shaMismatchToast(expectedSha))) {
            return@withContext false
        }
        if (!extractAndInstall(archiveFile, stageDir, modelDir, onExtract)) return@withContext false
        // 安装成功后记录目标 identity，供后续检查更新时比对
        storeManifestId(context, target.identity)
        true
            }
        } finally {
            downloadMutex.unlock()
        }
    }

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
        // downloadFile 本身不算摘要：完整性由 download() 随后以目
        // 标 SHA-256 统一校验
        val ok = downloadFile(url, archiveFile) { read, total ->
            onProgress(
                Progress(DOWNLOAD_FILE_INDEX, DOWNLOAD_FILE_COUNT, archiveFile.name, read, total)
            )
        }
        if (!ok) {
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

    /** 普通校验失败的提示措辞：按期望摘要来源区分官方登记/内置钉死 */
    private fun shaMismatchToast(expected: ExpectedSha256): String =
        if (expected.fromGithubLink) {
            "语音模型校验失败：文件与官方摘要不一致，已拒绝安装"
        } else {
            "语音模型校验失败：文件与内置摘要不一致，已拒绝安装"
        }

    /**
     * 归档包 SHA-256 强校验：文件内容与下载前已解析的期望摘要比对
     * （来源见 [SpeechModelTarget.sha256]），对不上拒绝安装并删包。
     * [mismatchToast] 非空时失配即按其文案提示；为空时静默（由调
     * 用方统一收尾提示）。
     */
    private suspend fun verifyArchiveSha256(
        archiveFile: File,
        expected: ExpectedSha256,
        mismatchToast: String?,
    ): Boolean {
        val actual = sha256OfFile(archiveFile)
        if (actual.equals(expected.value, ignoreCase = true)) return true
        Timber.e(
            "Speech model archive SHA-256 mismatch (fromGithubLink=%s)",
            expected.fromGithubLink,
        )
        if (mismatchToast != null) HttpUtil.showToast(mismatchToast)
        archiveFile.delete()
        return false
    }

    /** 实现已收敛到 [DigestUtil.sha256HexCancellable]（口径逐行同构：32KB 缓冲、每 32 块查取消）。 */
    private suspend fun sha256OfFile(file: File): String =
        DigestUtil.sha256HexCancellable(file)

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
        // 整体换装（自愈恢复、旧目录让位、新目录就位、在位校验后删
        // 备份）见 replaceModelDirectory：任一步失败抛 IOException，
        // 备份原地保留（下次安装仍可自愈）；失败时 staging 残存由
        // 这里清理，成功时 staging 已随换名消失、无须清理。
        try {
            replaceModelDirectory(staging, modelDir)
        } catch (e: IOException) {
            staging.deleteRecursively()
            throw e
        }
    }

    /**
     * 把已校验完整的 staging 新模型整体换到 modelDir（同父目录换名三步，
     * 原子换名、无半套写进最终目录）。失败一律抛 IOException——调用方
     * [extractAndInstall] 捕获后返回 false，下载层报失败；错误信息点名
     * 失败步骤与备份去向。
     *
     * 备份处置（R1-3：只有确认恢复成功、或新模型完整提交并在位校验后，才动 .old）：
     * - 正目录缺失且备份存在：先恢复备份；恢复失败抛错、备份原地保留，
     *   绝不删除——下次安装仍可自愈；恢复成功后备份已随换名消失，无须再删。
     * - 正目录与备份并存：在位模型完整时备份是已被取代的旧模型，删之腾出
     *   让位位置（删失败抛错、备份保留）；在位模型残缺时先删残缺在位、
     *   以备份恢复在位，再按正常换装走（恢复出的旧模型随后被换下）。
     * - 新模型就位后先在位校验完整，才删刚换下的旧备份；删失败抛错
     *   （新模型已在位、备份保留，下次安装按「并存」分支处理）。
     * - 就位失败回滚：回滚失败抛错、备份保留（下次安装走自愈分支），
     *   绝不在此删备份。
     *
     * [rename] 注入点：生产默认即 File.renameTo；主机单测以此注入失败——
     * 同父目录换名在 root 测试用户下无法用只读/占位使其失败（已实测：
     * 0555 父目录下 rename 仍成功；目录→非空目录/文件才必败，但自愈与
     * 就位分支的目标路径按构造不可能处于那种状态），故失败分支不用
     * 文件系统状态注入，见 ModelDownloaderFaultTest。
     */
    internal fun replaceModelDirectory(
        staging: File,
        modelDir: File,
        rename: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
    ) {
        val parent = modelDir.parentFile
            ?: error("Model directory has no parent: ${modelDir.absolutePath}")
        parent.mkdirs()
        val backup = File(parent, modelDir.name + ".old")
        // 自愈：上次安装恰好中断在换名之间（正目录缺失、备份还在）先恢复。
        // 恢复失败必须抛错且保留备份：旧代码此处不验返回值、随后无条件
        // 删备份，恢复失败即最后一把旧模型丢失。
        if (!modelDir.exists() && backup.exists()) {
            if (!rename(backup, modelDir)) {
                throw IOException(
                    "Failed to restore model backup: $backup -> $modelDir; " +
                        "backup kept for next install",
                )
            }
        }
        // 正目录与备份并存：上次安装中断在「新目录已就位、备份未删」，
        // 或残缺在位与备份并存。完整在位→备份已被取代，删之腾位；
        // 残缺在位→备份是最后一把好模型，先恢复在位再正常换装。
        if (modelDir.exists() && backup.exists()) {
            if (findModel(modelDir) == null) {
                modelDir.deleteRecursively()
                if (!rename(backup, modelDir)) {
                    throw IOException(
                        "Failed to restore model backup over incomplete model: " +
                            "$backup -> $modelDir; backup kept for next install",
                    )
                }
            } else {
                val removed = backup.deleteRecursively()
                if (!removed && backup.exists()) {
                    throw IOException("Failed to remove stale model backup: $backup")
                }
            }
        }
        val hadOld = modelDir.exists()
        if (hadOld && !rename(modelDir, backup)) {
            throw IOException("Failed to move old model aside: $modelDir -> $backup")
        }
        if (!rename(staging, modelDir)) {
            // 新目录就位失败：把旧模型改回来，不能留下空目录。
            // 回滚失败同样抛错、备份保留（下次安装走自愈分支），绝不删备份。
            if (hadOld && !rename(backup, modelDir)) {
                throw IOException(
                    "Failed to move new model into place and failed to restore old model: " +
                        "staging=$staging, backup=$backup kept for next install",
                )
            }
            throw IOException(
                "Failed to move new model into place: $staging -> $modelDir; " +
                    "old model restored",
            )
        }
        // 新模型已提交：在位再次校验完整，才删刚换下的旧备份。
        if (hadOld) {
            if (findModel(modelDir) == null) {
                // 在位新模型不完整：清掉它、把旧模型换回来；回滚失败则
                // 备份保留，下次安装走自愈分支。
                modelDir.deleteRecursively()
                if (!rename(backup, modelDir)) {
                    throw IOException(
                        "Installed model incomplete and failed to restore old model: " +
                            "backup=$backup kept for next install",
                    )
                }
                throw IOException("Installed model incomplete, old model restored: $modelDir")
            }
            val removed = backup.deleteRecursively()
            if (!removed && backup.exists()) {
                throw IOException(
                    "Failed to remove old model backup after new model installed: " +
                        "$backup; new model is in place, backup kept",
                )
            }
        }
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
     * 自动从零重下。完整下完后才 rename 到 target；返回是否成功。
     * 本函数不算任何摘要，完整性由调用方以目标 SHA-256 统一校验。
     */
    private suspend fun downloadFile(
        url: String,
        target: File,
        onRead: (Long, Long) -> Unit,
    ): Boolean {
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
                    return false
                }
                val body = response.body ?: return false
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
                java.io.FileOutputStream(part, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = startAt
                        var lastReported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return false
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val finished = total > 0 && downloaded >= total
                            if (downloaded - lastReported >= REPORT_STEP || finished) {
                                onRead(downloaded, total)
                                lastReported = downloaded
                            }
                            if (finished) break
                        }
                    }
                    // 写完先 fsync 文件本体再 rename：只靠 close 落盘，
                    // 断电时 .part 内容可能还没真正上盘，rename 后的
                    // 归档会是残缺的（后续 SHA 校验能拦住安装，但会
                    // 白下一整包）
                    output.fd.sync()
                }
                if (!part.renameTo(target)) {
                    Timber.w("Model download: rename .part failed")
                    return false
                }
                true
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Speech model download failed")
            HttpUtil.showToast("语音模型下载失败：${e.message ?: "网络错误"}")
            false
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
