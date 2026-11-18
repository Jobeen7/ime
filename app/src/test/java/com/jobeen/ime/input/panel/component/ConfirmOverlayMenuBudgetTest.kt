// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（原值口径，2026-11-18 改回 26353025 之前）：
 * 按钮纵向内边距 8dp、项间距 8dp、卡片纵向内边距 10dp、按钮横向
 * 内边距 12dp、字号 13sp、无最小宽。6 项自然高 263.52dp、4 项
 * 181.01dp。放得下时 showCard 拉满可用高、各项均分（一页放满
 * 选项）；放不下时靠 showCard 钳制在卡内滚动保底。
 * 预算口径与布局同源（见 ConfirmOverlay.actionsMenuNaturalHeightDp）；
 * 可用高取 S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class ConfirmOverlayMenuBudgetTest {

    private val portraitAvailDp = 199.68f
    private val landscapeAvailDp = 168.96f

    @Test
    fun sixItems_naturalHeight() {
        // 6×（13×1.32714844＋2×8）＋5×8＋2×10＋4 ≈ 263.52dp。
        assertEquals(263.52f, ConfirmOverlay.actionsMenuNaturalHeightDp(6), 0.01f)
    }

    @Test
    fun fourItems_fitsPortraitDefault() {
        // 常用语 4 项 ≈ 181.01dp：竖屏放得下、余约 18.7dp；放得下时
        // showCard 拉满可用高、各项均分。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(4)
        assertEquals(181.01f, natural, 0.01f)
        assertTrue(natural <= portraitAvailDp)
        assertEquals(18.67f, portraitAvailDp - natural, 0.01f)
    }

    @Test
    fun sixItems_exceedsBothDefaults() {
        // 剪贴板 6 项 ≈ 263.52dp：默认面板高竖屏短约 63.8dp、横屏短约
        // 94.6dp，放不下时靠 showCard 钳制滚动保底；面板更高时拉满。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural > portraitAvailDp)
        assertTrue(natural > landscapeAvailDp)
        assertEquals(63.84f, natural - portraitAvailDp, 0.01f)
        assertEquals(94.56f, natural - landscapeAvailDp, 0.01f)
    }

    @Test
    fun fiveItems_naturalHeight() {
        // 5 项 ≈ 222.26dp。
        assertEquals(222.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) > portraitAvailDp)
    }

    @Test
    fun sevenItems_exceedsBoth() {
        // 7 项 ≈ 304.77dp：竖屏短约 105.1dp、横屏短更多，靠钳制滚动保底。
        assertEquals(304.77f, ConfirmOverlay.actionsMenuNaturalHeightDp(7), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > portraitAvailDp)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > landscapeAvailDp)
        assertEquals(105.09f, ConfirmOverlay.actionsMenuNaturalHeightDp(7) - portraitAvailDp, 0.01f)
    }

    @Test
    fun zeroAndOneItem_countsOnlyChrome() {
        // 0 项只剩卡片内边距＋滚动区顶边距；1 项无间距。
        assertEquals(24f, ConfirmOverlay.actionsMenuNaturalHeightDp(0), 0.001f)
        assertEquals(57.25f, ConfirmOverlay.actionsMenuNaturalHeightDp(1), 0.01f)
    }

    @Test
    fun geometryConstants_matchOriginal() {
        // 预算与布局同源的锚点：改一处常量即改预算，数值漂移会被
        // sixItems_naturalHeight 抓住；此处再钉死常量本身（原值）。
        assertEquals(12f, ConfirmOverlay.ACTION_ITEM_PADDING_H_DP, 0.001f)
        assertEquals(8f, ConfirmOverlay.ACTION_ITEM_PADDING_V_DP, 0.001f)
        assertEquals(8f, ConfirmOverlay.ACTION_ITEM_GAP_DP, 0.001f)
        assertEquals(10f, ConfirmOverlay.CARD_PADDING_V_DP, 0.001f)
        assertEquals(4f, ConfirmOverlay.ACTIONS_SCROLL_TOP_MARGIN_DP, 0.001f)
        assertEquals(13f, ConfirmOverlay.TEXT_SIZE_SP, 0.001f)
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
