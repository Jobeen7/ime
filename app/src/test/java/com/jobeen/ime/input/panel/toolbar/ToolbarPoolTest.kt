package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具栏中央池：简繁进池并成为第 6 个出厂默认列；
 * 动作路由本身依赖 Android 服务，走真机验收。
 */
class ToolbarPoolTest {

    @Test
    fun traditionalChineseIsInPool() {
        val item = ToolbarConfig.item("traditional_chinese")
        assertTrue(item != null)
        assertEquals(PanelAction.ToggleTraditionalChinese, item?.action)
        assertEquals("简繁", item?.label)
    }

    @Test
    fun defaultColumnsAreSixWithTraditionalChineseLast() {
        assertEquals(6, ToolbarConfig.DEFAULT_IDS.size)
        assertEquals("traditional_chinese", ToolbarConfig.DEFAULT_IDS.last())
        assertTrue(ToolbarConfig.DEFAULT_ACTIONS.contains(PanelAction.ToggleTraditionalChinese))
    }

    @Test
    fun defaultActionsStayWithinMaxItems() {
        assertTrue(ToolbarConfig.DEFAULT_IDS.size <= ToolbarConfig.MAX_ITEMS)
    }
}
