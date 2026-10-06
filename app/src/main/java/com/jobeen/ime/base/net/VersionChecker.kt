package com.jobeen.ime.base.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GithubAsset(
    val name: String = "",
    val size: Long = 0,
    val browser_download_url: String = "",
    /** GitHub 资产摘要，格式 "sha256:<hex>"（旧发行版可能缺省为空） */
    val digest: String = "",
)

@Serializable
private data class GithubRelease(
    val tag_name: String = "",
    val prerelease: Boolean = false,
    val html_url: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

/** 检查更新结果 */
sealed interface UpdateCheckResult {
    /** 已是最新版本 */
    data object Latest : UpdateCheckResult

    /** 查询失败（网络/API 异常） */
    data object Failed : UpdateCheckResult

    /**
     * 有新正式版。[apkUrl] 为空表示该发行版没有 APK 资产，
     * 调用方退回打开 [pageUrl] 的旧流程。
     */
    data class Available(
        val version: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
        /** 资产 SHA-256（hex）；GitHub 未提供时为空，此时回退到大小+签名校验 */
        val apkSha256: String = "",
    ) : UpdateCheckResult
}

object VersionChecker {
    // /releases/latest 直接返回最新正式版；用列表接口时预览版发得多会把正式版挤出第一页
    private const val LATEST_URL = "https://api.github.com/repos/Jobeen7/ime/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 查询 GitHub 上的最新正式发行版（prerelease=false）。
     * 有新版时返回新版号与 APK 直链，供 App 内下载安装，不再跳浏览器。
     */
    suspend fun check(currentVersion: String): UpdateCheckResult {
        val latest = runCatching {
            json.decodeFromString<GithubRelease>(HttpUtil.getRawText(LATEST_URL))
        }.getOrNull() ?: return UpdateCheckResult.Failed
        if (latest.prerelease) return UpdateCheckResult.Failed
        val version = normalizeVersion(latest.tag_name)
        if (version.isBlank()) return UpdateCheckResult.Failed
        if (compareVersions(version, currentVersion) <= 0) return UpdateCheckResult.Latest
        val apk = latest.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.browser_download_url.isNotBlank()
        }
        return UpdateCheckResult.Available(
            version = version,
            apkName = apk?.name.orEmpty(),
            apkUrl = apk?.browser_download_url.orEmpty(),
            apkSize = apk?.size ?: 0L,
            pageUrl = latest.html_url.trim(),
            apkSha256 = apk?.digest
                ?.substringAfter("sha256:", "")
                ?.trim()
                ?.takeIf { it.length == 64 }?.lowercase()
                .orEmpty(),
        )
    }

    /** "v1.0.11-Jime" -> "1.0.11" */
    private fun normalizeVersion(tag: String): String {
        var v = tag.trim()
        if (v.startsWith("v", ignoreCase = true)) v = v.drop(1)
        val suffix = "-Jime"
        if (v.endsWith(suffix, ignoreCase = true)) v = v.dropLast(suffix.length)
        return v.trim()
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }
}
