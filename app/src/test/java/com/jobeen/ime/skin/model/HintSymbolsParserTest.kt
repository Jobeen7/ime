// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按符号样式现行 form 与短按气泡样式的解析口径。 */
class HintSymbolsParserTest {

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun currentFormParsesAllFields() {
        val style = HintSymbolsParser.parseStyle(
            element(
                """{
                    "size": {"width": 50, "height": 50},
                    "insets": {"top": 4, "bottom": 4, "left": 4, "right": 4},
                    "backgroundStyle": "panelBg",
                    "selectedBackgroundStyle": "selectedBg",
                    "selectedIndex": 1,
                    "checkIfOverflowsParentHeight": false,
                    "symbolStyles": ["sym1", "sym2", "sym3"]
                }""",
            ),
        )!!
        assertEquals(listOf("sym1", "sym2", "sym3"), style.symbolStyles)
        assertEquals(HintBubbleSize(50f, 50f), style.size)
        assertEquals(AreaInsets(left = 4f, top = 4f, right = 4f, bottom = 4f), style.insets)
        assertEquals("panelBg", style.backgroundStyle)
        assertEquals("selectedBg", style.selectedBackgroundStyle)
        assertEquals(1, style.selectedIndex)
        assertEquals(false, style.checkIfOverflowsParentHeight)
    }

    @Test
    fun defaultsApplyWhenOptionalFieldsAbsent() {
        val style = HintSymbolsParser.parseStyle(
            element("""{"symbolStyles": ["only"]}"""),
        )!!
        assertEquals(null, style.size)
        assertTrue(style.insets.isZero)
        assertNull(style.backgroundStyle)
        assertNull(style.selectedBackgroundStyle)
        assertEquals(0, style.selectedIndex)
        assertEquals(true, style.checkIfOverflowsParentHeight)
    }

    @Test
    fun emptySymbolStylesInvalidatesWholeStyle() {
        assertNull(HintSymbolsParser.parseStyle(element("""{"symbolStyles": []}""")))
        assertNull(HintSymbolsParser.parseStyle(element("""{"symbolStyles": ["", " "]}""")))
        assertNull(HintSymbolsParser.parseStyle(element("""{"backgroundStyle": "bg"}""")))
        assertNull(HintSymbolsParser.parseStyle(element("""42""")))
        assertNull(HintSymbolsParser.parseStyle(null))
    }

    @Test
    fun oldJointFormIsIgnored() {
        // 旧 form：foregroundStyle 与 actions 并行数组 + selectedStyle，
        // 无 symbolStyles → 整体无效；旧名字段即使与新字段同现，
        // selectedBackgroundStyle 仍只认新名。
        assertNull(
            HintSymbolsParser.parseStyle(
                element(
                    """{
                        "foregroundStyle": ["fg1", "fg2"],
                        "actions": [{"character": "q"}, {"symbol": "1"}],
                        "selectedStyle": "oldSelected"
                    }""",
                ),
            ),
        )
        val mixed = HintSymbolsParser.parseStyle(
            element(
                """{
                    "symbolStyles": ["sym1"],
                    "selectedStyle": "oldSelected"
                }""",
            ),
        )!!
        assertNull(mixed.selectedBackgroundStyle)
    }

    @Test
    fun sizeRequiresBothDimensionsAndSelectedIndexClamps() {
        val halfSize = HintSymbolsParser.parseStyle(
            element("""{"symbolStyles": ["a", "b"], "size": {"width": 50}}"""),
        )!!
        assertNull(halfSize.size)
        val percentSize = HintSymbolsParser.parseStyle(
            element("""{"symbolStyles": ["a"], "size": {"width": "50%", "height": "50%"}}"""),
        )!!
        assertNull(percentSize.size)
        val clamped = HintSymbolsParser.parseStyle(
            element("""{"symbolStyles": ["a", "b"], "selectedIndex": 9}"""),
        )!!
        assertEquals(1, clamped.selectedIndex)
        val negative = HintSymbolsParser.parseStyle(
            element("""{"symbolStyles": ["a"], "selectedIndex": -1}"""),
        )!!
        assertEquals(0, negative.selectedIndex)
    }

    @Test
    fun bubbleParsesSizeAndOverflow() {
        val bubble = HintSymbolsParser.parseBubble(
            element("""{"size": {"width": 60, "height": 40}, "checkIfOverflowsParentHeight": false}"""),
        )!!
        assertEquals(HintBubbleSize(60f, 40f), bubble.size)
        assertEquals(false, bubble.checkIfOverflowsParentHeight)
        val defaults = HintSymbolsParser.parseBubble(element("""{}"""))!!
        assertNull(defaults.size)
        assertEquals(true, defaults.checkIfOverflowsParentHeight)
        assertNull(HintSymbolsParser.parseBubble(element(""""x"""")))
    }
}
