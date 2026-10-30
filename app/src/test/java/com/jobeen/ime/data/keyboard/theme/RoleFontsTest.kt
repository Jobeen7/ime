package com.jobeen.ime.data.keyboard.theme

import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.ValidatedSkin
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分角色字体（2026-10-29 item-1）：皮肤内逐角色映射解析与分层。 */
class RoleFontsTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun skinWithRoleFonts(roleFontsJson: String): ValidatedSkin {
        return validate(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"},
               "roleFonts": $roleFontsJson}""",
        ).skin
    }

    @Test
    fun validRolesParseAndInvalidDropIndividually() {
        val skin = skinWithRoleFonts(
            """{"key": "serif", "candidate": "monospace", "bogus": "serif",
               "comment": "no-such-family", "popup": "default", "toolbar": 42}""",
        )
        assertEquals(
            mapOf(FontRole.KEY to "serif", FontRole.CANDIDATE to "monospace"),
            skin.roleFonts,
        )
    }

    @Test
    fun nonObjectRoleFontsFallsBackToEmpty() {
        val skin = skinWithRoleFonts("""["serif"]""")
        assertTrue(skin.roleFonts.isEmpty())
    }

    @Test
    fun snapshotCarriesRoleFonts() {
        val validated = skinWithRoleFonts("""{"key": "serif"}""")
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
            defaultScheme, com.jobeen.ime.skin.model.MemoryTier.NORMAL,
            { "a".repeat(64) }, 1000, 400,
        )
        assertEquals(mapOf(FontRole.KEY to "serif"), snapshot.roleFonts)
    }

    @Test
    fun effectiveSourceOrdersSkinRoleAboveSkinFamily() {
        // 皮肤逐角色 ＞ 皮肤单值 ＞ 全局 ＞ 默认。
        assertEquals(
            GlobalFonts.EffectiveSource.SkinRole("serif"),
            GlobalFonts.effectiveSource(
                "monospace", FontChoice.System("sans-serif"),
                mapOf(FontRole.KEY to "serif"), FontRole.KEY,
            ),
        )
        assertEquals(
            GlobalFonts.EffectiveSource.Skin("monospace"),
            GlobalFonts.effectiveSource(
                "monospace", FontChoice.System("sans-serif"),
                mapOf(FontRole.CANDIDATE to "serif"), FontRole.KEY,
            ),
        )
        assertEquals(
            GlobalFonts.EffectiveSource.Global("sans-serif"),
            GlobalFonts.effectiveSource(
                null, FontChoice.System("sans-serif"),
                emptyMap(), FontRole.KEY,
            ),
        )
        assertEquals(
            GlobalFonts.EffectiveSource.Default,
            GlobalFonts.effectiveSource(null, FontChoice.Follow, emptyMap(), FontRole.KEY),
        )
    }
}
