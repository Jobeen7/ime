// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 九宫格符号板（审计 J）：3×3 分页、补齐行、横向九键条缺省列数。 */
class T9SymbolsTest {

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun tenItems(): String =
        (1..10).joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    @Test
    fun pagesAreNineCells() {
        val (parsed, _) = T9SymbolsParser.parse(
            element("""{"dataSource": ${tenItems()}}"""),
        )
        val spec = parsed!!
        assertEquals(2, T9SymbolLayout.pageCount(spec))
        assertEquals(9, T9SymbolLayout.visiblePage(spec, 0).size)
        assertEquals(1, T9SymbolLayout.visiblePage(spec, 1).size)
    }

    @Test
    fun rowsPadShortLastRowToThreeColumns() {
        val (parsed, _) = T9SymbolsParser.parse(
            element("""{"dataSource": ["a", "b", "c", "d", "e"]}"""),
        )
        val rows = T9SymbolLayout.rowsForPage(
            T9SymbolLayout.visiblePage(parsed!!, 0),
        )
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0].map { it?.displayText })
        assertEquals(listOf("d", "e", null), rows[1].map { it?.displayText })
    }

    @Test
    fun emptyDataSourceYieldsNoSpec() {
        val (parsed, _) = T9SymbolsParser.parse(element("""{"dataSource": []}"""))
        assertNull(parsed)
    }

    @Test
    fun horizontalStripDefaultsToNineColumns() {
        val (parsed, _) = T9HorizontalSymbolsParser.parse(
            element("""{"dataSource": ${tenItems()}}"""),
        )
        assertEquals(9, parsed!!.maxColumns)
        assertEquals(2, HorizontalSymbolLayout.pageCount(parsed))
    }

    @Test
    fun horizontalStripRespectsExplicitMaxColumns() {
        val (parsed, _) = T9HorizontalSymbolsParser.parse(
            element("""{"maxColumns": 5, "dataSource": ${tenItems()}}"""),
        )
        assertEquals(5, parsed!!.maxColumns)
    }
}
