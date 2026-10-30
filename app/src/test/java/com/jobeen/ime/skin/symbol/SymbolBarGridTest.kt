// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 符号栏＋长按符号格（重点候选 8-6）：解析、布局、位移选格、角选、状态。 */
class SymbolBarGridTest {

    private fun spec(
        cells: List<String?> = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"),
        columns: Int = 3,
        anchorIndex: Int = 4,
        defaultIndex: Int = 4,
        cellSizePx: Float = 100f,
        sensitivity: Float = 1f,
        jitterPx: Float = 8f,
    ): SymbolGridSpec = SymbolGridSpec.create(
        cells, columns, anchorIndex, defaultIndex, cellSizePx, sensitivity, jitterPx,
    )!!

    @Test
    fun layout_anchorAlignedAndEdgeShifted() {
        val grid = spec()
        val centered = SymbolGridResolver.layoutGrid(grid, 500f, 500f, 1000f, 1000f)
        assertEquals(350f, centered.left)
        assertEquals(350f, centered.top)
        val rightEdge = SymbolGridResolver.layoutGrid(grid, 950f, 500f, 1000f, 1000f)
        assertEquals(700f, rightEdge.left)
        val bottomEdge = SymbolGridResolver.layoutGrid(grid, 500f, 980f, 1000f, 1000f)
        assertEquals(700f, bottomEdge.top)
    }

    @Test
    fun highlight_displacementStepsAndJitter() {
        val grid = spec()
        assertEquals(4, SymbolGridResolver.highlightFor(grid, 0f, 0f))
        assertEquals(4, SymbolGridResolver.highlightFor(grid, 5f, 0f))
        assertEquals(5, SymbolGridResolver.highlightFor(grid, 50f, 0f))
        assertEquals(3, SymbolGridResolver.highlightFor(grid, -50f, 0f))
        assertEquals(7, SymbolGridResolver.highlightFor(grid, 0f, 50f))
        assertNull(SymbolGridResolver.highlightFor(grid, 150f, 0f))
        assertNull(SymbolGridResolver.highlightFor(grid, 0f, -150f))
    }

    @Test
    fun highlight_emptyCellNearestWins() {
        val grid = spec(
            cells = listOf("a", "b", "c", "d", "e", null, "g", "h", "i"),
        )
        // 右移目标格（下标 5）为空 → 环内行主序首个非空（下标 1）
        assertEquals(1, SymbolGridResolver.highlightFor(grid, 50f, 0f))
        val allEmpty = spec(
            cells = listOf(null, null, null, null),
            columns = 2, anchorIndex = 0, defaultIndex = 0,
        )
        assertNull(SymbolGridResolver.highlightFor(allEmpty, 0f, 0f))
    }

    @Test
    fun specCreate_rejectsInvalid() {
        assertNull(SymbolGridSpec.create(emptyList(), 3, 0, 0, 100f, 1f, 8f))
        assertNull(SymbolGridSpec.create(listOf("a"), 0, 0, 0, 100f, 1f, 8f))
        assertNull(SymbolGridSpec.create(listOf("a"), 1, 5, 0, 100f, 1f, 8f))
        assertNull(SymbolGridSpec.create(listOf("a"), 1, 0, 0, 0f, 1f, 8f))
        assertNull(SymbolGridSpec.create(listOf("a"), 1, 0, 0, 100f, 0f, 8f))
        assertNull(SymbolGridSpec.create(listOf("a"), 1, 0, 0, 100f, 1f, -1f))
    }

    @Test
    fun cornerSelector_quadrantsAndMissing() {
        assertEquals(0, CornerSelector.select(-50f, -50f, 8f, 4))
        assertEquals(1, CornerSelector.select(50f, -50f, 8f, 4))
        assertEquals(2, CornerSelector.select(-50f, 50f, 8f, 4))
        assertEquals(3, CornerSelector.select(50f, 50f, 8f, 4))
        assertNull(CornerSelector.select(5f, 50f, 8f, 4))
        assertNull(CornerSelector.select(50f, 5f, 8f, 4))
        assertNull(CornerSelector.select(50f, 50f, 8f, 2))
        assertEquals(1, CornerSelector.select(50f, -50f, 8f, 2))
    }

    @Test
    fun barParser_validAndDropped() {
        val (parsed, dropped) = SymbolBarParser.parse(
            Json.parseToJsonElement(
                """{
                    "symbols": [
                        {"tap": "。", "corners": ["「", "」", "『", "』", "多余"]},
                        {"tap": "，"},
                        {"tap": ""},
                        "notobject",
                        {"corners": ["x"]}
                    ],
                    "closePosition": "left",
                    "typeSwitchPosition": "right"
                }""",
            ),
        )
        assertEquals(3, dropped)
        assertEquals(2, parsed?.keys?.size)
        assertEquals(4, parsed?.keys?.get(0)?.corners?.size)
        assertEquals(BarSide.LEFT, parsed?.closePosition)
        assertEquals(BarSide.RIGHT, parsed?.typeSwitchPosition)

        val (defaults, _) = SymbolBarParser.parse(
            Json.parseToJsonElement(
                """{"symbols": [{"tap": "。"}], "closePosition": "up"}""",
            ),
        )
        assertEquals(BarSide.RIGHT, defaults?.closePosition)
        assertEquals(BarSide.LEFT, defaults?.typeSwitchPosition)

        val (noSymbols, _) = SymbolBarParser.parse(
            Json.parseToJsonElement("""{"closePosition": "left"}"""),
        )
        assertNull(noSymbols)
    }

    @Test
    fun barState_typeRemembered_openStateNot() {
        val store = InMemorySymbolBarTypeStore()
        val first = SymbolBarState(store)
        assertFalse(first.isOpen)
        assertNull(first.currentType)
        first.switchType("标点")
        first.open()
        assertTrue(first.isOpen)
        first.close()
        assertFalse(first.isOpen)
        assertEquals("标点", first.currentType)
        val second = SymbolBarState(store)
        assertFalse(second.isOpen)
        assertEquals("标点", second.currentType)
    }
}
