// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作菜单高度预算（P-中1）：6 项菜单在竖屏默认 24% 与横屏默认 44%
 * 下都一页放下（WO36：项内边距 4→2dp、间距 4→3dp，6 项 162.52dp，
 * 竖屏余 37.16dp、横屏余 6.44dp；showCard 钳制保留，作 7 项以上／
 * 大字号的滚动保底）。
 * 预算口径与布局同源（见 ConfirmOverlay.actionsMenuNaturalHeightDp）；
 * 可用高取 S24 Ultra 384×832dp：竖屏 24%＝199.68dp，横屏 44%＝168.96dp。
 * 字号倍率 1.0。View 的钳制／点按链路本身需真机验收。
 */
class ConfirmOverlayMenuBudgetTest {

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
        // 余量约 37.2dp。
        assertEquals(37.16f, portraitAvailDp - natural, 0.01f)
    }

    @Test
    fun sixItems_fitsLandscapeToo() {
        // 横屏 44% 也放得下：余约 6.4dp；7 项以上仍靠钳制滚动保底。
        val natural = ConfirmOverlay.actionsMenuNaturalHeightDp(6)
        assertTrue(natural <= landscapeAvailDp)
        assertEquals(6.44f, landscapeAvailDp - natural, 0.01f)
    }

    @Test
    fun fiveItems_fitsLandscapeToo() {
        // 5 项 ≈ 138.26dp，横屏也放得下。
        assertEquals(138.26f, ConfirmOverlay.actionsMenuNaturalHeightDp(5), 0.01f)
        assertTrue(ConfirmOverlay.actionsMenuNaturalHeightDp(5) <= landscapeAvailDp)
    }

    @Test
    fun sevenItems_fitsPortraitButShortLandscape() {
        // 7 项 ≈ 186.77dp：竖屏放得下，横屏仍短约 17.8dp、靠钳制滚动保底。
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
    fun oldGeometry_wouldNotFitPortrait() {
        // 回归锚点：旧口径（内边距 5dp、间距 6dp）6 项 ≈ 213.5dp，
        // 竖屏 24% 短约 14dp——正是本修复要消掉的缺口。
        val oldItemH = 13f * ConfirmOverlay.ROBOTO_LINE_HEIGHT_WITH_PADDING + 2 * 5f
        val oldNatural = 6 * oldItemH + 5 * 6f + 2 * 8f + 4f
        assertEquals(213.52f, oldNatural, 0.01f)
        assertTrue(oldNatural > portraitAvailDp)
    }
}
