// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 横向符号条（审计 J）：规格解析、RTL 顺序、分页与翻页。 */
class HorizontalSymbolsTest {

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun spec(
        maxColumns: String = "5",
        rtl: String = "false",
        items: String = """["a", "b", "c", "d", "e", "f", "g"]""",
    ): HorizontalSymbolsSpec {
        val (parsed, _) = HorizontalSymbolsParser.parse(
            element(
                """{"maxColumns": $maxColumns, "contentRightToLeft": $rtl,
                    "dataSource": $items, "cellStyle": "cell"}""",
            ),
        )
        return parsed!!
    }

    @Test
    fun defaultsAndCellStyle() {
        val (parsed, _) = HorizontalSymbolsParser.parse(
            element("""{"dataSource": ["a"]}"""),
        )
        assertEquals(5, parsed!!.maxColumns)
        assertFalse(parsed.contentRightToLeft)
        assertNull(parsed.cellStyle)
        assertEquals("cell", spec().cellStyle)
    }

    @Test
    fun invalidMaxColumnsFallsBackToFive() {
        val (parsed, _) = HorizontalSymbolsParser.parse(
            element("""{"maxColumns": 99, "dataSource": ["a"]}"""),
        )
        assertEquals(5, parsed!!.maxColumns)
    }

    @Test
    fun pagingChunksByMaxColumns() {
        val s = spec()
        assertEquals(2, HorizontalSymbolLayout.pageCount(s))
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            HorizontalSymbolLayout.visibleItems(s, 0).map { it.displayText },
        )
        assertEquals(
            listOf("f", "g"),
            HorizontalSymbolLayout.visibleItems(s, 1).map { it.displayText },
        )
        // 越界页码夹取到末页。
        assertEquals(
            listOf("f", "g"),
            HorizontalSymbolLayout.visibleItems(s, 9).map { it.displayText },
        )
    }

    @Test
    fun rightToLeftReversesWholeStrip() {
        val s = spec(rtl = "true")
        assertEquals(
            listOf("g", "f", "e", "d", "c"),
            HorizontalSymbolLayout.visibleItems(s, 0).map { it.displayText },
        )
    }

    @Test
    fun emptyDataSourceYieldsNoSpec() {
        val (parsed, dropped) = HorizontalSymbolsParser.parse(
            element("""{"dataSource": [""]}"""),
        )
        assertNull(parsed)
        assertEquals(1, dropped)
    }

    @Test
    fun stripStatePagesForwardAndBack() {
        val state = HorizontalSymbolStripState(spec())
        assertTrue(state.nextPage())
        assertFalse(state.nextPage())
        assertTrue(state.prevPage())
        assertFalse(state.prevPage())
    }
}
