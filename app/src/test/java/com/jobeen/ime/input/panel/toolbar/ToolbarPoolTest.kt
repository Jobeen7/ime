package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具栏中央池：简繁仍在池中（可添加），但不再是出厂默认列；
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
    fun defaultColumnsAreFiveWithoutTraditionalChinese() {
        assertEquals(5, ToolbarConfig.DEFAULT_IDS.size)
        assertEquals("clipboard", ToolbarConfig.DEFAULT_IDS.last())
        assertTrue(!ToolbarConfig.DEFAULT_ACTIONS.contains(PanelAction.ToggleTraditionalChinese))
    }

    @Test
    fun defaultActionsStayWithinMaxItems() {
        assertTrue(ToolbarConfig.DEFAULT_IDS.size <= ToolbarConfig.MAX_ITEMS)
    }
}
