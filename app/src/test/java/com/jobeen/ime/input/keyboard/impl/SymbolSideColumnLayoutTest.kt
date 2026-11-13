// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard.Companion.SideColumnLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号板侧栏列高分配（P-低2）：自定义入口先拿足两行 12sp 的固定高，
 * 剩余高度按每项不低于 15sp 行盒（17.58dp）折算可见行数，上限 7 行。
 * 列高取 S24 Ultra 384×832dp 的 contentH×3/4：
 * 竖屏 24%→149.76dp，竖屏 24%＋子条→113.76dp，横屏 44%→126.72dp，
 * 横屏 30%→86.4dp，横屏 30%＋子条→50.4dp。字号倍率 1.0。
 * 旧口径（WO30 记录的“≈28.6dp／项”）是漏算 3/4 系数与自定义入口行：
 * 实际旧每项高＝sidePanelH/8，竖屏 24% 仅 18.7dp。
 * onMeasure／onLayout 的接线本身需真机验收。
 */
class SymbolSideColumnLayoutTest {

    private fun layout(sidePanelHDp: Float, fontScale: Float = 1f): SideColumnLayout =
        SymbolKeyboard.layoutSideColumn(sidePanelHDp, fontScale)

    @Test
    fun portraitDefault_sixRowsAndFixedEntry() {
        // 列高 149.76dp：入口 32dp＋6 行×19.63dp（旧口径 7 行×18.7dp，
        // 入口行只有 18.7dp、两行字被裁）。
        val l = layout(149.76f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(6, l.visibleRows)
        assertEquals(19.63f, l.itemHDp, 0.01f)
    }

    @Test
    fun portraitWithSubStrip_fourRows() {
        val l = layout(113.76f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(4, l.visibleRows)
        assertEquals(20.44f, l.itemHDp, 0.01f)
    }

    @Test
    fun landscapeDefault_fiveRows() {
        val l = layout(126.72f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(5, l.visibleRows)
        assertEquals(18.94f, l.itemHDp, 0.01f)
    }

    @Test
    fun landscapeMinHeight_threeRows() {
        // 横屏 30% 是旧口径最差档：7 行×10.8dp，墨水溢出相邻行。
        val l = layout(86.4f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(3, l.visibleRows)
        assertEquals(18.13f, l.itemHDp, 0.01f)
    }

    @Test
    fun landscapeMinHeightWithSubStrip_oneRow() {
        // 横屏 30%＋子条是旧口径极值档：7 行×6.3dp。
        val l = layout(50.4f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(1, l.visibleRows)
        assertEquals(18.4f, l.itemHDp, 0.01f)
    }

    @Test
    fun allBudgetCases_itemHeightCoversLineBox() {
        // 每项高恒 ≥ 15sp 行盒 17.58dp（取整口径 18dp 下限）。
        for (h in listOf(149.76f, 113.76f, 126.72f, 86.4f, 50.4f)) {
            val l = layout(h)
            assertTrue("列高 $h 每项高 ${l.itemHDp}", l.itemHDp >= 18f)
            assertTrue("列高 $h 行数 ${l.visibleRows}", l.visibleRows <= 7)
        }
    }

    @Test
    fun tinyColumn_entryStaysFixed32RowBelowMinHeight() {
        // 列高 40dp：入口固定 32dp（P-低2 定稿：固定 32dp，不按列高对折；
        // 旧测试按实现里的 min(32, 列高/2) 断言 20dp，已按复查规约改回），
        // 剩余 8dp 仍保 1 行×8dp——低于行盒下限，是列高小于入口＋一行的
        // 极值情形（见 layoutSideColumn KDoc），真机列高不可能这么小。
        val l = layout(40f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(1, l.visibleRows)
        assertEquals(8f, l.itemHDp, 0.001f)
    }

    @Test
    fun degenerateColumn_stillOneRow() {
        // 列高 10dp：入口固定 32dp，剩余高度钳到 0、仍保 1 行×0dp——
        // 极值下不崩，只是不够行盒（旧测试按 min(32, 列高/2) 断言入口
        // 5dp，已按复查规约“固定 32dp”改回）。
        val l = layout(10f)
        assertEquals(32f, l.customEntryHDp, 0.001f)
        assertEquals(1, l.visibleRows)
    }

    @Test
    fun negativeOrZeroColumn_neverNegativeHeight() {
        // 横条全开＋键盘拉到最小的极值：列高 ≤0 时列高钳到 0，入口仍为
        // 固定 32dp、每项高 0，不量出负高（旧口径 sidePanelH/8 同样为负，
        // 是既有极值问题；旧测试按 min(32, 列高/2) 断言入口 0dp，已按
        // 复查规约“固定 32dp”改回）。
        for (h in listOf(0f, -10f)) {
            val l = layout(h)
            assertEquals(32f, l.customEntryHDp, 0.001f)
            assertEquals(1, l.visibleRows)
            assertEquals(0f, l.itemHDp, 0.001f)
        }
    }

    @Test
    fun fontScale13_entryGrowsRowsFold() {
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
