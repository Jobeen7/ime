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

    // 可信摘要来源：上游 RIME-LMDG 的 LTS 发布资产在 GitHub API 中登记了
    // 官方 sha256（digest 字段）。清单服务端未提供摘要时改查这里——
    // 上游换模型时新资产带新摘要，客户端现查现验，无需发版跟随。
    // 查询走 base.net 的共享实现（资产分页端点，见 fetchGithubSha256）。

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
        // 期望摘要只认 GitHub 登记值：清单与下载链接同出一台服务器，清单
        // 自带的 sha256 与链接同源、不能当信任锚（服务器被攻破时可配套
        // 伪造）。fail-closed：GitHub 查不到可信摘要时拒绝下载——语法
        // 模型约 400MB 且由原生代码解析，不能只凭长度放行。
        val expectedSha = pickExpectedSha256(
            manifest.sha256,
            fetchGithubSha256("$language.gram"),
            PINNED_GRAM_SHA256["$language.gram"] ?: "",
        )
        val manifestSha = normalizeSha256(manifest.sha256)
        if (manifestSha.isNotEmpty() && manifestSha != expectedSha) {
            Timber.w("Ngram manifest sha256 differs from GitHub registry, trusting GitHub")
        }
        if (expectedSha.isEmpty()) {
            Timber.w("Ngram model has no trustworthy SHA-256, refusing download: %s", language)
            HttpUtil.showToast("模型增强下载失败：无法获取官方校验值")
            return@withContext false
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
            val ok = partial.renameTo(target)
            // rename 后 fsync 目录，让这次替换在断电后依然成立
            if (ok) syncDirectory(target.parentFile)
            ok
        }
        if (!renamed) {
            Timber.w("Failed to finalize ngram model: %s", target.absolutePath)
            partial.delete()
            return@withContext false
        }
        target.isFile && target.length() > 0L
    }

    /**
     * 向 GitHub 查 [fileName] 在 LTS 发布中登记的官方 sha256。
     * 任何失败（网络不通、限流、资产结构变化）都返回空串，调用方
     * 退到 APK 内置钉死摘要，两者皆无则 fail-closed 拒绝下载。
     */
    private suspend fun fetchGithubSha256(fileName: String): String =
        withContext(Dispatchers.IO) {
            // 走资产分页端点（共享实现）：Release 对象内嵌的 assets
            // 数组并不完整，恰好被省略的资产会查不到摘要
            com.jobeen.ime.base.net.fetchGithubReleaseAssetSha256(
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
 * 期望摘要的选取：只认独立于清单服务器的信任锚——优先 GitHub 登记
 * 摘要，其次 APK 内置钉死摘要（同一模型文件的摘要随包发布，GitHub
 * 在国内网络不可达/被限流时 fail-closed 不应误伤正常安装）。清单
 * 摘要与下载链接同出一台服务器、不能当信任锚（参数保留仅因调用
 * 方还要拿它做日志比对）。两者皆无时返回空串，调用方拒绝下载。
 */
internal fun pickExpectedSha256(
    manifestSha256: String,
    githubSha256: String,
    pinnedSha256: String = "",
): String =
    normalizeSha256(githubSha256).ifEmpty { normalizeSha256(pinnedSha256) }

/**
 * APK 内置钉死的语法模型摘要（按文件名）：仅作 GitHub 查询失败时
 * 的兜底信任锚。上游换代新模型后，GitHub 可达时照常按登记摘要
 * 安装；GitHub 不可达且新文件与钉死值对不上时拒绝安装（宁可装
 * 不上，也不装一个无独立来源背书的 400MB 原生解析文件），等
 * 网络恢复即可。钉死值只在 GitHub 查询为空时参与选取。
 */
private val PINNED_GRAM_SHA256 = mapOf(
    // 2026-10-11 上游在 LTS Release 内原地替换了简体模型（published_at
    // 不变、摘要变），钉死值同步刷新为现行登记摘要
    "wanxiang-lts-zh-hans.gram" to
        "e317fb8587d189183cf31c046cb2410d8e11416dbd8a9c7d42d699c6e685e763",
)
