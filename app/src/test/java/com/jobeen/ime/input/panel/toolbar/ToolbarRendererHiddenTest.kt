// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具栏隐藏的触摸分区：隐藏时只压制中间区，首尾固定槽照常。
 * 渲染器实例化依赖 Android Paint，JVM 单测只能覆盖这套纯函数；
 * hitTest 里的接线（早期返回）与绘制门控以编译＋真机为准。
 */
class ToolbarRendererHiddenTest {

    // width=1080, density=3：左槽触摸区 [0..132]，右槽 [948..1080]。
    private fun region(x: Float): ToolbarHitRegion =
        toolbarHitRegion(x, 1080, 3f, 0f, 12f)

    @Test
    fun region_menuSlot_isMenu() {
        assertEquals(ToolbarHitRegion.MENU, region(60f))
        assertEquals(ToolbarHitRegion.MENU, region(132f))
    }

    @Test
    fun region_closeSlot_isClose() {
        assertEquals(ToolbarHitRegion.CLOSE, region(1020f))
        assertEquals(ToolbarHitRegion.CLOSE, region(948f))
    }

    @Test
    fun region_middle_isCenter() {
        assertEquals(ToolbarHitRegion.CENTER, region(133f))
        assertEquals(ToolbarHitRegion.CENTER, region(500f))
        assertEquals(ToolbarHitRegion.CENTER, region(947f))
    }

    @Test
    fun region_narrowWidth_overlap_prefersClose() {
        // 窄屏下左右触摸区重叠：与 hitTest 一致，右槽优先。
        val overlapped = toolbarHitRegion(100f, 200, 3f, 0f, 12f)
        assertEquals(ToolbarHitRegion.CLOSE, overlapped)
    }

    @Test
    fun suppressed_hiddenCenter_only() {
        assertTrue(toolbarCenterHitSuppressed(true, ToolbarHitRegion.CENTER))
        assertFalse(toolbarCenterHitSuppressed(true, ToolbarHitRegion.MENU))
        assertFalse(toolbarCenterHitSuppressed(true, ToolbarHitRegion.CLOSE))
        assertFalse(toolbarCenterHitSuppressed(false, ToolbarHitRegion.CENTER))
        assertFalse(toolbarCenterHitSuppressed(false, ToolbarHitRegion.MENU))
        assertFalse(toolbarCenterHitSuppressed(false, ToolbarHitRegion.CLOSE))
    }
}
