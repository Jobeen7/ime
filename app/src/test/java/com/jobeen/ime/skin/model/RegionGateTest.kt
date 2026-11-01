package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 区域门控：缺失 = 不创建（非透明），皮肤压制 = 缺席。 */
class RegionGateTest {

    @Test
    fun missingStyleMeansNotCreated() {
        assertFalse(RegionGate.isCreated(stylePresent = false, suppressed = false))
        assertFalse(RegionGate.isToolbarZoneCreated(false, false))
        assertFalse(RegionGate.isKeyboardZoneCreated(false, false))
    }

    @Test
    fun suppressionMeansAbsentEvenWhenStylePresent() {
        assertFalse(RegionGate.isCreated(stylePresent = true, suppressed = true))
        assertFalse(RegionGate.isToolbarZoneCreated(true, true))
    }

    @Test
    fun presentAndUnsuppressedIsCreated() {
        assertTrue(RegionGate.isCreated(stylePresent = true, suppressed = false))
        assertTrue(RegionGate.isToolbarZoneCreated(true, false))
        assertTrue(RegionGate.isKeyboardZoneCreated(true, false))
    }

    @Test
    fun suppressFlagsParseAndDefaultFalse() {
        val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
        fun validated(json: String): ValidatedSkin {
            val trimmed = json.trimEnd()
            val withLight = if (json.contains("\"styles\"")) json
            else trimmed.dropLast(1) + """, "styles": {"keyBackground": "#FFFFFFFF"}}"""
            val withDark = if (withLight.contains("\"stylesDark\"")) withLight
            else withLight.trimEnd().dropLast(1) +
                """, "stylesDark": {"keyBackground": "#FF222222"}}"""
            val tree = Json.parseToJsonElement(withDark) as JsonObject
            val result = SkinValidator.validate(tree, 143, fallbacks, assetKind = { null })
            assertTrue(result is SkinValidator.Result.Valid)
            return (result as SkinValidator.Result.Valid).skin
        }
        val suppressed = validated(
            """{"formatVersion": 3, "suppressToolbar": true}""",
        )
        assertTrue(suppressed.suppressToolbar)
        assertFalse(suppressed.suppressKeyboard)
        val plain = validated("""{"formatVersion": 3}""")
        assertFalse(plain.suppressToolbar)
        assertFalse(plain.suppressKeyboard)
    }
}
