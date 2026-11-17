package com.jobeen.ime.skin.model

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
    /**
     * 审计 H 项：压下/抬起分段时长（对端 scale 的 pressDuration /
     * releaseDuration）；null = 沿用 durationMs。抬起时长只在
     * autoReverse 时生效（非自动回弹时抬起不做缩放动画）。
     */
    val pressDurationMs: Int? = null,
    val releaseDurationMs: Int? = null,
    /** 抬起是否自动回弹到 1.0（对端 isAutoReverse）；缺省 true。 */
    val autoReverse: Boolean = true,
    /**
     * WO38 按压下沉（对端 KeyEffects.pressSink，0..8dp）：压下时
     * 键面整体下移，抬起回 0；0 = 不下沉（缺省，旧皮肤行为不变）。
     * 与 pressedScale 同一动画器、同一分段时长跑（见 KeyView 接线差距）。
     */
    val pressedSinkDp: Float = DEFAULT_PRESSED_SINK_DP,
) {
    companion object {
        const val DEFAULT_PRESSED_SCALE: Float = 0.96f
        const val MIN_PRESSED_SCALE: Float = 0.80f
        const val DEFAULT_DURATION_MS: Int = 120
        const val MIN_DURATION_MS: Int = 50
        const val MAX_DURATION_MS: Int = 300
        const val DEFAULT_PRESSED_SINK_DP: Float = 0f
        const val MAX_PRESSED_SINK_DP: Float = 8f
    }
}

enum class KeyAnimationEasing {
    LINEAR,
    EASE_OUT,
    EASE_IN_OUT,
    OVERSHOOT,
}
