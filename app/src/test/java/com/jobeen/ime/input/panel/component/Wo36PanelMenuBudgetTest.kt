// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（WO36 P-中1）：项内边距 4→2dp、项间距 4→3dp
 * 后，6 项菜单自然高 162.52dp，竖屏默认 24%（199.68dp）与横屏默认
 * 44%（168.96dp）都一页放得下、不滚动；字号 13sp、卡片边距 8dp、
 * 滚动区顶边距 4dp 全不动。预算口径与布局同源
 * （见 ConfirmOverlay.actionsMenuNaturalHeightDp）；可用高取
 * S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class Wo36PanelMenuBudgetTest {

    private val portraitAvailDp = 199.68f
    private val landscapeAvailDp = 168.96f

    @Test
    fun sixItems_naturalHeight() {
        // 6×（13×1.32714844＋2×2）＋5×3＋2×8＋4 ≈ 162.52dp。
        assertEquals(162.52f, ConfirmOverlay.actionsMenuNaturalHeightDp(6), 0.01f)
    }

    @Test
    fun sixItems_fitsPortraitDefault() {
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural <= portraitAvailDp)
        // 余量约 37.2dp：不是贴边过关。
        assertEquals(37.16f, portraitAvailDp - natural, 0.01f)
    }

    @Test
    fun sixItems_fitsLandscapeDefault() {
        // WO36 收口：横屏 44% 不再短 22.5dp，6 项一页放得下、余 6.4dp。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural <= landscapeAvailDp)
        assertEquals(6.44f, landscapeAvailDp - natural, 0.01f)
    }

    @Test
    fun fiveItems_naturalHeight() {
        // 5 项 ≈ 138.26dp。
        assertEquals(138.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) <= landscapeAvailDp)
    }

    @Test
    fun sevenItems_fitsPortraitNotLandscape() {
        // 7 项 ≈ 186.77dp：竖屏放得下（余 12.9dp），横屏仍短约 17.8dp、
        // 靠 showCard 钳制滚动保底（7 项菜单目前无生产调用方）。
        assertEquals(186.77f, ConfirmOverlay.actionsMenuNaturalHeightDp(7), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) <= portraitAvailDp)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > landscapeAvailDp)
        assertEquals(17.81f, ConfirmOverlay.actionsMenuNaturalHeightDp(7) - landscapeAvailDp, 0.01f)
    }

    @Test
    fun zeroAndOneItem_countsOnlyChrome() {
        // 0 项只剩卡片内边距＋滚动区顶边距；1 项无间距。
        assertEquals(20f, ConfirmOverlay.actionsMenuNaturalHeightDp(0), 0.001f)
        assertEquals(41.25f, ConfirmOverlay.actionsMenuNaturalHeightDp(1), 0.01f)
    }

    @Test
    fun geometryConstants_matchWo36Budget() {
        // 预算与布局同源的锚点：改一处常量即改预算，数值漂移会被
        // sixItems_naturalHeight 抓住；此处再钉死常量本身。
        assertEquals(2f, ConfirmOverlay.ACTION_ITEM_PADDING_V_DP, 0.001f)
        assertEquals(3f, ConfirmOverlay.ACTION_ITEM_GAP_DP, 0.001f)
        assertEquals(8f, ConfirmOverlay.CARD_PADDING_V_DP, 0.001f)
        assertEquals(4f, ConfirmOverlay.ACTIONS_SCROLL_TOP_MARGIN_DP, 0.001f)
        assertEquals(13f, ConfirmOverlay.TEXT_SIZE_SP, 0.001f)
    }
}
