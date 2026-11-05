// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 跨行列模型（.18 回归：九宫格切换键被回车盖住）：
 *
 * - 列号按每行各自的行内键数算：靠右键在每行都放最后，
 *   显式 spanCol 按该行行内键数钳制；
 * - 行装配顺序：九宫格第 4 行四键在前、回车占位格在最后，
 *   切换键与回车键无重叠。
 *
 * 测的是抽出来的纯函数（列号计算与行链装配）；ConstraintLayout
 * 真实装配与真机渲染不在 JVM 单测内，走真机验收。
 */
class SpanRowLayoutTest {

    private val delta = 1e-4f

    // ---- 列号计算 ----

    @Test
    fun alignEnd_withoutExplicitCol_goesLastInEachRow() {
        // 回车键：起始行 3 个行内键得 3，第 4 行 4 个行内键得 4
        assertEquals(3, BaseKeyboard.spacerCol(null, true, 3))
        assertEquals(4, BaseKeyboard.spacerCol(null, true, 4))
    }

    @Test
    fun alignStart_withoutExplicitCol_goesFirstInEachRow() {
        // 侧栏键（左对齐）：任何行都在行首
        assertEquals(0, BaseKeyboard.spacerCol(null, false, 4))
        assertEquals(0, BaseKeyboard.spacerCol(null, false, 3))
    }

    @Test
    fun explicitCol_clampedToRowInRowCount() {
        assertEquals(2, BaseKeyboard.spacerCol(2, true, 4))
        assertEquals(2, BaseKeyboard.spacerCol(2, false, 3))
        // 超出该行行内键数时钳制到行尾，不插进键列中间
        assertEquals(4, BaseKeyboard.spacerCol(7, true, 4))
        assertEquals(0, BaseKeyboard.spacerCol(-1, false, 4))
    }

    // ---- 九宫格第 4 行（回归核心） ----

    @Test
    fun t9Row4_fourKeysThenSpacer_switchKeyNotCovered() {
        // 第 4 行行内键：切换 0.15、句号 0.13、空格 0.41、中英切换 0.13；
        // 回车跨行键宽 0.18，靠右：第 4 行列号为 4（不是起始行的 3）。
        val returnCol = BaseKeyboard.spacerCol(null, true, 4)
        assertEquals(4, returnCol)
        val inRowWidths = listOf(0.15f, 0.13f, 0.41f, 0.13f)
        val spanScale = (1f - 0.18f) / inRowWidths.sum()
        val elements = BaseKeyboard.assembleRowElements(
            inRowWidths, listOf(returnCol), listOf(0.18f), spanScale,
        )
        // 顺序：四键在前、占位格最后
        assertEquals(
            listOf(false, false, false, false, true),
            elements.map { it.isSpacer },
        )
        assertEquals(0.13f, elements[3].width, delta)
        assertEquals(0.18f, elements[4].width, delta)
        // 位置：切换键（第 4 键）止于 0.82，占位格恰占 [0.82, 1.0]——
        // 与回车视图自身位置（起始行 bias=1 → 左缘 0.82）重合，
        // 不与任何行内键重叠；行总和恒为 1.0
        val starts = elements.runningFold(0f) { acc, e -> acc + e.width }
        assertEquals(0.69f, starts[3], delta)
        assertEquals(0.82f, starts[4], delta)
        assertEquals(1.0f, starts[5], delta)
        assertTrue(starts[4] >= 0.82f - delta)
    }

    @Test
    fun t9Row4_oldColWouldInsertSpacerBeforeSwitchKey() {
        // 反证：若沿用起始行列号 3，占位格会插在第 4 键（切换键）
        // 前面——正是 .18 的故障形态；修后列号必须为 4 而不是 3。
        val elements = BaseKeyboard.assembleRowElements(
            listOf(0.15f, 0.13f, 0.41f, 0.13f),
            listOf(3), listOf(0.18f), 1f,
        )
        assertEquals(
            listOf(false, false, false, true, false),
            elements.map { it.isSpacer },
        )
        assertEquals(4, BaseKeyboard.spacerCol(null, true, 4))
    }

    // ---- 九宫格第 3 行（回车起始行，行为不变） ----

    @Test
    fun t9Row3_sidePanelFirst_returnSpacerLast() {
        // 第 3 行行内键：7/8/9 各 0.22333；覆盖 span：侧栏（左，列 0，
        // 宽 0.15）与回车（右，列 3，宽 0.18）。
        val sideCol = BaseKeyboard.spacerCol(null, false, 3)
        val returnCol = BaseKeyboard.spacerCol(null, true, 3)
        assertEquals(0, sideCol)
        assertEquals(3, returnCol)
        val inRowWidths = listOf(0.22333f, 0.22333f, 0.22333f)
        val spanScale = (1f - 0.15f - 0.18f) / inRowWidths.sum()
        val elements = BaseKeyboard.assembleRowElements(
            inRowWidths, listOf(sideCol, returnCol), listOf(0.15f, 0.18f), spanScale,
        )
        assertEquals(
            listOf(true, false, false, false, true),
            elements.map { it.isSpacer },
        )
        val total = elements.sumOf { it.width.toDouble() }.toFloat()
        assertEquals(1.0f, total, delta)
    }
}
