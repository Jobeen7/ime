// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

/**
 * 滑动阈值解析（纯函数，可 JVM 单测）。
 *
 * 优先级：逐键覆写 > 皮肤级 > 全局设置 > 默认 36dp。
 * 超出有效区间的候选值视为未设置，顺延下一级；800dp 的
 * “等效禁用”槽（无侧向动作时的横向阈值、纵向禁用的
 * SwipeMoveCursor）不走本解析，调用方原样保留。
 */
object SwipeThresholds {

    /** 默认阈值：与旧写死行为一致。 */
    const val DEFAULT_DP = 36f

    /** 有效区间：小于 8dp 过敏、大于 160dp 近乎禁滑，均视为非法。 */
    const val MIN_DP = 8f
    const val MAX_DP = 160f

    fun isValid(dp: Float?): Boolean =
        dp != null && dp.isFinite() && dp in MIN_DP..MAX_DP

    fun resolve(perKeyDp: Float?, skinDp: Float?, settingDp: Float?): Float {
        for (candidate in listOf(perKeyDp, skinDp, settingDp)) {
            if (isValid(candidate)) return candidate!!
        }
        return DEFAULT_DP
    }
}

/**
 * 板级横滑拦截判定（纯函数，可 JVM 单测；I-M2）：只有横向位移达到
 * 翻页最小距离且横向占优时才拦截——未达翻页距离的横向小拖动不拦
 * 截，仍归子键点按（子键在键界内抬手即点按，见 CustomGestureView
 * 的 handleRelease）；竖向位移归网格滚动。旧口径以 touchSlop
 * （约 8dp）拦截、却以 48dp 才翻页，8～48dp 的拖动既被取消点按
 * 又翻不了页，手势无任何效果。
 */
internal fun shouldInterceptHorizontalSwipe(dxPx: Float, dyPx: Float, minDistancePx: Float): Boolean {
    val adx = kotlin.math.abs(dxPx)
    return adx > minDistancePx && adx > kotlin.math.abs(dyPx)
}
