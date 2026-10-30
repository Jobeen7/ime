package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.pow

/**
 * 颜色派生（2026-10-27 并入项，6 基色 → 令牌模型）：
 *
 * 皮肤文件可用 `derivation` 段声明 6 个基础色，校验期展开为
 * 完整的 28 字段令牌表；显式 `styles` 逐字段优先于派生值。
 * 展开发生在校验期（冷路径），快照构建器与渲染层完全不动；
 * 派生展开后的令牌快照就是样式引用解析的目标（链路顺序：
 * 派生 → 快照 → 样式引用，见 KeyStyleResolver）。
 *
 * 6 基色语义（B01 云白为参照例：face 白、pressed 浅灰、
 * bed 近透明、ink 深灰、accent 金 #9C824A、hint 浅灰）：
 * - face：普通键面；pressed：按压键面；bed：键盘底板/面板底；
 * - ink：主文字；accent：强调键面与激活态；hint：副文字/序号。
 *
 * 派生映射（固定，不接受自定义公式——可预测、可测试）：
 * - 描边 = 键面加深（hairline 比键面深一档）；
 * - 特殊键面 = 键面微深（与普通键拉开半档）；
 * - 强调按压 = 强调色加深；强调文字 = 按强调色明暗取白/墨；
 * - 面板/候选底 = bed；分隔线 = bed 与 ink 的浅混合。
 */
object ColorDerivation {

    /** 6 个基础色（ARGB Int，与 styles 颜色同格式）。 */
    data class BaseColors(
        val face: Int,
        val pressed: Int,
        val bed: Int,
        val ink: Int,
        val accent: Int,
        val hint: Int,
    ) {
        companion object {
            /** derivation 段的 6 个字段名（与 6 基色一一对应）。 */
            val FIELD_NAMES = listOf("face", "pressed", "bed", "ink", "accent", "hint")
        }
    }

    /** 解析 derivation 段：6 色齐全且合法才返回；否则返回 null（调用方逐字段记 issue）。 */
    fun parse(derivation: JsonObject?): BaseColors? {
        if (derivation == null) return null
        val values = mutableMapOf<String, Int>()
        for (name in BaseColors.FIELD_NAMES) {
            val raw = (derivation[name] as? JsonPrimitive)?.contentOrNull
            val parsed = raw?.let { SkinJson.parseColor(it) } ?: return null
            values[name] = parsed
        }
        return BaseColors(
            face = values.getValue("face"),
            pressed = values.getValue("pressed"),
            bed = values.getValue("bed"),
            ink = values.getValue("ink"),
            accent = values.getValue("accent"),
            hint = values.getValue("hint"),
        )
    }

    /** 校验单个基色字段（供调用方逐字段记 issue 用）。 */
    fun parseField(derivation: JsonObject, name: String): Int? {
        val raw = (derivation[name] as? JsonPrimitive)?.contentOrNull
        return raw?.let { SkinJson.parseColor(it) }
    }

    /** 6 基色展开为完整令牌表（纯函数，可直接单测）。 */
    fun expand(base: BaseColors): Map<StyleField, Int> {
        val faceDark = darken(base.face, 0.88f)
        val accentDark = darken(base.accent, 0.85f)
        val accentText = if (luminance(base.accent) < 0.5f) 0xFFFFFFFF.toInt() else base.ink
        val specialFace = darken(base.face, 0.94f)
        return mapOf(
            StyleField.KEY_BACKGROUND to base.face,
            StyleField.KEY_PRESSED to base.pressed,
            StyleField.KEY_BORDER_STROKE to faceDark,
            StyleField.SPECIAL_KEY_BACKGROUND to specialFace,
            StyleField.SPECIAL_KEY_PRESSED to base.pressed,
            StyleField.SPECIAL_KEY_BORDER_STROKE to faceDark,
            StyleField.ACCENT_KEY_BACKGROUND to base.accent,
            StyleField.ACCENT_KEY_PRESSED to accentDark,
            StyleField.ACCENT_KEY_BORDER_STROKE to accentDark,
            StyleField.KEY_TEXT to base.ink,
            StyleField.SPECIAL_KEY_TEXT to base.ink,
            StyleField.ACCENT_KEY_TEXT to accentText,
            StyleField.ALT_TEXT to base.hint,
            StyleField.BACKGROUND to base.bed,
            StyleField.TOAST_BACKGROUND to specialFace,
            StyleField.TOAST_TEXT to base.ink,
            StyleField.PANEL_BACKGROUND to base.bed,
            StyleField.PANEL_TOOLBAR_TEXT to base.ink,
            StyleField.PANEL_TOOLBAR_ACTIVED to base.accent,
            StyleField.PANEL_TOOLBAR_ICON to base.ink,
            StyleField.PANEL_TOOLBAR_PRESSED to base.pressed,
            StyleField.PANEL_CANDIDATE_BACKGROUND to base.bed,
            StyleField.PANEL_CANDIDATE_TEXT to base.ink,
            StyleField.PANEL_CANDIDATE_INDEX to base.hint,
            StyleField.PANEL_CANDIDATE_DIVIDER to mix(base.bed, base.ink, 0.15f),
            StyleField.PINNER_BACKGROUND to base.face,
            StyleField.PINNER_TEXT_COLOR to base.ink,
            StyleField.PINNER_SECONDARY_TEXT_COLOR to base.hint,
        )
    }

    /** 加深（RGB 各通道乘 factor，alpha 不变）。 */
    fun darken(argb: Int, factor: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb ushr 16) and 0xFF) * factor
        val g = ((argb ushr 8) and 0xFF) * factor
        val b = (argb and 0xFF) * factor
        return (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    /** 两色线性混合（t=0 取 a，t=1 取 b；alpha 同步混合）。 */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** sRGB 相对亮度（0=黑，1=白；WCAG 系数）。 */
    fun luminance(argb: Int): Float {
        fun linear(c: Int): Float {
            val s = c / 255f
            return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = linear((argb ushr 16) and 0xFF)
        val g = linear((argb ushr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
