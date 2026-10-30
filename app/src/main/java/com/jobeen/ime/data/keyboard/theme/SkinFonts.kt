package com.jobeen.ime.data.keyboard.theme

import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

/**
 * 皮肤字族解析（2026-10-27 新增分流；2026-10-28 截图 6 项补渲染侧回退）。
 *
 * 来源：当前已验证快照的 [fontFamily]（校验期已白名单化，见
 * SkinValidator.SYSTEM_FONT_FAMILIES）；无快照或未指定时返回 null，
 * 调用方保留原有字体（绝不把“未指定”变成一次字体切换）。
 *
 * 渲染侧回退（2026-10-29 外观轮起由 GlobalFonts 承接）：
 * 即使快照外的路径塞进未知族名，[resolveFamily] 也只放行白名单；
 * 未知族名回落系统默认、永不空白（日志口径见 GlobalFonts）。
 */
object SkinFonts {

    /** 当前皮肤字族名；无皮肤/未指定时为 null。 */
    fun familyName(): String? = SkinSnapshotProvider.peek()?.fontFamily

    /**
     * 字族名归一化（纯函数，可 JVM 单测）：null/空白/"default"
     * → null（调用方用系统默认字体）；白名单内（大小写不敏感）
     * → 小写规范名；白名单外 → null（未知名回落系统默认）。
     */
    fun resolveFamily(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().lowercase()
        if (normalized == "default") return null
        return normalized.takeIf { it in SkinValidator.SYSTEM_FONT_FAMILIES }
    }
}
