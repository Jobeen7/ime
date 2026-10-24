package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具栏中央区配置的解析/序列化口径：
 * 未存过=出厂五项、显式空=留空、未知 id 丢弃、重复去重、超上限截断。
 */
class ToolbarConfigTest {

    @Test
    fun nullRawFallsBackToFactoryDefaults() {
        assertEquals(ToolbarConfig.DEFAULT_IDS, ToolbarConfig.parseIds(null))
        assertEquals(
            listOf(
                PanelAction.EmojiKeyboard, PanelAction.SelectAll, PanelAction.Copy,
                PanelAction.Paste, PanelAction.Clipboard,
            ),
            ToolbarConfig.idsToActions(ToolbarConfig.parseIds(null)),
        )
    }

    @Test
    fun explicitEmptyStaysEmpty() {
        assertEquals(emptyList<String>(), ToolbarConfig.parseIds(""))
        assertEquals(emptyList<String>(), ToolbarConfig.parseIds(" , ,"))
    }

    @Test
    fun unknownDroppedDuplicatesDedupedOrderKept() {
        assertEquals(
            listOf("voice", "copy", "emoji"),
            ToolbarConfig.parseIds("voice, nope ,copy,voice,emoji,copy"),
        )
    }

    @Test
    fun overLimitTruncatedAtMax() {
        val all = ToolbarConfig.POOL.joinToString(",") { it.id }
        val parsed = ToolbarConfig.parseIds(all)
        assertEquals(ToolbarConfig.MAX_ITEMS, parsed.size)
        assertEquals(ToolbarConfig.POOL.take(ToolbarConfig.MAX_ITEMS).map { it.id }, parsed)
    }

    @Test
    fun serializeRoundTripsAndFilters() {
        val raw = ToolbarConfig.serializeIds(listOf("cursor", "bogus", "undo", "cursor", "redo"))
        assertEquals("cursor,undo,redo", raw)
        assertEquals(listOf("cursor", "undo", "redo"), ToolbarConfig.parseIds(raw))
        assertEquals("", ToolbarConfig.serializeIds(emptyList()))
    }

    @Test
    fun everyPoolIdResolvesToAnAction() {
        for (item in ToolbarConfig.POOL) {
            assertEquals(item.action, ToolbarConfig.idsToActions(listOf(item.id)).single())
        }
    }
}
