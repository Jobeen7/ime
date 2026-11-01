// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按符号样式表（顶层 section）与逐键挂载字段的校验层。 */
class HintSymbolsValidatorTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 157, fallbacks, assetKind = { id ->
                when (id) {
                    "bg_main" -> AssetKind.IMAGE
                    else -> null
                }
            },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private val base = """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
        "stylesDark": {"keyBackground": "#FF222222"},
        "appearance": {"background": {"type": "asset", "asset": "bg_main"}}}"""

    @Test
    fun hintSymbolsStylesSectionParsesAndDropsInvalidItems() {
        val valid = validate(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
                "stylesDark": {"keyBackground": "#FF222222"},
                "appearance": {"background": {"type": "asset", "asset": "bg_main"}},
                "hintSymbolsStyles": {
                    "good": {"symbolStyles": ["s1", "s2"], "selectedIndex": 1},
                    "oldForm": {"foregroundStyle": ["f"], "actions": [{"character": "q"}]},
                    "empty": {"symbolStyles": []}
                }}""",
        )
        assertEquals(1, valid.skin.hintSymbolsStyles.size)
        val good = valid.skin.hintSymbolsStyles.getValue("good")
        assertEquals(listOf("s1", "s2"), good.symbolStyles)
        assertEquals(1, good.selectedIndex)
        assertTrue(
            valid.issues.any { it.path == "hintSymbolsStyles.oldForm" },
        )
        assertTrue(
            valid.issues.any { it.path == "hintSymbolsStyles.empty" },
        )
    }

    @Test
    fun keyMappingHintFieldsAttach() {
        val valid = validate(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
                "stylesDark": {"keyBackground": "#FF222222"},
                "appearance": {"background": {"type": "asset", "asset": "bg_main"}},
                "keyMappings": {"KEY_Q": {
                    "hintSymbolsStyle": "qHint",
                    "hintStyle": {"size": {"width": 60, "height": 40}}
                }}}""",
        )
        val override = valid.skin.keyOverrides.getValue("KEY_Q")
        assertEquals("qHint", override.hintSymbolsStyle)
        assertEquals(HintBubbleSize(60f, 40f), override.hintStyle?.size)
        assertEquals(true, override.hintStyle?.checkIfOverflowsParentHeight)
    }

    @Test
    fun keyMappingHintFieldsAbsentByDefault() {
        val valid = validate(base)
        assertTrue(valid.skin.keyOverrides.isEmpty())
        assertTrue(valid.skin.hintSymbolsStyles.isEmpty())
    }

    @Test
    fun hintSymbolsStylesNonObjectIsIssueNotRejection() {
        val valid = validate(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
                "stylesDark": {"keyBackground": "#FF222222"},
                "appearance": {"background": {"type": "asset", "asset": "bg_main"}},
                "hintSymbolsStyles": "nope"}""",
        )
        assertTrue(valid.skin.hintSymbolsStyles.isEmpty())
        assertTrue(
            valid.issues.any { it.path == "hintSymbolsStyles" },
        )
    }
}
