// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（P-中1）：6 项菜单在竖屏默认 24% 下一页放下
 * （2026-11-18 放大：项内边距 2→4dp、间距 3→5dp，6 项 196.52dp，
 * 竖屏余 3.16dp；横屏 44% 短 27.56dp，靠 showCard 钳制滚动保底；
 * 5 项 166.26dp 横屏余 2.70dp；4 项 136.01dp）。
 * 预算口径与布局同源（见 ConfirmOverlay.actionsMenuNaturalHeightDp）；
 * 可用高取 S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class ConfirmOverlayMenuBudgetTest {

    private val portraitAvailDp = 199.68f
    private val landscapeAvailDp = 168.96f

    @Test
    fun sixItems_naturalHeight() {
        // 6×（13×1.32714844＋2×4）＋5×5＋2×8＋4 ≈ 196.52dp。
        assertEquals(196.52f, ConfirmOverlay.actionsMenuNaturalHeightDp(6), 0.01f)
    }

    @Test
    fun sixItems_fitsPortraitDefault() {
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural <= portraitAvailDp)
        // 余量约 3.2dp：贴边过关（默认面板 24%）。
        assertEquals(3.16f, portraitAvailDp - natural, 0.01f)
    }

    @Test
    fun sixItems_shortLandscape_scrollFallback() {
        // 放大后横屏 44% 放不下、短约 27.6dp，靠 showCard 钳制滚动保底。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural > landscapeAvailDp)
        assertEquals(27.56f, natural - landscapeAvailDp, 0.01f)
    }

    @Test
    fun fiveItems_fitsLandscapeToo() {
        // 5 项 ≈ 166.26dp，横屏也放得下、余约 2.7dp。
        assertEquals(166.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) <= landscapeAvailDp)
        assertEquals(2.70f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
    }

    @Test
    fun fourItems_naturalHeight() {
        // 4 项 ≈ 136.01dp（常用语菜单），竖屏余 63.67dp、横屏余 32.95dp。
        assertEquals(136.01f, ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(4) <= landscapeAvailDp)
        assertEquals(32.95f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
    }

    @Test
    fun sevenItems_exceedsPortraitToo() {
        // 7 项 ≈ 226.77dp：竖屏也放不下（短约 27.1dp）、横屏短约 57.8dp，
        // 都靠钳制滚动保底（7 项菜单目前无生产调用方）。
        assertEquals(226.77f, ConfirmOverlay.actionsMenuNaturalHeightDp(7), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > portraitAvailDp)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > landscapeAvailDp)
        assertEquals(27.09f, ConfirmOverlay.actionsMenuNaturalHeightDp(7) - portraitAvailDp, 0.01f)
        assertEquals(57.81f, ConfirmOverlay.actionsMenuNaturalHeightDp(7) - landscapeAvailDp, 0.01f)
    }

    @Test
    fun zeroAndOneItem_countsOnlyChrome() {
        // 0 项只剩卡片内边距＋滚动区顶边距；1 项无间距。
        assertEquals(20f, ConfirmOverlay.actionsMenuNaturalHeightDp(0), 0.001f)
        assertEquals(45.25f, ConfirmOverlay.actionsMenuNaturalHeightDp(1), 0.01f)
    }

    @Test
    fun oldGeometry_wouldNotFitPortrait() {
        // 回归锚点：旧口径（内边距 5dp、间距 6dp）6 项 ≈ 213.5dp，
        // 竖屏 24% 短约 14dp——正是本修复要消掉的缺口。
        val oldItemH = 13f * ConfirmOverlay.ROBOTO_LINE_HEIGHT_WITH_PADDING + 2 * 5f
        val oldNatural = 6 * oldItemH + 5 * 6f + 2 * 8f + 4f
        assertEquals(213.52f, oldNatural, 0.01f)
        assertTrue(oldNatural > portraitAvailDp)
    }
}
