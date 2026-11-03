package com.jobeen.ime.input

/**
 * 打开链接白名单（审计 E：openURL）：只放行 http/https，且
 * host 段非空；其余一律在分发层丢弃并记日志，绝不原样起意图
 * （防 javascript:/intent: 等伪协议外泄）。另设长度上限：超长
 * 链接一律不放行（皮肤值本应很短；超长只可能是误配或注入，
 * 不值得为它起意图、弹确认框）。
 */
object OpenUrlPolicy {

    /** 链接长度上限（字符数）：常见浏览器/意图处理的安全线内取值。 */
    const val MAX_URL_LENGTH = 2048

    fun isAllowed(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val rest = url.substringAfter("://", "")
        return rest.isNotEmpty() && !rest.startsWith("/")
    }
}
