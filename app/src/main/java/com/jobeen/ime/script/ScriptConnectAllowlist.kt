// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

/**
 * 脚本联网白名单（纯函数，无 Android 依赖，可主机单测）：
 *
 * 声明：在脚本源码头部（第一个非注释、非空行之前）的行注释里写，
 * 每行一个域名：
 * ```
 * // @connect api.example.com
 * // @connect *.example.com
 * ```
 * - 指令大小写不敏感；域名小写化、去尾点、去端口；
 * - `*.example.com` 只匹配子域名（含多级，如 a.b.example.com），
 *   不匹配 apex 本身；裸 `*` 不是合法声明，直接忽略；
 * - 头部之后（第一行代码之后）的 `@connect` 不算——声明只在头部生效，
 *   脚本执行中途不能给自己加域名；
 * - 未声明任何域名时白名单为空，$http 一律拒绝。
 *
 * 代理策略（[ScriptHttpProxyPolicy]）：$http 的 URL 须为 http/https
 * 且 host 在白名单内才放行；其余一律拒绝，拒绝以 rejected 完成投递
 * 给脚本（不是抛错，脚本的 `__jimeHttpResult` 收到错误文本）。
 * 隔离进程内不直接联网：联网只发生在主进程的代理里，代理只转发
 * 脚本自己给的请求头，不附加任何应用凭据——密钥不进隔离进程，
 * 也不由代理代发。
 */
object ScriptConnectAllowlist {

    /** `@connect` 行注释：行首空白＋`//`＋`@connect`＋一个域名。 */
    private val CONNECT_LINE = Regex("""^\s*//\s*@connect\s+(\S+)\s*$""", RegexOption.IGNORE_CASE)

    /** 解析脚本头部的 `@connect` 声明，返回规范化后的域名集合。 */
    fun parse(source: String): Set<String> {
        val hosts = mutableSetOf<String>()
        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = CONNECT_LINE.matchEntire(line)
            if (match != null) {
                normalize(match.groupValues[1])?.let { hosts.add(it) }
                continue
            }
            // 非 @connect 的行注释仍是头部的一部分，跳过继续扫；
            // 第一行代码出现即头部结束，其后的 @connect 不算。
            if (line.startsWith("//")) continue
            break
        }
        return hosts
    }

    /**
     * 规范化一个声明域名：小写、去尾点、去端口；通配符只保留 `*.` 前缀。
     * 裸 `*`、空串、含空白或 `/` 的声明返回 null（非法声明直接丢弃）。
     */
    fun normalize(raw: String): String? {
        var host = raw.trim().lowercase()
        if (host.isEmpty() || host == "*" || host.contains(' ') || host.contains('/')) return null
        if (host.endsWith(".")) host = host.dropLast(1)
        if (host.isEmpty()) return null
        // 去端口（`example.com:8443` → `example.com`）；IPv6 `[::1]:8080` 去括号端口。
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end == -1) return null
            host = host.substring(0, end + 1)
        } else {
            val colon = host.indexOf(':')
            if (colon != -1) host = host.substring(0, colon)
        }
        if (host.isEmpty() || host == "*") return null
        return host
    }

    /** host 是否在白名单内：精确匹配，或 `*.domain` 匹配任意级子域名。 */
    fun isHostAllowed(host: String?, allowlist: Set<String>): Boolean {
        val normalized = host?.trim()?.lowercase()?.removeSuffix(".") ?: return false
        if (normalized.isEmpty()) return false
        if (allowlist.contains(normalized)) return true
        for (entry in allowlist) {
            if (entry.startsWith("*.")) {
                val suffix = entry.substring(1) // ".example.com"
                if (normalized.endsWith(suffix) && normalized.length > suffix.length) return true
            }
        }
        return false
    }

    /** 从 URL 里取 host（java.net.URI 纯 JVM 解析；非法返回 null）。 */
    fun hostOf(url: String): String? = try {
        java.net.URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }
}

/** $http 代理放行判定（纯函数）。 */
sealed interface ProxyDecision {
    /** 放行：URL 合法且 host 在白名单内。 */
    data object Allowed : ProxyDecision

    /** 拒绝：[reason] 为给脚本的错误文本（不含源码、不含密钥）。 */
    data class Denied(val reason: String) : ProxyDecision
}

/** $http 代理策略：只按白名单放行，不碰请求体、不附加凭据。 */
object ScriptHttpProxyPolicy {
    /** 检查一次 $http 请求：scheme 须为 http/https，host 须在白名单内。 */
    fun check(url: String, allowlist: Set<String>): ProxyDecision {
        val scheme = try {
            java.net.URI(url).scheme?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return ProxyDecision.Denied("Invalid HTTP URL")
        if (scheme != "http" && scheme != "https") {
            return ProxyDecision.Denied("Invalid HTTP URL")
        }
        val host = ScriptConnectAllowlist.hostOf(url)
            ?: return ProxyDecision.Denied("Invalid HTTP URL")
        return if (ScriptConnectAllowlist.isHostAllowed(host, allowlist)) {
            ProxyDecision.Allowed
        } else {
            ProxyDecision.Denied("host not declared in @connect: $host")
        }
    }
}
