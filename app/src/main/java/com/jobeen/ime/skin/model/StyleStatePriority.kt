// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

/**
 * 样式状态优先级（对端架构 §5 全序，Jime 单一实现点）：
 *
 * 前景样式选取顺序（高 → 低）：
 * 1. 滑动样式（上划 / 下划，滑动暂显期间）
 * 2. 大写锁定样式（capsLocked）
 * 3. 大写样式（uppercased）
 * 4. 基础 foregroundStyle（调用方以 null 表示“未指定”，
 *    由调用方回落默认色）
 *
 * 点击动作选取顺序（高 → 低）：
 * 1. preeditStateAction（组字中）
 * 2. uppercasedStateAction（大写态）
 * 3. 普通 action
 *
 * 两态独立：preedit 只压过普通，不压过大写缺省——即组字中
 * 若无 preedit 动作，仍取大写动作（不跳过）；同理滑动暂显
 * 结束后应恢复到大写态色（而非默认色），见 BaseKeyboard
 * 的 clearTransientSwipeColor 接线。
 *
 * 纯函数；调用方把各自的状态枚举映射到本函数的布尔/方向
 * 参数（避免本模型模块依赖键盘视图枚举）。
 */
object StyleStatePriority {

    enum class SwipeDir { UP, DOWN, NONE }

    /**
     * 前景色选取：按上序返回首个非空；全空返回 null（调用方
     * 回落默认色）。isCapsLocked 与 isUppercased 独立：锁定态
     * 同时满足“大写”，但锁定色优先于大写色。
     */
    fun foregroundColor(
        swipeUpColor: Int?,
        swipeDownColor: Int?,
        capsLockedColor: Int?,
        uppercasedColor: Int?,
        swipeDir: SwipeDir,
        isCapsLocked: Boolean,
        isUppercased: Boolean,
    ): Int? {
        when (swipeDir) {
            SwipeDir.UP -> swipeUpColor?.let { return it }
            SwipeDir.DOWN -> swipeDownColor?.let { return it }
            SwipeDir.NONE -> Unit
        }
        if (isCapsLocked) capsLockedColor?.let { return it }
        if (isUppercased) uppercasedColor?.let { return it }
        return null
    }

    /** 动作选取：泛型版（调用方传入三态动作值；null = 该态未指定）。 */
    fun <T> action(
        preeditAction: T?,
        uppercasedAction: T?,
        baseAction: T,
        isComposing: Boolean,
        isUppercased: Boolean,
    ): T {
        if (isComposing) preeditAction?.let { return it }
        if (isUppercased) uppercasedAction?.let { return it }
        return baseAction
    }
}
