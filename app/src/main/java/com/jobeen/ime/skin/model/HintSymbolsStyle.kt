// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

/**
 * 长按符号气泡尺寸（dp）：宽高必须成对出现才生效，
 * 单边缺失/非数值/非正数时整体回落按键可视区域尺寸
 * （与短按气泡 size 同口径：宽高须同时为数值才生效）。
 */
data class HintBubbleSize(
    val width: Float,
    val height: Float,
)

/**
 * 短按气泡样式（hintStyle 节点）：气泡本身是完整按键样式
 * 节点，本模型只取气泡几何与溢出两项——颜色/字形仍由按键
 * 本体变体决定（现有按键预览弹窗已按变体取色）：
 *
 * - size：可选，气泡尺寸；缺省 = 现有弹窗默认尺寸。
 * - checkIfOverflowsParentHeight：是否检测气泡超出父视图
 *   顶部并自动下移，缺省 true（与顶层 bubbleOverflowCheck
 *   同口径；逐键值优先于顶层值）。
 */
data class HintBubbleStyle(
    val size: HintBubbleSize? = null,
    val checkIfOverflowsParentHeight: Boolean = true,
)
