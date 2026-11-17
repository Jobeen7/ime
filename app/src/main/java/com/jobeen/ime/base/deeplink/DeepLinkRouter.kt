package com.jobeen.ime.base.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jobeen.ime.ui.MainActivity
import java.net.URLDecoder

/**
 * jime:// deep link 路由（WO52 4.2）：纯解析＋路由，可主机单测。
 *
 * scheme `jime://`，host `com.jobeen.ime`；MainActivity 为
 * singleTask，onCreate 与 onNewIntent 均走同一路由函数。
 *
 * 解析口径（与对端口径一致）：
 * - 非 ACTION_VIEW 直接丢弃；getDataString() 为空返回；
 *   URI 解析失败返回；scheme 大小写不敏感；
 * - path 去尾部 `/`、取首个 `/` 之后的子串；
 * - query 按 `&` 切、`=` 切键值，键 URLDecoder.decode(UTF-8)
 *   （`+` 先换成 `%2B`），值取 `=` 之后原文；空 query 得空 Map；
 * - 取 `action` 键供特例判断。
 *
 * 路由优先级：① keyboardSettings＋numericSymbols/t9Symbols →
 * 键盘设置页；② clipboard＋phrase → 常用语页；③ dr.a 别名表
 * 17 项；④ 回落 bx0 路由 id／目录页键直配。
 *
 * clipboard 落点（WO52 4.2 遗留项，已补接收侧）：`clipboard?id=<id>`
 * 的 id 参数由本路由透传（见 Target.params），MainActivity 接线后
 * 交 ClipboardActivity（EXTRA_CLIPBOARD_ID），ClipboardScreen
 * 按 id 打开历史列表并定位到该条（高亮＋滚动）；id 查不到时给
 * 明确提示、不崩溃；不带 id 只打开剪贴板页。
 * 另 typingPractice／keyboardPreview／appIcon／pro 已定不做，
 * 不导航。
 */
object DeepLinkRouter {

    const val SCHEME = "jime"
    const val HOST = "com.jobeen.ime"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    data class Target(val pageKey: String, val params: Map<String, String>)

    /**
     * dr.a 别名表（15 项）＋ bx0 路由 id 回落，值＝设置目录页键。
     * typingPractice／pro／keyboardPreview／appIcon 不在表内（不导航）。
     */
    private val aliasToPageKey: Map<String, String> = mapOf(
        // dr.a 别名
        "inputSchema" to "schema",
        "finder" to "files",
        "keyboardSettings" to "keyboard",
        "inputBehavior" to "keyboard",
        "feedback" to "keyboard",
        "fonts" to "appearance",
        "keyboardSkins" to "skin",
        "toolbar" to "toolbar",
        "symbolBar" to "symbol_bar",
        "phrase" to "phrase",
        "clipboard" to "clipboard",
        "rime" to "rime",
        "backup" to "backup",
        "remoteStorage" to "userdict",
        // bx0 回落 id（别名表未覆盖的）
        "skins" to "skin",
        "keyboardMenu" to "keyboardMenu",
        "files" to "files",
        "ai" to "ai",
    )

    /** 目录页键直配（21 个一级入口）。 */
    private val catalogPageKeys: Set<String> = setOf(
        "schema", "scheme_update", "rime", "candidates", "skin", "scripts",
        "keyboard", "keyboardMenu", "toolbar", "symbol_bar",
        "skin_editor", "appearance", "clipboard", "phrase",
        "ai", "voice", "stats", "userdict", "backup", "files",
        "about",
    )

    fun resolve(action: String?, dataString: String?): Target? {
        if (action != ACTION_VIEW) return null
        if (dataString.isNullOrEmpty()) return null
        val parsed = parseUri(dataString) ?: return null
        if (!parsed.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!parsed.host.equals(HOST, ignoreCase = true)) return null
        val routeId = parsed.path.trimEnd('/').substringAfter('/', "")
        if (routeId.isEmpty()) return null
        val params = parseQuery(parsed.query)
        val actionParam = params["action"]
        // 特例 1：键盘设置页的数字／九宫格符号入口 → 键盘设置页。
        if (routeId == "keyboardSettings" &&
            (actionParam == "numericSymbols" || actionParam == "t9Symbols")
        ) {
            return Target("keyboard", params)
        }
        // 特例 2：剪贴板页的常用语动作 → 常用语页。
        if (routeId == "clipboard" && actionParam == "phrase") {
            return Target("phrase", params)
        }
        val pageKey = aliasToPageKey[routeId]
            ?: if (routeId in catalogPageKeys) routeId else return null
        return Target(pageKey, params)
    }

    private data class ParsedUri(val scheme: String, val host: String, val path: String, val query: String)

    /** 极简 URI 解析（不用 android.net.Uri，以便 JVM 单测）。 */
    private fun parseUri(dataString: String): ParsedUri? {
        val schemeEnd = dataString.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = dataString.substring(0, schemeEnd)
        if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) return null
        val rest = dataString.substring(schemeEnd + 3)
        val queryStart = rest.indexOf('?')
        val beforeQuery = if (queryStart < 0) rest else rest.substring(0, queryStart)
        val query = if (queryStart < 0) "" else rest.substring(queryStart + 1)
        val slash = beforeQuery.indexOf('/')
        val host = if (slash < 0) beforeQuery else beforeQuery.substring(0, slash)
        if (host.isEmpty()) return null
        val path = if (slash < 0) "" else beforeQuery.substring(slash)
        return ParsedUri(scheme, host, path, query)
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            // 键与值同口径解码（2026-11-16 N9：旧实现只解键，值里的
            // %XX／+ 原样进参数，含中文或空格的 query 值会错）。
            // `+` 先换 %2B：值里的 `+` 是字面加号，不是空格。
            val key = decodeQueryComponent(rawKey)
            val value = decodeQueryComponent(rawValue)
            map[key] = value
        }
        return map
    }

    private fun decodeQueryComponent(raw: String): String = runCatching {
        URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrDefault(raw)
}

/**
 * 应用内打开 jime:// 链接的统一入口：只接受本应用 deep link，
 * 其余返回 false（不启动任何页）。
 */
object JimeDeepLinks {
    fun open(context: Context, uriString: String): Boolean {
        val target = DeepLinkRouter.resolve(
            DeepLinkRouter.ACTION_VIEW,
            uriString,
        ) ?: return false
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uriString)
        }
        // target 已验过有效；MainActivity 的 onCreate／onNewIntent
        // 会再次路由，这里只负责把链接送进去。
        context.startActivity(intent)
        return true
    }
}
