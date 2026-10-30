package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拟态双阴影（2026-10-29 item-1）：解析口径＋快照透传＋三层形。 */
class KeyShadowTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun skinWithShadow(shadowJson: String): ValidatedSkin {
        val tree = Json.parseToJsonElement(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"},
               "keyShadow": $shadowJson}""",
        ) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    @Test
    fun missingShadowDefaultsToDisabled() {
        val tree = Json.parseToJsonElement(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"}}""",
        ) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        val skin = (result as SkinValidator.Result.Valid).skin
        assertFalse(skin.keyShadow.enabled)
        assertEquals(KeyShadowSpec.DEFAULT_OFFSET_DP, skin.keyShadow.offsetDp)
    }

    @Test
    fun fullShadowParsesColorsAndOffset() {
        val skin = skinWithShadow(
            """{"enabled": true, "lightColor": "#80FFFFFF",
               "darkColor": "#33000000", "offsetDp": 3}""",
        )
        assertTrue(skin.keyShadow.enabled)
        assertEquals(0x80FFFFFF.toInt(), skin.keyShadow.lightColor)
        assertEquals(0x33000000.toInt(), skin.keyShadow.darkColor)
        assertEquals(3f, skin.keyShadow.offsetDp)
    }

    @Test
    fun invalidFieldsFallBackIndividually() {
        val skin = skinWithShadow(
            """{"enabled": "yes", "lightColor": "red",
               "darkColor": "#12345", "offsetDp": 99}""",
        )
        assertFalse(skin.keyShadow.enabled)
        assertEquals(KeyShadowSpec.DEFAULT_LIGHT_COLOR, skin.keyShadow.lightColor)
        assertEquals(KeyShadowSpec.DEFAULT_DARK_COLOR, skin.keyShadow.darkColor)
        assertEquals(KeyShadowSpec.DEFAULT_OFFSET_DP, skin.keyShadow.offsetDp)
    }

    @Test
    fun nonObjectShadowFallsBackToDisabled() {
        val (spec, issues) = KeyShadowParser.parse(
            Json.parseToJsonElement("""[1, 2]"""),
        )
        assertFalse(spec.enabled)
        assertEquals(1, issues.size)
        assertEquals("key_shadow_not_object", issues.single().detail)
    }

    @Test
    fun snapshotCarriesShadowSpec() {
        val validated = skinWithShadow("""{"enabled": true, "offsetDp": 4}""")
        val defaultScheme = KeyboardColors.ColorScheme(
            keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
            specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
            accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
            keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
            background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
                candidateBackground = 604, candidateText = 605, candidateIndex = 606,
                candidateDivider = 607, toolbarPressed = 608,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 700, textColor = 701, secondaryTextColor = 702,
            ),
            toastBackground = 999, toastText = 998,
        )
        val snapshot = SkinSnapshotBuilder.build(
            validated, SkinGenRef("skin-1", "h".repeat(64), 1),
            defaultScheme, MemoryTier.NORMAL, { "a".repeat(64) }, 1000, 400,
        )
        assertTrue(snapshot.keyShadow.enabled)
        assertEquals(4f, snapshot.keyShadow.offsetDp)
    }
}
