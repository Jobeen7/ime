// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard.Companion.SideColumnLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号板侧栏列高分配（WO36 P-低2）：单项最小行高 18→16.5dp 后，
 * 竖屏默认 24% 恢复 7 行；其余四档行数与每项高与 WO34 一致；
 * 列高小于入口高时入口钳到列高、余高不为负。
 * 列高取 S24 Ultra 384×832dp 的 contentH×3/4：
 * 竖屏 24%→149.76dp，竖屏 24%＋子条→113.76dp，横屏 44%→126.72dp，
 * 横屏 30%→86.4dp，横屏 30%＋子条→50.4dp。字号倍率 1.0。
 * onMeasure／onLayout 的接线本身需真机验收。
 */
class Wo36PanelSideColumnTest {

    private fun layout(sidePanelHDp: Float, fontScale: Float = 1f): SideColumnLayout =
        SymbolKeyboard.layoutSideColumn(sidePanelHDp, fontScale)

    @Test
    fun portraitDefault_sevenRowsRestored() {
        // 列高 149.76dp：入口 32dp＋7 行×16.82dp（WO34 是 6 行×19.63dp）。
        val l = layout(149.76f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(7, l.visibleRows)
        assertEquals(16.82f, l.itemHDp, 0.01f)
    }

    @Test
    fun otherBudgetCases_unchangedFromWo34() {
        // 下限 16.5dp 只改动竖屏 24% 一档；其余四档行数与每项高不变。
        val cases = listOf(
            Triple(113.76f, 4, 20.44f),
            Triple(126.72f, 5, 18.94f),
            Triple(86.4f, 3, 18.13f),
            Triple(50.4f, 1, 18.4f),
        )
        for ((h, rows, itemH) in cases) {
            val l = layout(h)
            assertEquals(32f, l.customEntryHDp, 0.001f)
            assertEquals(rows, l.visibleRows)
            assertEquals(itemH, l.itemHDp, 0.01f)
        }
    }

    @Test
    fun allBudgetCases_itemHeightCoversInkFloor() {
        // 每项高恒 ≥ 16.5dp 墨水下限（15sp 最 tall 墨水 15.26dp＋呼吸量）。
        for (h in listOf(149.76f, 113.76f, 126.72f, 86.4f, 50.4f)) {
            val l = layout(h)
            assertTrue("列高 $h 每项高 ${l.itemHDp}", l.itemHDp >= 16.5f)
            assertTrue("列高 $h 行数 ${l.visibleRows}", l.visibleRows <= 7)
        }
    }

    @Test
    fun columnBelowEntry_entryClampedToColumnHeight() {
        // 列高 20dp＜入口 32dp：入口钳到 20dp、不溢出列外，余高 0、
        // 仍保 1 行×0dp（旧口径入口恒 32dp，量出的侧栏区高为负）。
        val l = layout(20f)
        assertEquals(20f, l.customEntryHDp, 0.001f)
        assertEquals(1, l.visibleRows)
        assertEquals(0f, l.itemHDp, 0.001f)
    }

    @Test
    fun columnExactlyEntry_noRemainder() {
        // 列高恰 32dp：入口足额 32dp，余高 0、仍保 1 行×0dp。
        val l = layout(32f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(1, l.visibleRows)
        assertEquals(0f, l.itemHDp, 0.001f)
    }

    @Test
    fun tinyColumn_entryStaysFixed32RowBelowMinHeight() {
        // 列高 40dp＞入口 32dp：钳制不生效，入口仍 32dp、剩余 8dp
        // 仍保 1 行×8dp——低于行高下限，是列高小于入口＋一行的
        // 极值情形，真机列高不可能这么小。
        val l = layout(40f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(1, l.visibleRows)
        assertEquals(8f, l.itemHDp, 0.001f)
    }

    @Test
    fun degenerateColumn_entryClampedToZero() {
        // 列高 10dp＜入口 32dp：入口钳到 10dp，余高 0、仍保 1 行×0dp。
        val l = layout(10f)
        assertEquals(10f, l.customEntryHDp, 0.001f)
        assertEquals(1, l.visibleRows)
        assertEquals(0f, l.itemHDp, 0.001f)
    }

    @Test
    fun negativeOrZeroColumn_neverNegativeHeight() {
        // 列高 ≤0：列高钳到 0，入口随之钳到 0、每项高 0，不量出负高。
        for (h in listOf(0f, -10f)) {
            val l = layout(h)
            assertEquals(0f, l.customEntryHDp, 0.001f)
            assertEquals(1, l.visibleRows)
            assertEquals(0f, l.itemHDp, 0.001f)
        }
    }

    @Test
    fun fontScale13_entryClampedBeforeRowsFold() {
        // 字号倍率 1.3、列高 40dp：入口 32×1.3＝41.6dp＞列高，钳到 40dp。
        val l = layout(40f, 1.3f)
        assertEquals(40f, l.customEntryHDp, 0.01f)
        assertEquals(1, l.visibleRows)
        assertEquals(0f, l.itemHDp, 0.001f)
    }

    @Test
    fun fontScale13_budgetCasesUnchanged() {
        // 字号倍率 1.3：入口 32×1.3＝41.6dp，竖屏仍 6 行、横屏 30% 剩 2 行。
        val portrait = layout(149.76f, 1.3f)
        assertEquals(41.6f, portrait.customEntryHDp, 0.01f)
        assertEquals(6, portrait.visibleRows)
        assertEquals(18.03f, portrait.itemHDp, 0.01f)
        val landscapeMin = layout(86.4f, 1.3f)
        assertEquals(2, landscapeMin.visibleRows)
        assertEquals(22.4f, landscapeMin.itemHDp, 0.01f)
    }
}
