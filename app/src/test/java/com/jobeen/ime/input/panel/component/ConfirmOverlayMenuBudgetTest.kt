// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（P-中1）：6 项菜单在竖屏默认 24% 下一页放下
 * （2026-11-18 放大：项内边距 2→4dp、间距 3→5dp，6 项 188.52dp，
 * 竖屏余 11.16dp；横屏 44% 短 19.56dp，靠 showCard 钳制滚动保底；
 * 5 项 158.26dp 横屏余 10.70dp；4 项 128.01dp）。
 * （2026-11-18 贴边：卡片下内边距 8dp→0dp、右 margin 12dp→0dp，
 * 自然高比贴边前各少 8dp。）
 * 预算口径与布局同源（见 ConfirmOverlay.actionsMenuNaturalHeightDp）；
 * 可用高取 S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class ConfirmOverlayMenuBudgetTest {

    private val portraitAvailDp = 199.68f
    private val landscapeAvailDp = 168.96f

    @Test
    fun sixItems_naturalHeight() {
        // 6×（13×1.32714844＋2×4）＋5×5＋8＋0＋4 ≈ 188.52dp。
        assertEquals(188.52f, ConfirmOverlay.actionsMenuNaturalHeightDp(6), 0.01f)
    }

    @Test
    fun sixItems_fitsPortraitDefault() {
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural <= portraitAvailDp)
        // 余量约 11.2dp：贴边过关（默认面板 24%）。
        assertEquals(11.16f, portraitAvailDp - natural, 0.01f)
    }

    @Test
    fun sixItems_shortLandscape_scrollFallback() {
        // 放大后横屏 44% 放不下、短约 19.6dp，靠 showCard 钳制滚动保底。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural > landscapeAvailDp)
        assertEquals(19.56f, natural - landscapeAvailDp, 0.01f)
    }

    @Test
    fun fiveItems_fitsLandscapeToo() {
        // 5 项 ≈ 158.26dp，横屏也放得下、余约 10.7dp。
        assertEquals(158.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) <= landscapeAvailDp)
        assertEquals(10.70f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
    }

    @Test
    fun fourItems_naturalHeight() {
        // 4 项 ≈ 128.01dp（常用语菜单），竖屏余 71.67dp、横屏余 40.95dp。
        assertEquals(128.01f, ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(4) <= landscapeAvailDp)
        assertEquals(40.95f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
    }

    @Test
    fun sevenItems_exceedsPortraitToo() {
        // 7 项 ≈ 218.77dp：竖屏也放不下（短约 19.1dp）、横屏短约 49.8dp，
        // 都靠钳制滚动保底（7 项菜单目前无生产调用方）。
        assertEquals(218.77f, ConfirmOverlay.actionsMenuNaturalHeightDp(7), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > portraitAvailDp)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > landscapeAvailDp)
        assertEquals(19.09f, ConfirmOverlay.actionsMenuNaturalHeightDp(7) - portraitAvailDp, 0.01f)
        assertEquals(49.81f, ConfirmOverlay.actionsMenuNaturalHeightDp(7) - landscapeAvailDp, 0.01f)
    }

    @Test
    fun zeroAndOneItem_countsOnlyChrome() {
        // 0 项只剩卡片内边距（上 8dp＋下 0dp）＋滚动区顶边距；1 项无间距。
        assertEquals(12f, ConfirmOverlay.actionsMenuNaturalHeightDp(0), 0.001f)
        assertEquals(37.25f, ConfirmOverlay.actionsMenuNaturalHeightDp(1), 0.01f)
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
