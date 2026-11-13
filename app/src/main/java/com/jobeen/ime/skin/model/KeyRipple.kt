package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WO38 按压涟漪参数（对端 KeyEffects.ripple / rippleColor 口径）：
 * 皮肤顶层 `keyRipple` 对象。
 *
 * ```json
 * "keyRipple": {"color": "#B4B4E6FF", "durationMs": 650}
 * ```
 * - 只参数化既有 `KeyboardRippleView`（水波圆环）：颜色与单程时长；
 *   是否显示仍由用户全局开关（KeyboardManager.RippleEffect，缺省关）决定，
 *   皮肤不替用户开涟漪。
 * - 缺省 = 现有硬编码值（色 `#B4B4E6FF`＝argb(180,180,230,255)，
 *   650ms）：无参数皮肤行为零变化。非法字段逐项回退默认值、记 issue，
 *   绝不拒包。
 *
 * 纯解析，可直接单测。
 */
data class KeyRippleSpec(
    /** 波纹环颜色（ARGB，含透明度）；缺省既有硬编码色。 */
    val color: Int = DEFAULT_COLOR,
    /** 单程时长 ms（100..2000）；缺省 650。 */
    val durationMs: Int = DEFAULT_DURATION_MS,
) {
    companion object {
        const val DEFAULT_COLOR: Int = 0xB4B4E6FF.toInt()
        const val DEFAULT_DURATION_MS: Int = 650
        const val MIN_DURATION_MS: Int = 100
        const val MAX_DURATION_MS: Int = 2000
    }
}

object KeyRippleParser {

    fun parse(node: JsonElement?): Pair<KeyRippleSpec, List<SkinIssue>> {
        if (node == null) return KeyRippleSpec() to emptyList()
        val obj = node as? JsonObject
        if (obj == null) {
            return KeyRippleSpec() to listOf(
                SkinIssue("keyRipple", SkinIssue.Kind.FIELD_FALLBACK, "key_ripple_not_object"),
            )
        }
        val issues = mutableListOf<SkinIssue>()
        fun fallback(field: String, reason: String) {
            issues += SkinIssue("keyRipple.$field", SkinIssue.Kind.FIELD_FALLBACK, reason)
        }

        var color = KeyRippleSpec.DEFAULT_COLOR
        val colorNode = obj["color"]
        if (colorNode != null) {
            val raw = (colorNode as? JsonPrimitive)?.takeIf { it.isString }?.content
            val parsed = raw?.let { SkinJson.parseColor(it) }
            if (parsed == null) {
                fallback("color", "key_ripple_color_invalid")
            } else {
                color = parsed
            }
        }

        var durationMs = KeyRippleSpec.DEFAULT_DURATION_MS
        val durationNode = obj["durationMs"]
        if (durationNode != null) {
            val primitive = durationNode as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toIntOrNull()
            } else {
                null
            }
            if (value == null || value < KeyRippleSpec.MIN_DURATION_MS ||
                value > KeyRippleSpec.MAX_DURATION_MS
            ) {
                fallback("durationMs", "key_ripple_duration_invalid")
            } else {
                durationMs = value
            }
        }

        return KeyRippleSpec(color, durationMs) to issues
    }
}
