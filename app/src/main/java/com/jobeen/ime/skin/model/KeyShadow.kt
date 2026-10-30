package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 拟态双阴影（2026-10-29 item-1）：皮肤顶层 `keyShadow` 对象。
 *
 * ```json
 * "keyShadow": {
 *   "enabled": true,
 *   "lightColor": "#66FFFFFF",
 *   "darkColor": "#40000000",
 *   "offsetDp": 2
 * }
 * ```
 * - 左上亮影／右下暗影：两层半透明圆角矩形相对主形反向偏移，
 *   柔和来自半透明色＋小偏移，不做逐像素模糊（零热路径成本；
 *   真模糊须冷路径预计算位图，本轮不做，见 KeyDrawable）。
 * - 缺省 = 关闭（旧皮肤行为不变）；非法字段逐项回退默认值、
 *   记 issue，绝不拒包。偏移超键间距时渲染期钳制（见
 *   dualShadowKeyBackgroundDrawable），绝不崩。
 *
 * 纯解析，可直接单测。
 */
data class KeyShadowSpec(
    val enabled: Boolean = false,
    /** 左上亮影色（ARGB，含透明度）；缺省白 40%。 */
    val lightColor: Int = DEFAULT_LIGHT_COLOR,
    /** 右下暗影色（ARGB，含透明度）；缺省黑 25%。 */
    val darkColor: Int = DEFAULT_DARK_COLOR,
    /** 偏移 dp（0..8）；缺省 2。 */
    val offsetDp: Float = DEFAULT_OFFSET_DP,
) {
    companion object {
        const val DEFAULT_LIGHT_COLOR: Int = 0x66FFFFFF
        const val DEFAULT_DARK_COLOR: Int = 0x40000000
        const val DEFAULT_OFFSET_DP: Float = 2f
        const val MAX_OFFSET_DP: Float = 8f
    }
}

object KeyShadowParser {

    fun parse(node: JsonElement?): Pair<KeyShadowSpec, List<SkinIssue>> {
        if (node == null) return KeyShadowSpec() to emptyList()
        val obj = node as? JsonObject
        if (obj == null) {
            return KeyShadowSpec() to listOf(
                SkinIssue("keyShadow", SkinIssue.Kind.FIELD_FALLBACK, "key_shadow_not_object"),
            )
        }
        val issues = mutableListOf<SkinIssue>()
        fun fallback(field: String, reason: String) {
            issues += SkinIssue("keyShadow.$field", SkinIssue.Kind.FIELD_FALLBACK, reason)
        }

        var enabled = false
        val enabledNode = obj["enabled"]
        if (enabledNode != null) {
            val primitive = enabledNode as? JsonPrimitive
            if (primitive != null && !primitive.isString) {
                try {
                    enabled = primitive.content.toBooleanStrict()
                } catch (e: IllegalArgumentException) {
                    fallback("enabled", "key_shadow_enabled_invalid")
                }
            } else {
                fallback("enabled", "key_shadow_enabled_invalid")
            }
        }

        fun color(field: String, default: Int): Int {
            val raw = (obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (raw == null) {
                if (obj.containsKey(field)) fallback(field, "key_shadow_color_invalid")
                return default
            }
            return SkinJson.parseColor(raw) ?: run {
                fallback(field, "key_shadow_color_invalid")
                default
            }
        }
        val lightColor = color("lightColor", KeyShadowSpec.DEFAULT_LIGHT_COLOR)
        val darkColor = color("darkColor", KeyShadowSpec.DEFAULT_DARK_COLOR)

        var offsetDp = KeyShadowSpec.DEFAULT_OFFSET_DP
        val offsetNode = obj["offsetDp"]
        if (offsetNode != null) {
            val primitive = offsetNode as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toFloatOrNull()
            } else {
                null
            }
            if (value == null || value < 0f || value > KeyShadowSpec.MAX_OFFSET_DP) {
                fallback("offsetDp", "key_shadow_offset_invalid")
            } else {
                offsetDp = value
            }
        }

        return KeyShadowSpec(enabled, lightColor, darkColor, offsetDp) to issues
    }
}
