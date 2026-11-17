package com.jobeen.ime.data.keyboard.theme


/**
 * 皮肤字族解析（2026-10-27 新增分流；2026-10-28 截图 6 项补渲染侧回退）。
 *
 * 来源：当前已验证快照的 [fontFamily]（校验期已白名单化，见
 * SYSTEM_FONT_FAMILIES）；无快照或未指定时返回 null，
 * 调用方保留原有字体（绝不把“未指定”变成一次字体切换）。
 *
 * 渲染侧回退（2026-10-29 外观轮起由 GlobalFonts 承接）：
 * 即使快照外的路径塞进未知族名，[resolveFamily] 也只放行白名单；
 * 未知族名回落系统默认、永不空白（日志口径见 GlobalFonts）。
 */
object SkinFonts {

    /** 当前皮肤字族名；快照恒为 null，恒返回 null（调用方保留原有字体）。 */
    fun familyName(): String? = null

    /**
     * 系统字族白名单（2026-10-27 新增分流）：Typeface.create 对
     * 未知族名静默回落默认字体、无法与“指定了默认字体”区分，故
     * 校验期只接受 Android 系统字族；其它名字一律按非法值回退并
     * 记 issue，不进快照。`default` 为显式默认别名（等价于未指定）。
     */
    val SYSTEM_FONT_FAMILIES: Set<String> = setOf(
        "sans-serif",
        "sans-serif-light",
        "sans-serif-thin",
        "sans-serif-condensed",
        "sans-serif-medium",
        "sans-serif-black",
        "sans-serif-smallcaps",
        "serif",
        "monospace",
        "serif-monospace",
    )

    /**
     * 字族名归一化（纯函数，可 JVM 单测）：null/空白/"default"
     * → null（调用方用系统默认字体）；白名单内（大小写不敏感）
     * → 小写规范名；白名单外 → null（未知名回落系统默认）。
     */
    fun resolveFamily(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().lowercase()
        if (normalized == "default") return null
        return normalized.takeIf { it in SYSTEM_FONT_FAMILIES }
    }

    /**
     * 内嵌字体名匹配（纯函数，可 JVM 单测）：raw 与某声明名
     * 大小写不敏感相等时返回声明原文，否则 null。系统字族名
     * 不走此路（见 [resolveFamily]）。
     */
    fun findFaceName(raw: String?, faceNames: List<String>): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return faceNames.firstOrNull { it.equals(trimmed, ignoreCase = true) }
    }
}
