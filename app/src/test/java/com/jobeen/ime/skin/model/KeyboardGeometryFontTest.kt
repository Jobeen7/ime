package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘级几何 geometry + 字族 fontFamily（2026-10-27 新增分流）：校验、回退、优先级、快照透传。 */
class KeyboardGeometryFontTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-geo", "h".repeat(64), 1)

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
    )

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun build(json: String) = SkinSnapshotBuilder.build(
        validate(json).skin, ref, defaultScheme, MemoryTier.NORMAL, { "a".repeat(64) }, 1000, 400,
    )

    private fun base(json: String, styles: String = "\"keyBackground\": \"#FFFFFFFF\""): String =
        if (json.isBlank()) {
            "{\"formatVersion\": 3, \"styles\": {$styles}, \"stylesDark\": {\"keyBackground\": \"#FF222222\"}}"
        } else {
            "{\"formatVersion\": 3, \"styles\": {$styles}, \"stylesDark\": {\"keyBackground\": \"#FF222222\"}, $json}"
        }

    @Test
    fun geometryAllFieldsValid() {
        val skin = validate(
            base(
                "\"geometry\": {\"keyRadiusDp\": 14, \"keyHGapDp\": 6, \"keyVGapDp\": 2.5, \"heightPercent\": 30}",
            ),
        ).skin
        assertEquals(
            setOf(
                KeyboardGeometryField.KEY_RADIUS, KeyboardGeometryField.KEY_H_GAP,
                KeyboardGeometryField.KEY_V_GAP, KeyboardGeometryField.HEIGHT_PERCENT,
            ),
            skin.keyboardGeometry.presence,
        )
        assertEquals(14f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_RADIUS))
        assertEquals(6f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_H_GAP))
        assertEquals(2.5f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_V_GAP))
        assertEquals(30f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.HEIGHT_PERCENT))
    }

    @Test
    fun geometryOutOfRangeAndWrongTypeFallBackPerField() {
        val result = validate(
            base(
                "\"geometry\": {\"keyRadiusDp\": 41, \"keyHGapDp\": -1, \"keyVGapDp\": \"6\", \"heightPercent\": 71}",
            ),
        )
        assertTrue(result.skin.keyboardGeometry.presence.isEmpty())
        val fallbackPaths = result.issues
            .filter { it.kind == SkinIssue.Kind.FIELD_FALLBACK }
            .map { it.path }.toSet()
        assertEquals(
            setOf(
                "geometry.keyRadiusDp", "geometry.keyHGapDp",
                "geometry.keyVGapDp", "geometry.heightPercent",
            ),
            fallbackPaths,
        )
    }

    @Test
    fun geometryNonFiniteFallsBack() {
        // 1e400 在 JSON 数值解析后为 Infinity：非有限数按坏字段处理，绝不进快照。
        val result = validate(base("\"geometry\": {\"keyRadiusDp\": 1e400}"))
        assertTrue(KeyboardGeometryField.KEY_RADIUS !in result.skin.keyboardGeometry.presence)
        assertTrue(
            result.issues.any {
                it.path == "geometry.keyRadiusDp" && it.kind == SkinIssue.Kind.FIELD_FALLBACK
            },
        )
    }

    @Test
    fun geometryNotObjectFallsBack() {
        val result = validate(base("\"geometry\": [1, 2]"))
        assertTrue(result.skin.keyboardGeometry.presence.isEmpty())
        assertTrue(result.issues.any { it.path == "geometry" })
    }

    @Test
    fun geometryWinsOverAppearanceAndIsFlagged() {
        val result = validate(
            base(
                "\"appearance\": {\"cornerRadius\": 8, \"keyHMargin\": 5, \"keyVMargin\": 4}," +
                    "\"geometry\": {\"keyRadiusDp\": 14, \"keyHGapDp\": 6, \"keyVGapDp\": 2}",
            ),
        )
        assertTrue(
            result.issues.any {
                it.path == "geometry.keyRadiusDp" && it.kind == SkinIssue.Kind.INFO &&
                    it.detail == "geometry_overrides_appearance:cornerRadius"
            },
        )
        val snapshot = build(
            base(
                "\"appearance\": {\"cornerRadius\": 8, \"keyHMargin\": 5, \"keyVMargin\": 4}," +
                    "\"geometry\": {\"keyRadiusDp\": 14, \"keyHGapDp\": 6, \"keyVGapDp\": 2}",
            ),
        )
        assertEquals(14f, snapshot.colorScheme.cornerRadius)
        assertEquals(6f, snapshot.colorScheme.keyHMargin)
        assertEquals(2f, snapshot.colorScheme.keyVMargin)
    }

    @Test
    fun appearanceStillWinsWhenGeometryAbsent() {
        val snapshot = build(
            base("\"appearance\": {\"cornerRadius\": 8, \"keyHMargin\": 5, \"keyVMargin\": 4}"),
        )
        assertEquals(8f, snapshot.colorScheme.cornerRadius)
        assertEquals(5f, snapshot.colorScheme.keyHMargin)
    }

    @Test
    fun heightPercentPassesThroughSnapshot() {
        val snapshot = build(base("\"geometry\": {\"heightPercent\": 30}"))
        assertTrue(
            KeyboardGeometryField.HEIGHT_PERCENT in snapshot.keyboardGeometry.presence,
        )
        assertEquals(30f, snapshot.keyboardGeometry.values.getValue(KeyboardGeometryField.HEIGHT_PERCENT))
        // 高度不进 ColorScheme：窗口 contentHeight() 直接读快照。
        assertEquals(5f, snapshot.colorScheme.cornerRadius)
    }

    @Test
    fun fontFamilyValidAndNormalized() {
        assertEquals("serif", validate(base("\"fontFamily\": \"serif\"")).skin.fontFamily)
        assertEquals(
            "sans-serif-medium",
            validate(base("\"fontFamily\": \"Sans-Serif-Medium\"")).skin.fontFamily,
        )
    }

    @Test
    fun fontFamilyInvalidFallsBackToDefault() {
        for (json in listOf(
            base("\"fontFamily\": \"\""),
            base("\"fontFamily\": \"   \""),
            base("\"fontFamily\": \"Comic Sans\""),
            base("\"fontFamily\": 123"),
        )) {
            val result = validate(json)
            assertNull(result.skin.fontFamily)
            assertTrue(
                result.issues.any {
                    it.path == "fontFamily" && it.kind == SkinIssue.Kind.FIELD_FALLBACK
                },
            )
        }
    }

    @Test
    fun fontFamilyDefaultAliasMeansUnspecifiedWithoutIssue() {
        val result = validate(base("\"fontFamily\": \"default\""))
        assertNull(result.skin.fontFamily)
        assertTrue(result.issues.none { it.path == "fontFamily" })
    }

    @Test
    fun fontFamilyPassesThroughSnapshot() {
        assertEquals("monospace", build(base("\"fontFamily\": \"monospace\"")).fontFamily)
        assertNull(build(base("")).fontFamily)
    }
}
