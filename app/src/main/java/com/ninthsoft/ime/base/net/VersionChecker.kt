package com.ninthsoft.ime.base.net

import kotlinx.serialization.Serializable

@Serializable
data class AppVersionInfo(
    val lastestVersion: String = "",
    val website: String = "",
)

object VersionChecker {
    suspend fun check(currentVersion: String): String? {
        val remote = runCatching {
            HttpUtil.get<AppVersionInfo>("app/version")
        }.getOrNull()?.takeIf { it.lastestVersion.isNotBlank() } ?: return null
        return when {
            compareVersions(remote.lastestVersion.trim(), currentVersion) > 0 ->
                remote.website.trim().takeIf { it.isNotEmpty() }
            else -> ""
        }
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
