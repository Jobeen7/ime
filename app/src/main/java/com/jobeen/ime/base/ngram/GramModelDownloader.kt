package com.jobeen.ime.base.ngram

import com.jobeen.ime.base.net.HttpUtil
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

@Serializable
data class GramModelManifest(
    val link: String = "",
    val sha256: String = "",
)

/**
 * 语法模型最终文件（sharedDataDir 下的 <language>.gram）的跨下载器
 * 安装锁：本下载器与 WanxiangUpdateManager 写的是同一个目标文件，
 * 「rename 安装＋记录写入」临界区必须共用一把锁串行化，否则两路
 * 并发时后到者的记录会对应先到者的文件、更新事务的回滚还会删掉
 * 另一路刚装好的模型。用进程内 ReentrantLock 而非协程 Mutex：
 * WUM 的启动恢复入口是阻塞式普通函数、且全部临界区内都没有挂起
 * 点，普通锁在两种调用上下文里都成立；锁序固定为先 WUM 自己的
 * updateMutex、后本锁，本下载器只持本锁，不存在反序。
 */
internal val gramFileInstallLock = java.util.concurrent.locks.ReentrantLock()

/**
 * 两路大文件下载的进程级共用闸：语法模型单独下载（本文件）与方案
 * 更新（WanxiangUpdateManager，其流程内含约 400 MB 语法模型下载）
 * 写的是同一个目标文件。此前两路各持一把 AtomicBoolean、彼此
 * 不排斥，跨路互斥只靠设置页读 StateFlow 快照判断——快照翻转有
 * 帧级延迟，同帧双击能双双放行、各下一份约 400 MB。闸只有一把：
 * 任一路持有期间另一路入口即被拒（[DownloadResult.BUSY]），互斥
 * 不再依赖界面层时序。
 */
object DownloadGate {
    private val gate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 抢占下载闸：成功返回 true 并持有至 [release]；已有下载在跑返回 false */
    fun tryAcquire(): Boolean = gate.compareAndSet(false, true)

    fun release() {
        gate.set(false)
    }

    /** 当前是否有任一路下载持有闸（同步读，供界面守门即时判断） */
    val isBusy: Boolean get() = gate.get()
}

/**
 * 词库更新与方案导入事务的交叉互斥闸：更新两路（方案更新、
 * 语法模型单独下载）在入口抢占并持有至整个函数结束；方案导入
 * 事务（[com.jobeen.ime.ui.screen.SchemaSettingsScreen] 的 runImport）
 * 同样在事务入口抢占并持有至事务结束。任一方持有期间另一方
 * 入口即被拒（BUSY/直接返回），从而关掉「导入换位前部署标志
 * 尚未置位」的时间窗：只查 EngineDeployState 关不住导入的
 * 解压/换位阶段——那时部署还没开始，更新却已能把新 dicts
 * 换进随后被导入换位盖回或失败回滚删掉的目标目录。
 * 注意：更新持有本闸期间，换位前的部署复核只读部署状态、
 * 不读本闸（本闸此时正是更新自己持有，读它恒为真）。
 * 切换/管理页部署不持本闸：其部署全程置位 EngineDeployState，
 * 更新入口与换位前复核读部署状态即可覆盖。
 */
object UpdateImportGate {
    private val held = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 抢占交叉闸：成功返回 true 并持有至 [release]；已有对方持有返回 false */
    fun tryAcquire(): Boolean = held.compareAndSet(false, true)

    fun release() {
        held.set(false)
    }

    /** 当前是否有更新或导入事务持有交叉闸（同步读，供界面守门即时判断） */
    val isHeld: Boolean get() = held.get()
}

/**
 * 部署进行中是否应挡住词库更新（含超时孤儿仍在后台跑）：
 * 部署作业与更新换位互斥——部署进行中更新换位会撞上部署
 * 的编译读盘，部署失败回滚更会把刚换上的新 dicts 连同目标
 * 目录一起删掉、而偏好仍记新版本。纯函数口径供测试钉住；
 * 无参版读实时状态，供两路下载入口与换位前复核调用。
 */
internal fun deployBlocksUpdate(deployRunning: Boolean, orphanRunning: Boolean): Boolean =
    deployRunning || orphanRunning

internal fun deployBlocksUpdate(): Boolean =
    deployBlocksUpdate(
        com.jobeen.ime.data.manager.EngineDeployState.isRunning(),
        com.jobeen.ime.data.manager.EngineDeployState.isOrphanRunning(),
    )

/**
 * 大文件下载的三态结果：抢闸被拒（已有下载在跑，BUSY）与真正失败
 * （FAILED）严格区分——界面对 BUSY 不得置失败标志（旧实现被拒返回
 * false 被当失败，第一路还在下载、界面却报「下载失败」）。
 */
enum class DownloadResult { SUCCESS, FAILED, BUSY }

/** 一次下载的完成记录（进程级发布）：供页面销毁重进后读回最近一次结果 */
data class DownloadRecord(
    val result: DownloadResult,
    val finishedAtMs: Long,
)

object GramModelDownloader {
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L
    // Grammar models are currently about 400 MB. Keep headroom for upstream
    // growth while refusing unbounded or obviously invalid responses.
    private const val MAX_MODEL_BYTES = 600L * 1024 * 1024
    // 临时文件名必须与 WanxiangUpdateManager 的语法模型下载区分：两者
    // 目标文件同为 sharedDataDir 下的 <language>.gram（如
    // wanxiang-lts-zh-hans.gram），此前共用同一个「目标名 + .part」，
    // 两路下载同时触发时会互相 delete/rename 对方下到一半的文件，
    // 轻则下载失败、重则 rename 校验环节抛未捕获异常崩溃
    private const val PART_SUFFIX = ".gmdl.part"

    // 期望摘要来源：优先上游 RIME-LMDG 的 LTS 发布资产在 GitHub API
    // 中登记的官方 sha256（digest 字段，现查现验、无需发版跟随）；
    // GitHub 查不到时退回清单自带摘要；两者皆无则只走大小/长度
    // 校验放行，不再 fail-closed 拒绝下载（S2）。查询走 base.net
    // 的共享实现（只读 Release 内嵌摘要，见 fetchGithubSha256）。

    // 这些客户端只访问写死的 HTTPS 地址：禁止 https↔http 重定向，
    // 避免全局放开明文后被降级（明文仅供 WebDAV 在用户显式开启后使用）
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 语法模型下载的进程级运行状态、进度与结果：设置页订阅它们决定
     * 按钮与状态文案——页面局部状态随页面销毁丢失，下载由调用方以
     * 应用级作用域启动、页面销毁不中断，重进页面只看局部状态会
     * 误判空闲、重复启动同一下载，进行中的进度与已完成的结果也
     * 无从读回。
     * 状态由跨下载器共用的 [DownloadGate] 驱动：只有抢到闸的调用
     * 才置位/复位，被拒的重复调用（BUSY）不触碰它们，不会把正在
     * 跑的下载假复位、也不会覆盖 [lastResult]。
     */
    private val runningState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = runningState

    private val progressState = kotlinx.coroutines.flow.MutableStateFlow(0f)

    /** 下载进度 0f..1f（总量未知时为 0f）；非运行期保持初值或上次进度 */
    val progress: kotlinx.coroutines.flow.StateFlow<Float> = progressState

    private val lastResultState =
        kotlinx.coroutines.flow.MutableStateFlow<DownloadRecord?>(null)

    /** 最近一次下载的完成记录（成功/失败与完成时间）；未跑过为 null */
    val lastResult: kotlinx.coroutines.flow.StateFlow<DownloadRecord?> = lastResultState

    /**
     * 下载语法模型。入口第一行抢跨下载器共用的 [DownloadGate]（与
     * 方案更新共用一把，见该类注释），持有到整个函数结束：没有它时
     * 快速双击可同时启动两路下载，两路共用同一个 partial 文件会
     * 互相 delete/覆盖对方下到一半的内容。
     * @return 三态结果：被拒（已有任一路下载在跑）为
     * [DownloadResult.BUSY]，与失败严格区分
     */
    suspend fun download(
        language: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult {
        // 交叉守门（先于一切状态置位与文件写入）：部署进行中
        // （含超时孤儿）或导入事务进行中时一律 BUSY，不触碰
        // 运行状态与完成记录——被拒不得表现为失败。锁序固定：
        // 先交叉闸、后 DownloadGate，释放反序；交叉闸被拒时
        // 尚未抢 DownloadGate，无需释放它。
        if (deployBlocksUpdate()) {
            Timber.w("部署进行中，忽略本次语法模型下载调用")
            return DownloadResult.BUSY
        }
        if (!UpdateImportGate.tryAcquire()) {
            Timber.w("方案导入进行中，忽略本次语法模型下载调用")
            return DownloadResult.BUSY
        }
        try {
            if (!DownloadGate.tryAcquire()) {
                Timber.w("已有大文件下载在进行中，忽略本次语法模型下载调用")
                return DownloadResult.BUSY
            }
            runningState.value = true
            progressState.value = 0f
            try {
                val ok = downloadInternal(language) { downloaded, total ->
                    progressState.value =
                        if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
                    onProgress(downloaded, total)
                }
                val result = if (ok) DownloadResult.SUCCESS else DownloadResult.FAILED
                lastResultState.value = DownloadRecord(result, System.currentTimeMillis())
                return result
            } finally {
                // 先复位状态再放闸：闸未放时无人能抢入并置位，
                // 不会出现新调用的 true 被本处 false 覆盖
                runningState.value = false
                DownloadGate.release()
            }
        } finally {
            UpdateImportGate.release()
        }
    }

    private suspend fun downloadInternal(
        language: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val manifest = runCatching {
            GramModelApi.fetchManifest(language)
        }.onFailure {
            Timber.e(it, "Failed to fetch ngram model manifest")
            if (it !is kotlinx.coroutines.CancellationException &&
                it !is HttpUtil.ApiException
            ) {
                HttpUtil.showToast("模型增强下载失败：${it.message ?: "网络错误"}")
            }
        }.getOrNull()
            ?: return@withContext false
        val link = manifest.link.trim()
        if (link.isEmpty()) {
            Timber.w("Ngram model manifest has no download link: %s", language)
            return@withContext false
        }
        // 下载链接来自清单服务端：只接受 HTTPS。全局网络配置已放开
        // 明文（供 WebDAV 用户自填地址），不能依赖系统拦截；非 HTTPS
        // 与「无链接」同走 fail-closed 拒绝路径，不新增异常通道。
        if (!link.startsWith("https://")) {
            Timber.w("Ngram model manifest link is not https, refusing download: %s", language)
            return@withContext false
        }

        val target = File(DataManager.sharedDataDir, "$language.gram")
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        partial.delete()
        // 期望摘要优先 GitHub 登记值（与清单/下载链接不同源，可作
        // 校验锚）；GitHub 查不到时退回清单自带摘要；两者皆无时
        // expectedSha 为空，downloadFile 只走大小/长度校验放行——
        // 不再因拿不到摘要拒绝整个下载（S2：原三层信任锚＋钉死值
        // ＋fail-closed 已拆）
        val expectedSha = pickExpectedSha256(
            manifest.sha256,
            fetchGithubSha256("$language.gram"),
        )
        val manifestSha = normalizeSha256(manifest.sha256)
        if (manifestSha.isNotEmpty() && manifestSha != expectedSha) {
            Timber.w("Ngram manifest sha256 differs from GitHub registry, trusting GitHub")
        }
        if (!downloadFile(link, partial, expectedSha, onProgress)) {
            partial.delete()
            return@withContext false
        }

        // 不先删 target 再 rename：.part 与 target 同目录，rename 本身即
        // 原子覆盖，先删会打开「旧模型已删、新模型未就位」的空窗，中途
        // 被杀将无模型可用（与 WanxiangUpdateManager 语法模型落盘同款规避）。
        // rename 在跨下载器安装锁内执行（见 gramFileInstallLock）：与
        // WUM 的语法模型落盘/回滚互斥，避免互相覆盖与记录错位。
        // rename 失败按下载失败处理（记日志、清半成品、返回 false），
        // 不再抛未捕获异常穿透到调用方——旧实现 check() 在任何 try
        // 之外，失败即崩进程且调用方的下载中标志永不复位
        val renamed = gramFileInstallLock.withLock {
            // 换位前复核：下载中途部署启动时，入口守门已拦不住
            // 本次下载；此处是落盘前的最后一道关——部署进行中
            // （含超时孤儿）即放弃安装，半成品由调用方删掉，
            // 现用模型与记录保持旧的自洽状态。检查与 rename 间
            // 无挂起点；本闸（UpdateImportGate）此时由本次下载
            // 自己持有，故此处只读部署状态、不读本闸。
            if (deployBlocksUpdate()) {
                Timber.w("部署进行中，放弃本次语法模型安装")
                false
            } else {
                val ok = partial.renameTo(target)
                // rename 后 fsync 目录，让这次替换在断电后依然成立
                if (ok) syncDirectory(target.parentFile)
                ok
            }
        }
        if (!renamed) {
            Timber.w("Failed to finalize ngram model: %s", target.absolutePath)
            partial.delete()
            return@withContext false
        }
        target.isFile && target.length() > 0L
    }

    /**
     * 向 GitHub 查 [fileName] 在 LTS 发布中登记的官方 sha256，只读
     * Release 对象内嵌的摘要（共享实现，单次请求）。任何失败（网
     * 络不通、限流、内嵌缺项、结构变化）都返回空串，调用方退回
     * 清单自带摘要，不据此拒绝下载（S2）。
     */
    private suspend fun fetchGithubSha256(fileName: String): String =
        withContext(Dispatchers.IO) {
            com.jobeen.ime.base.net.fetchGithubReleaseEmbeddedDigest(
                client, "amzxyz", "RIME-LMDG", "LTS", fileName,
            )
        }

    /**
     * fsync 目录本体，让其中的 rename 在断电后依然成立（失败只记日志）。
     * 用 Os.open 以只读方式打开目录再 fsync；RandomAccessFile 打开
     * 目录在 Android 上必然失败，不能用它实现。
     */
    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        runCatching {
            val fd = android.system.Os.open(
                dir.absolutePath, android.system.OsConstants.O_RDONLY, 0,
            )
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        }.onFailure { Timber.w(it, "Directory fsync failed: %s", dir) }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Ngram model download failed: HTTP %d", response.code)
                    HttpUtil.showToast("模型增强下载失败：HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                val total = body.contentLength()
                val expected = expectedSha256.trim().lowercase()
                if (expected.isNotEmpty() && !expected.matches(Regex("[0-9a-f]{64}"))) {
                    Timber.w("Ngram model manifest contains an invalid SHA-256")
                    return false
                }
                if (total > MAX_MODEL_BYTES) {
                    Timber.w("Ngram model exceeds size limit: %d > %d", total, MAX_MODEL_BYTES)
                    return false
                }
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var reported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return false
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (downloaded + count > MAX_MODEL_BYTES) {
                                Timber.w("Ngram model exceeded size limit while streaming")
                                return false
                            }
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - reported >= REPORT_STEP ||
                                (total > 0 && downloaded >= total)
                            ) {
                                onProgress(downloaded, total)
                                reported = downloaded
                            }
                        }
                        // 写完先 fsync 文件本体再走校验与 rename：只靠
                        // close 落盘，断电时内容可能还没真正上盘
                        output.fd.sync()
                        if (downloaded == 0L || (total >= 0L && downloaded != total)) {
                            Timber.w("Ngram model length mismatch: expected=%d actual=%d", total, downloaded)
                            return false
                        }
                        onProgress(downloaded, total)
                    }
                }
                if (expected.isNotEmpty()) {
                    val actual = DigestUtil.sha256Hex(target)
                    if (actual != expected) {
                        // 校验不一致要让用户明确知道是「被拒绝」而不是
                        // 普通下载失败：文件可能已被替换/篡改
                        Timber.w("Ngram model SHA-256 mismatch")
                        HttpUtil.showToast("模型增强下载失败：文件校验不一致，已拒绝安装")
                        return false
                    }
                }
                true
            }
        }.onFailure {
            Timber.e(it, "Ngram model download failed")
            if (it !is kotlinx.coroutines.CancellationException) {
                HttpUtil.showToast("模型增强下载失败：${it.message ?: "网络错误"}")
            }
        }.getOrDefault(false)
    }

}

private object GramModelApi {
    suspend fun fetchManifest(language: String): GramModelManifest {
        val encoded = java.net.URLEncoder.encode(language, Charsets.UTF_8.name())
        return HttpUtil.get("model/grammar?language=$encoded")
    }
}

/** 归一化 sha256 文本（可带 "sha256:" 前缀）：合法时返回小写 64 位十六进制，否则空串 */
internal fun normalizeSha256(raw: String?): String {
    val hex = raw?.trim()?.lowercase()?.removePrefix("sha256:") ?: return ""
    return if (hex.matches(Regex("[0-9a-f]{64}"))) hex else ""
}

/**
 * 期望摘要的选取（S2 后口径）：优先 GitHub 登记摘要（独立于清单
 * 服务器，可作校验锚）；GitHub 查不到时退回清单自带摘要；两者
 * 皆无返回空串，调用方只走大小/长度校验放行，不再拒绝下载。
 * 原「钉死摘要兜底＋两者皆无即拒绝」的三层信任锚已拆除：钉死
 * 值必然随上游换包过期，fail-closed 又把网络抖动升级成装不上。
 */
internal fun pickExpectedSha256(
    manifestSha256: String,
    githubSha256: String,
): String =
    normalizeSha256(githubSha256).ifEmpty { normalizeSha256(manifestSha256) }
