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
