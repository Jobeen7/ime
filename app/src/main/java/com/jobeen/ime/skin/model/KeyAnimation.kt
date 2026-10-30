package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyIds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按键动效（2026-10-29 item-1：有界子集）。
 *
 * 皮肤顶层 `keyAnimation`（全部键）与 `spaceAnimation`（仅空格键，
 * 出现即整体取代 keyAnimation 对空格键的规格，含 enabled=false
 * 的显式关闭）：
 * ```json
 * "keyAnimation": {"enabled": true, "pressedScale": 0.96,
 *   "durationMs": 120, "easing": "easeOut"}
 * ```
 * - 本轮只做按压缩放自动回弹（压下缩、抬起回 1.0）：零分配
 *   ViewPropertyAnimator、无逐帧位图、无布局抖动；帧序列／
 *   物理／变换／自定义缓动曲线只留 easing 名（线性／缓出／
 *   缓入缓出／回弹）作模型字段，不展开实现。
 * - 缺省 = 关闭（旧皮肤行为不变）；非法字段逐项回退默认值、
 *   记 issue，绝不拒包。
 * - 性能门控：内存降级（Degradation.ANIMATIONS_DISABLED）时
 *   装配期直接不写动效规格（见 KeyOverrideApplier），视图层
 *   无需再判档位。
 *
 * 纯解析，可直接单测。
 */
data class KeyAnimationSpec(
    val enabled: Boolean = false,
    /** 按压缩放（0.80..1.0）；缺省 0.96。 */
    val pressedScale: Float = DEFAULT_PRESSED_SCALE,
    /** 单程时长 ms（50..300）；缺省 120。 */
    val durationMs: Int = DEFAULT_DURATION_MS,
    val easing: KeyAnimationEasing = KeyAnimationEasing.EASE_OUT,
) {
    companion object {
        const val DEFAULT_PRESSED_SCALE: Float = 0.96f
        const val MIN_PRESSED_SCALE: Float = 0.80f
        const val DEFAULT_DURATION_MS: Int = 120
        const val MIN_DURATION_MS: Int = 50
        const val MAX_DURATION_MS: Int = 300
    }
}

enum class KeyAnimationEasing {
    LINEAR,
    EASE_OUT,
    EASE_IN_OUT,
    OVERSHOOT,
}

/** 空格键稳定 ID 全集（各布局的空格键；见 KeyIds）。 */
val SPACE_KEY_IDS: Set<String> = setOf(
    KeyIds.Qwerty.KEY_SPACE,
    KeyIds.T9.T9_SPACE,
    KeyIds.T15.T15_SPACE,
    KeyIds.Number.NUM_SPACE,
)

object KeyAnimationParser {

    fun parse(node: JsonElement?, field: String): Pair<KeyAnimationSpec, List<SkinIssue>> {
        if (node == null) return KeyAnimationSpec() to emptyList()
        val obj = node as? JsonObject
        if (obj == null) {
            return KeyAnimationSpec() to listOf(
                SkinIssue(field, SkinIssue.Kind.FIELD_FALLBACK, "key_animation_not_object"),
            )
        }
        val issues = mutableListOf<SkinIssue>()
        fun fallback(sub: String, reason: String) {
            issues += SkinIssue("$field.$sub", SkinIssue.Kind.FIELD_FALLBACK, reason)
        }

        var enabled = false
        val enabledNode = obj["enabled"]
        if (enabledNode != null) {
            val primitive = enabledNode as? JsonPrimitive
            if (primitive != null && !primitive.isString) {
                try {
                    enabled = primitive.content.toBooleanStrict()
                } catch (e: IllegalArgumentException) {
                    fallback("enabled", "key_animation_enabled_invalid")
                }
            } else {
                fallback("enabled", "key_animation_enabled_invalid")
            }
        }

        var pressedScale = KeyAnimationSpec.DEFAULT_PRESSED_SCALE
        val scaleNode = obj["pressedScale"]
        if (scaleNode != null) {
            val primitive = scaleNode as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toFloatOrNull()
            } else {
                null
            }
            if (value == null || value < KeyAnimationSpec.MIN_PRESSED_SCALE || value > 1f) {
                fallback("pressedScale", "key_animation_scale_invalid")
            } else {
                pressedScale = value
            }
        }

        var durationMs = KeyAnimationSpec.DEFAULT_DURATION_MS
        val durationNode = obj["durationMs"]
        if (durationNode != null) {
            val primitive = durationNode as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toIntOrNull()
            } else {
                null
            }
            if (value == null || value < KeyAnimationSpec.MIN_DURATION_MS ||
                value > KeyAnimationSpec.MAX_DURATION_MS
            ) {
                fallback("durationMs", "key_animation_duration_invalid")
            } else {
                durationMs = value
            }
        }

        var easing = KeyAnimationEasing.EASE_OUT
        val easingNode = obj["easing"]
        if (easingNode != null) {
            val raw = (easingNode as? JsonPrimitive)?.takeIf { it.isString }?.content
            val parsed = when (raw?.lowercase()) {
                null -> null
                "linear" -> KeyAnimationEasing.LINEAR
                "easeout", "ease_out" -> KeyAnimationEasing.EASE_OUT
                "easeinout", "ease_in_out" -> KeyAnimationEasing.EASE_IN_OUT
                "overshoot" -> KeyAnimationEasing.OVERSHOOT
                else -> null
            }
            if (parsed == null) {
                fallback("easing", "key_animation_easing_invalid")
            } else {
                easing = parsed
            }
        }

        return KeyAnimationSpec(enabled, pressedScale, durationMs, easing) to issues
    }
}
