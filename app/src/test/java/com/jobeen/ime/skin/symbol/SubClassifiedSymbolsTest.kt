// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 二级分类符号（审计 J）：解析、角标、面板状态机、一级表适配。 */
class SubClassifiedSymbolsTest {

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    private val json = """{
        "parents": [
            {"label": "数学", "subCategories": [
                {"label": "运算", "symbols": ["+", "-", "×"]},
                {"label": "比较", "symbols": ["<", ">"], "badge": "新"}
            ]},
            {"label": "空类", "subCategories": []},
            {"label": "括号", "subCategories": [
                {"label": "圆", "dataSource": [{"label": "左", "value": "("}]}
            ], "badge": "99"}
        ]
    }"""

    @Test
    fun parsesTwoLevelsAndCountsDroppedParents() {
        val (parsed, dropped) = SubClassifiedSymbolsParser.parse(element(json))
        assertEquals(1, dropped) // 空类无有效子类，整父项丢弃。
        assertEquals(2, parsed!!.parents.size)
        assertEquals(listOf("数学", "括号"), parsed.parents.map { it.label })
        assertEquals(listOf("运算", "比较"), parsed.parents[0].subCategories.map { it.label })
        assertEquals(
            SymbolItem.LabelValue("左", "("),
            parsed.parents[1].subCategories[0].items[0],
        )
    }

    @Test
    fun badgeExplicitBeatsDerivedCount() {
        val (parsed, _) = SubClassifiedSymbolsParser.parse(element(json))
        val math = parsed!!.parents[0]
        assertEquals("5", math.badgeText) // 3 + 2，派生总数。
        assertEquals("3", math.subCategories[0].badgeText)
        assertEquals("新", math.subCategories[1].badgeText) // 显式优先。
        assertEquals("99", parsed.parents[1].badgeText)
    }

    @Test
    fun emptyTopLevelReturnsNull() {
        val (parsed, _) = SubClassifiedSymbolsParser.parse(element("""{"parents": []}"""))
        assertNull(parsed)
        val (parsed2, _) = SubClassifiedSymbolsParser.parse(element("""[]"""))
        assertNull(parsed2)
    }

    @Test
    fun stateSwitchingParentResetsSub() {
        val (parsed, _) = SubClassifiedSymbolsParser.parse(element(json))
        val state = SubClassifiedBoardState(parsed!!)
        assertEquals(listOf("数学", "括号"), state.parentLabels)
        assertTrue(state.selectSub(1))
        assertEquals("比较", state.currentSub!!.label)
        assertTrue(state.selectParent(1))
        assertEquals(0, state.subIndex)
        assertEquals("圆", state.currentSub!!.label)
        assertEquals(1, state.currentItems.size)
    }

    @Test
    fun outOfRangeSelectionIsNoOp() {
        val (parsed, _) = SubClassifiedSymbolsParser.parse(element(json))
        val state = SubClassifiedBoardState(parsed!!)
        assertFalse(state.selectParent(9))
        assertEquals(0, state.parentIndex)
        assertFalse(state.selectSub(9))
        assertEquals(0, state.subIndex)
    }

    @Test
    fun singleLevelAdapterKeepsAllItems() {
        val adapted = SubClassifiedSymbols.fromSingleLevel(
            listOf(Symbol.Category("常规") to arrayOf("a", "b")),
        )
        assertEquals(1, adapted.parents.size)
        assertEquals("常规", adapted.parents[0].label)
        assertEquals("全部", adapted.parents[0].subCategories[0].label)
        assertEquals("2", adapted.parents[0].badgeText)
    }
}
