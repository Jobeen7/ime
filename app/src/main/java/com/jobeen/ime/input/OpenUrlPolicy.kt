package com.jobeen.ime.input

/**
 * 打开链接白名单（审计 E：openURL）：只放行 http/https，且
 * host 段非空；其余一律在分发层丢弃并记日志，绝不原样起意图
 * （防 javascript:/intent: 等伪协议外泄）。
 */
object OpenUrlPolicy {

    fun isAllowed(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val rest = url.substringAfter("://", "")
        return rest.isNotEmpty() && !rest.startsWith("/")
    }
}
