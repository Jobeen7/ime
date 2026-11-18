// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（WO36 P-中1，2026-11-18 放大）：项内边距 2→4dp、
 * 项间距 3→5dp 后，6 项菜单自然高 196.52dp，竖屏默认 24%（199.68dp）
 * 一页放得下、余 3.16dp，横屏默认 44%（168.96dp）短 27.56dp、靠
 * showCard 钳制滚动保底；字号 13sp、卡片边距 8dp、滚动区顶边距 4dp、
 * 横向内边距 12dp、无最小宽全不动。预算口径与布局同源
 * （见 ConfirmOverlay.actionsMenuNaturalHeightDp）；可用高取
 * S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class Wo36PanelMenuBudgetTest {

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
        // 放大后横屏 44% 短 27.56dp，靠 showCard 钳制滚动保底。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural > landscapeAvailDp)
        assertEquals(27.56f, natural - landscapeAvailDp, 0.01f)
    }

    @Test
    fun fiveItems_naturalHeight() {
        // 5 项 ≈ 166.26dp，横屏余 2.70dp。
        assertEquals(166.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) <= landscapeAvailDp)
        assertEquals(2.70f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
    }

    @Test
    fun fourItems_naturalHeight() {
        // 4 项 ≈ 136.01dp（常用语菜单），横屏余 32.95dp。
        assertEquals(136.01f, ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(4) <= landscapeAvailDp)
        assertEquals(32.95f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
    }

    @Test
    fun sevenItems_exceedsBoth() {
        // 7 项 ≈ 226.77dp：竖屏短约 27.1dp、横屏短约 57.8dp，
        // 都靠 showCard 钳制滚动保底（7 项菜单目前无生产调用方）。
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
    fun geometryConstants_matchEnlargedBudget() {
        // 预算与布局同源的锚点：改一处常量即改预算，数值漂移会被
        // sixItems_naturalHeight 抓住；此处再钉死常量本身。
        assertEquals(4f, ConfirmOverlay.ACTION_ITEM_PADDING_V_DP, 0.001f)
        assertEquals(5f, ConfirmOverlay.ACTION_ITEM_GAP_DP, 0.001f)
        assertEquals(8f, ConfirmOverlay.CARD_PADDING_V_DP, 0.001f)
        assertEquals(4f, ConfirmOverlay.ACTIONS_SCROLL_TOP_MARGIN_DP, 0.001f)
        assertEquals(13f, ConfirmOverlay.TEXT_SIZE_SP, 0.001f)
    }
}
