package com.jobeen.ime.base.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GithubRelease(
    val tag_name: String = "",
    val prerelease: Boolean = false,
    val html_url: String = "",
)

object VersionChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/Jobeen7/ime/releases?per_page=30"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 查询 GitHub 上的最新正式发行版（prerelease=false）。
     * @return 有新版时返回发行版页面地址；已是最新返回 ""；查询失败返回 null
     */
    suspend fun check(currentVersion: String): String? {
        val releases = runCatching {
            json.decodeFromString<List<GithubRelease>>(HttpUtil.getRawText(RELEASES_URL))
        }.getOrNull() ?: return null
        val latest = releases
            .filter { !it.prerelease }
            .maxWithOrNull { a, b ->
                compareVersions(normalizeVersion(a.tag_name), normalizeVersion(b.tag_name))
            }
            ?.takeIf { normalizeVersion(it.tag_name).isNotBlank() }
            ?: return null
        return when {
            compareVersions(normalizeVersion(latest.tag_name), currentVersion) > 0 ->
                latest.html_url.trim().takeIf { it.isNotEmpty() }
            else -> ""
        }
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
