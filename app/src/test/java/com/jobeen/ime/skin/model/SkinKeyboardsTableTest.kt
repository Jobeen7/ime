package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.parseToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** skin.json `keyboards` 表解析：内置 7 类型 + 任意自定义类型，字段级回退。 */
class SkinKeyboardsTableTest {

    private fun tree(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test
    fun parsesBuiltinTypesAndCustomType() {
        val parsed = SkinJson.parseKeyboards(
            tree(
                """{"keyboards": {
                    "alphabetic": {"layoutId": "alpha-lay"},
                    "pinyin": {"layoutId": "pin-lay", "heightPercent": 42},
                    "emojis": {"layoutId": "emo-lay"},
                    "images": {"layoutId": "img-lay"},
                    "numeric": {"layoutId": "num-lay"},
                    "numberPad": {"layoutId": "pad-lay"},
                    "symbolic": {"layoutId": "sym-lay"},
                    "myBoard": {"layoutId": "custom-lay", "heightPercent": 60}
                }}""",
            ),
        )
        assertEquals(KeyboardTypes.BUILTIN + "myBoard", parsed.keys)
        assertEquals("pin-lay", parsed.getValue("pinyin").layoutId)
        assertEquals(42.0, parsed.getValue("pinyin").heightPercent)
        assertEquals("custom-lay", parsed.getValue("myBoard").layoutId)
    }

    @Test
    fun stringShorthandMeansLayoutId() {
        val parsed = SkinJson.parseKeyboards(
            tree("""{"keyboards": {"pinyin": "pin-lay"}}"""),
        )
        assertEquals(KeyboardTypeEntry(layoutId = "pin-lay"), parsed.getValue("pinyin"))
    }

    @Test
    fun invalidItemsAndFieldsAreDroppedNotFatal() {
        val parsed = SkinJson.parseKeyboards(
            tree(
                """{"keyboards": {
                    "pinyin": {"layoutId": 42, "heightPercent": 999},
                    "emojis": 7,
                    "symbolic": {},
                    "": {"layoutId": "x"},
                    "numeric": {"heightPercent": 55.5}
                }}""",
            ),
        )
        // pinyin 全字段非法 → 全空项不占位；其余非法项同理。
        assertEquals(setOf("numeric"), parsed.keys)
        assertEquals(55.5, parsed.getValue("numeric").heightPercent)
        assertNull(parsed.getValue("numeric").layoutId)
    }

    @Test
    fun missingTableIsEmpty() {
        assertTrue(SkinJson.parseKeyboards(tree("""{"layoutId": "a"}""")).isEmpty())
        assertTrue(SkinJson.parseKeyboards(tree("""{"keyboards": "nope"}""")).isEmpty())
    }

    @Test
    fun landscapeHeightFallsBackToPortrait() {
        val entry = KeyboardTypeEntry(layoutId = "a", heightPercent = 44.0)
        assertEquals(44.0, entry.heightPercentFor(false))
        assertEquals(44.0, entry.heightPercentFor(true))
        val withLandscape = entry.copy(heightPercentLandscape = 50.0)
        assertEquals(50.0, withLandscape.heightPercentFor(true))
        assertEquals(44.0, withLandscape.heightPercentFor(false))
    }

    @Test
    fun validatorAcceptsSkinWithKeyboardsTable() {
        val result = SkinValidator.validate(
            tree(
                """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
                    "keyboards": {"pinyin": {"layoutId": "pin-lay"}}}""",
            ),
            157,
            GeometryFallbacks(12f, 6f, 5f, 10f),
            assetKind = { null },
        )
        assertTrue("keyboards 表不得导致校验拒绝，实际：$result", result is SkinValidator.Result.Valid)
    }
}
