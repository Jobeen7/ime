// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（WO36 P-中1，2026-11-18 放大）：项内边距 2→4dp、
 * 项间距 3→5dp 后，6 项菜单自然高 188.52dp，竖屏默认 24%（199.68dp）
 * 一页放得下、余 11.16dp，横屏默认 44%（168.96dp）短 19.56dp、靠
 * showCard 钳制滚动保底；字号 13sp、卡片上边距 8dp、下边距 0dp
 * （2026-11-18 贴边）、滚动区顶边距 4dp、横向内边距 8dp
 * （2026-11-18 由 12dp 改窄）、右 margin 0dp（2026-11-18 由 12dp
 * 改贴边）、无最小宽。预算口径与布局同源
 * （见 ConfirmOverlay.actionsMenuNaturalHeightDp）；可用高取
 * S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class Wo36PanelMenuBudgetTest {

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
        // 放大后横屏 44% 短 19.56dp，靠 showCard 钳制滚动保底。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural > landscapeAvailDp)
        assertEquals(19.56f, natural - landscapeAvailDp, 0.01f)
    }

    @Test
    fun fiveItems_naturalHeight() {
        // 5 项 ≈ 158.26dp，横屏余 10.70dp。
        assertEquals(158.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) <= landscapeAvailDp)
        assertEquals(10.70f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
    }

    @Test
    fun fourItems_naturalHeight() {
        // 4 项 ≈ 128.01dp（常用语菜单），横屏余 40.95dp。
        assertEquals(128.01f, ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(4) <= landscapeAvailDp)
        assertEquals(40.95f, landscapeAvailDp - ConfirmOverlay.actionsMenuNaturalHeightDp(4), 0.01f)
    }

    @Test
    fun sevenItems_exceedsBoth() {
        // 7 项 ≈ 218.77dp：竖屏短约 19.1dp、横屏短约 49.8dp，
        // 都靠 showCard 钳制滚动保底（7 项菜单目前无生产调用方）。
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
    fun geometryConstants_matchEnlargedBudget() {
        // 预算与布局同源的锚点：改一处常量即改预算，数值漂移会被
        // sixItems_naturalHeight 抓住；此处再钉死常量本身。
        // 横向内边距 2026-11-18 由 12dp 改窄为 8dp（文字两边留白太宽）。
        // 卡片下内边距 2026-11-18 由 8dp 改为 0dp（贴边），上内边距保持 8dp。
        assertEquals(4f, ConfirmOverlay.ACTION_ITEM_PADDING_V_DP, 0.001f)
        assertEquals(5f, ConfirmOverlay.ACTION_ITEM_GAP_DP, 0.001f)
        assertEquals(8f, ConfirmOverlay.ACTION_ITEM_PADDING_H_DP, 0.001f)
        assertEquals(8f, ConfirmOverlay.CARD_PADDING_H_DP, 0.001f)
        assertEquals(8f, ConfirmOverlay.CARD_PADDING_TOP_DP, 0.001f)
        assertEquals(0f, ConfirmOverlay.CARD_PADDING_BOTTOM_DP, 0.001f)
        assertEquals(4f, ConfirmOverlay.ACTIONS_SCROLL_TOP_MARGIN_DP, 0.001f)
        assertEquals(13f, ConfirmOverlay.TEXT_SIZE_SP, 0.001f)
    }
}
