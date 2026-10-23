package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快照构建（定稿三.4/十章 + 详设 §6.2）：合成、presence、toast 回退、降级。 */
class SkinSnapshotBuilderTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-1", "h".repeat(64), 1)

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 100,
        keyPressed = 101,
        keyBorderStroke = 102,
        specialKeyBackground = 200,
        specialKeyPressed = 201,
        specialKeyBorderStroke = 202,
        accentKeyBackground = 300,
        accentKeyPressed = 301,
        accentKeyBorderStroke = 302,
        keyText = 400,
        specialKeyText = 401,
        accentKeyText = 402,
        altText = 403,
        background = 500,
        cornerRadius = 5f,
        keyHMargin = 3f,
        keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999,
        toastText = 998,
    )

    private fun validated(json: String): ValidatedSkin {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 143, fallbacks) { id ->
            when (id) {
                "bg_main" -> AssetKind.IMAGE
                else -> null
            }
        }
        assertTrue(result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    private fun build(
        json: String,
        tier: MemoryTier = MemoryTier.NORMAL,
        sha: (String) -> String? = { "a".repeat(64) },
        targetW: Int = 1000,
        targetH: Int = 400,
    ) = SkinSnapshotBuilder.build(validated(json), ref, defaultScheme, tier, sha, targetW, targetH)

    @Test
    fun partialStylesComposeOverDefault() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FF0000AA", "panel": {"candidateText": "#FF0000BB"}}}""",
        )
        assertEquals(0xFF0000AA.toInt(), snapshot.colorScheme.keyBackground)
        assertEquals(0xFF0000BB.toInt(), snapshot.colorScheme.panel.candidateText)
        assertEquals(101, snapshot.colorScheme.keyPressed)
        assertEquals(500, snapshot.colorScheme.background)
    }

    @Test
    fun toastFallsBackToComposedSpecialAndKeyText() {
        val snapshot = build("""{"formatVersion": 3, "styles": {"specialKeyBackground": "#FF000111"}}""")
        // toast 未给：回退合成后的 specialKeyBackground / keyText，而非默认方案自带的 999/998
        assertEquals(0xFF000111.toInt(), snapshot.colorScheme.toastBackground)
        assertEquals(400, snapshot.colorScheme.toastText)
        val explicit = build("""{"formatVersion": 3, "styles": {"toastBackground": "#FF000222"}}""")
        assertEquals(0xFF000222.toInt(), explicit.colorScheme.toastBackground)
    }

    @Test
    fun geometryPresenceComposition() {
        val snapshot = build(
            """{"formatVersion": 3, "appearance": {"cornerRadius": 8.0, "bottomInsetDp": 16.0}}""",
        )
        assertEquals(
            setOf(GeometryField.CORNER_RADIUS, GeometryField.BOTTOM_INSET),
            snapshot.geometryPresence,
        )
        assertEquals(8f, snapshot.colorScheme.cornerRadius)
        // 非 presence 几何取默认方案值占位（resolve 时再读偏好）
        assertEquals(3f, snapshot.colorScheme.keyHMargin)
        assertEquals(16f, snapshot.effectiveGeometry.getValue(GeometryField.BOTTOM_INSET))
        assertEquals(6f, snapshot.effectiveGeometry.getValue(GeometryField.KEY_H_MARGIN))
    }

    @Test
    fun lowTierDisablesBlur() {
        val json = """
            {"formatVersion": 3, "appearance": {"background":
              {"type": "asset", "asset": "bg_main", "blurRadius": 15, "overlayAlpha": 0.2}}}
        """.trimIndent()
        val normal = build(json, MemoryTier.NORMAL)
        assertEquals(15f, normal.background!!.blurRadius)
        assertTrue(normal.degradations.isEmpty())
        val low = build(json, MemoryTier.LOW)
        assertEquals(0f, low.background!!.blurRadius)
        assertTrue(Degradation.BLUR_DISABLED in low.degradations)
    }

    @Test
    fun extremeTierDownsamplesPreparedKey() {
        val json = """
            {"formatVersion": 3, "appearance": {"background":
              {"type": "asset", "asset": "bg_main", "blurRadius": 0}}}
        """.trimIndent()
        val extreme = build(json, MemoryTier.EXTREME, targetW = 1000, targetH = 400)
        assertTrue(extreme.background!!.preparedKey.contains("500x200"))
        assertTrue(Degradation.BACKGROUND_DOWNSAMPLED in extreme.degradations)
    }

    @Test
    fun backgroundDroppedWhenShaUnresolved() {
        val json = """
            {"formatVersion": 3, "appearance": {"background":
              {"type": "asset", "asset": "bg_main"}}}
        """.trimIndent()
        val snapshot = build(json, sha = { null })
        assertNull(snapshot.background)
    }
}
