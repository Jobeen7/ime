// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数字符号集（审计 J）：独立类型身份、内置内容、节点覆盖。 */
class NumericSymbolsTest {

    @Test
    fun builtInHasDistinctTypeAndNumericCategoryGlyphs() {
        val collection = NumericSymbols.builtIn()
        assertEquals("numericSymbols", collection.type)
        val expected = Symbol.Symbol.first { it.first.label == "数字" }.second
        assertTrue(expected.isNotEmpty())
        assertEquals(
            expected.map { SymbolItem.Plain(it) },
            collection.items,
        )
    }

    @Test
    fun nodeDataSourceOverridesBuiltIn() {
        val collection = NumericSymbols.parse(
            Json.parseToJsonElement("""{"dataSource": ["1", "2"]}"""),
        )
        assertEquals("numericSymbols", collection.type)
        assertEquals(
            listOf(SymbolItem.Plain("1"), SymbolItem.Plain("2")),
            collection.items,
        )
    }

    @Test
    fun missingOrEmptyNodeFallsBackToBuiltIn() {
        assertEquals(NumericSymbols.builtIn(), NumericSymbols.parse(null))
        assertEquals(
            NumericSymbols.builtIn(),
            NumericSymbols.parse(Json.parseToJsonElement("""{"dataSource": []}""")),
        )
    }
}
