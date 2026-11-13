// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（P-中1）：6 项菜单在竖屏默认 24% 下一页放得下，
 * 横屏默认 44% 仍短约 22.5dp（已知取舍，靠 showCard 钳制滚动保底）。
 * 预算口径与布局同源（见 ConfirmOverlay.actionsMenuNaturalHeightDp）；
 * 可用高取 S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class ConfirmOverlayMenuBudgetTest {

    private val portraitAvailDp = 199.68f
    private val landscapeAvailDp = 168.96f

    @Test
    fun sixItems_naturalHeight() {
        // 6×（13×1.32714844＋2×4）＋5×4＋2×8＋4 ≈ 191.52dp。
        assertEquals(191.52f, ConfirmOverlay.actionsMenuNaturalHeightDp(6), 0.01f)
    }

    @Test
    fun sixItems_fitsPortraitDefault() {
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural <= portraitAvailDp)
        // 余量约 8.2dp：不是贴边过关。
        assertEquals(8.16f, portraitAvailDp - natural, 0.01f)
    }

    @Test
    fun sixItems_stillShortLandscape() {
        // 横屏 44% 已知取舍：仍短约 22.5dp，末项靠钳制滚动保底。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural > landscapeAvailDp)
        assertEquals(22.56f, natural - landscapeAvailDp, 0.01f)
    }

    @Test
    fun fiveItems_fitsLandscapeToo() {
        // 5 项 ≈ 162.26dp，横屏也放得下：缺口只在第 6 项。
        assertEquals(162.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) <= landscapeAvailDp)
    }

    @Test
    fun sevenItems_doesNotFitPortrait() {
        // 7 项 ≈ 220.77dp：预算上限即 6 项，再多一项就得滚。
        assertEquals(220.77f, ConfirmOverlay.actionsMenuNaturalHeightDp(7), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(7) > portraitAvailDp)
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
