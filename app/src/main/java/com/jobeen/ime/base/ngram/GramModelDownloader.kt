package com.jobeen.ime.base.ngram

import com.jobeen.ime.base.net.HttpUtil
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

@Serializable
data class GramModelManifest(
    val link: String = "",
    val sha256: String = "",
)

object GramModelDownloader {
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L
    // Grammar models are currently about 400 MB. Keep headroom for upstream
    // growth while refusing unbounded or obviously invalid responses.
    private const val MAX_MODEL_BYTES = 600L * 1024 * 1024
    private const val PART_SUFFIX = ".part"

    // 可信摘要来源：上游 RIME-LMDG 的 LTS 发布资产在 GitHub API 中登记了
    // 官方 sha256（digest 字段）。清单服务端未提供摘要时改查这里——
    // 上游换模型时新资产带新摘要，客户端现查现验，无需发版跟随。
    private const val GITHUB_DIGEST_API =
        "https://api.github.com/repos/amzxyz/RIME-LMDG/releases/tags/LTS"

    // 这些客户端只访问写死的 HTTPS 地址：禁止 https↔http 重定向，
    // 避免全局放开明文后被降级（明文仅供 WebDAV 在用户显式开启后使用）
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        language: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
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
        // 被杀将无模型可用（与 WanxiangUpdateManager 语法模型落盘同款规避）
        check(partial.renameTo(target)) { "Failed to finalize ngram model: ${target.absolutePath}" }
        target.isFile && target.length() > 0L
    }

    /**
     * 向 GitHub 查 [fileName] 在 LTS 发布中登记的官方 sha256。
     * 任何失败（网络不通、限流、资产结构变化）都返回空串，调用方
     * 退到 APK 内置钉死摘要，两者皆无则 fail-closed 拒绝下载。
     */
    private suspend fun fetchGithubSha256(fileName: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(GITHUB_DIGEST_API)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use ""
                    val assets = org.json.JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("assets") ?: return@use ""
                    var digest = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name") == fileName) {
                            digest = normalizeSha256(asset.optString("digest"))
                            break
                        }
                    }
                    digest
                }
            }.getOrElse {
                Timber.w(it, "Failed to fetch grammar model digest from GitHub")
                ""
            }
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
                        if (downloaded == 0L || (total >= 0L && downloaded != total)) {
                            Timber.w("Ngram model length mismatch: expected=%d actual=%d", total, downloaded)
                            return false
                        }
                        onProgress(downloaded, total)
                    }
                }
                if (expected.isNotEmpty()) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    target.inputStream().use { input ->
                        val digestBuffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(digestBuffer)
                            if (count < 0) break
                            digest.update(digestBuffer, 0, count)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
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
    "wanxiang-lts-zh-hans.gram" to
        "6169f35d48aa1018e6effd16cb6ae07f307964cffe5d8c79b97faf2164693bec",
)
