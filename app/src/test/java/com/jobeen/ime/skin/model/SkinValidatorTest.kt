package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3 校验器（定稿九章）：整包拒绝门、逐项回填、保留不应用、资源回退。 */
class SkinValidatorTest {

    private val fallbacks = GeometryFallbacks(
        cornerRadius = 12f, keyHMargin = 6f, keyVMargin = 5f, bottomInsetDp = 10f,
    )
    private val assetKinds = mapOf(
        "bg_main" to AssetKind.IMAGE,
        "tap_main" to AssetKind.AUDIO,
    )

    private fun tree(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun validate(
        json: String,
        currentVersionCode: Int = 143,
    ): SkinValidator.Result = SkinValidator.validate(
        tree(json), currentVersionCode, fallbacks, { assetKinds[it] },
    )

    private fun valid(json: String, currentVersionCode: Int = 143): SkinValidator.Result.Valid {
        val result = validate(json, currentVersionCode)
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    @Test
    fun planSampleValid() {
        val result = valid(
            """
            {
              "formatVersion": 3,
              "minAppVersion": 143,
              "meta": {"id": "id-1", "name": "示例", "author": "User", "version": "1.0"},
              "layoutId": "layout_custom_01",
              "appearance": {
                "background": {"type": "asset", "asset": "bg_main", "blurRadius": 15, "overlayAlpha": 0.2},
                "cornerRadius": 8.0, "keyHMargin": 5.0, "keyVMargin": 8.0, "bottomInsetDp": 16.0
              },
              "styles": {"keyBackground": "#FFFFFFFF", "panel": {"candidateText": "#FF141414"}},
              "keyMappings": {"KEY_Q": {"label": "Q", "tap": {"type": "text", "value": "q"}}},
              "audio": {"tap": "tap_main"},
              "unknownNode": {"x": [1, 2, 3]}
            }
            """.trimIndent(),
        )
        val skin = result.skin
        assertEquals("示例", skin.metaName)
        assertEquals("layout_custom_01", skin.layoutId)
        assertEquals(GeometryField.entries.toSet(), skin.appearance.presence)
        assertEquals(8f, skin.appearance.effectiveGeometry.getValue(GeometryField.CORNER_RADIUS))
        assertEquals(16f, skin.appearance.effectiveGeometry.getValue(GeometryField.BOTTOM_INSET))
        val background = skin.appearance.background
        assertNotNull(background)
        assertEquals(15f, background!!.blurRadius)
        assertEquals(0.2f, background.overlayAlpha)
        assertEquals(
            0xFFFFFFFF.toInt(),
            skin.styles.colors.getValue(StyleField.KEY_BACKGROUND),
        )
        assertEquals(
            0xFF141414.toInt(),
            skin.styles.colors.getValue(StyleField.PANEL_CANDIDATE_TEXT),
        )
        // 保留不应用三段逐项明示
        val retained = result.issues.filter { it.kind == SkinIssue.Kind.RETAINED_INACTIVE }
            .map { it.path }.toSet()
        assertEquals(setOf("layoutId", "keyMappings", "audio"), retained)
        // 未知节点原样留在树里（round-trip）
        assertTrue(skin.tree.containsKey("unknownNode"))
    }

    @Test
    fun formatVersionTooNewRejected() {
        val result = validate("""{"formatVersion": 4}""")
        assertTrue(result is SkinValidator.Result.Rejected)
    }

    @Test
    fun minAppVersionTooHighRejected() {
        val result = validate("""{"formatVersion": 3, "minAppVersion": 144}""", 143)
        assertTrue(result is SkinValidator.Result.Rejected)
        // 144 的 App 上则可过结构门
        valid("""{"formatVersion": 3, "minAppVersion": 144}""", 144)
    }

    @Test
    fun badColorFallsBackOthersIntact() {
        val result = valid(
            """{"formatVersion": 3, "styles": {"keyBackground": "red", "keyText": "#FF000000"}}""",
        )
        assertFalse(result.skin.styles.colors.containsKey(StyleField.KEY_BACKGROUND))
        assertEquals(0xFF000000.toInt(), result.skin.styles.colors.getValue(StyleField.KEY_TEXT))
        assertTrue(
            result.issues.any {
                it.path == "styles.keyBackground" && it.kind == SkinIssue.Kind.FIELD_FALLBACK
            },
        )
    }

    @Test
    fun geometryOutOfRangeFallsBackToPreference() {
        val result = valid(
            """{"formatVersion": 3, "appearance": {"cornerRadius": 41, "keyHMargin": 7.5}}""",
        )
        val appearance = result.skin.appearance
        assertFalse(GeometryField.CORNER_RADIUS in appearance.presence)
        assertEquals(12f, appearance.effectiveGeometry.getValue(GeometryField.CORNER_RADIUS))
        assertTrue(GeometryField.KEY_H_MARGIN in appearance.presence)
        assertEquals(7.5f, appearance.effectiveGeometry.getValue(GeometryField.KEY_H_MARGIN))
    }

    @Test
    fun backgroundMissingAssetFallsBackToNone() {
        val result = valid(
            """{"formatVersion": 3, "appearance": {"background": {"type": "asset", "asset": "nope"}}}""",
        )
        assertNull(result.skin.appearance.background)
        assertTrue(result.issues.any { it.kind == SkinIssue.Kind.RESOURCE_FALLBACK })
    }

    @Test
    fun backgroundWrongTypeFallsBackToNone() {
        val result = valid(
            """{"formatVersion": 3, "appearance": {"background": {"type": "asset", "asset": "tap_main"}}}""",
        )
        assertNull(result.skin.appearance.background)
    }

    @Test
    fun blurOutOfRangeFallsBackToZeroButKeepsBackground() {
        val result = valid(
            """
            {"formatVersion": 3, "appearance": {"background":
              {"type": "asset", "asset": "bg_main", "blurRadius": 26, "overlayAlpha": 1.5}}}
            """.trimIndent(),
        )
        val background = result.skin.appearance.background
        assertNotNull(background)
        assertEquals(0f, background!!.blurRadius)
        assertEquals(0f, background.overlayAlpha)
    }

    @Test
    fun metaNameTooLongFallsBack() {
        val longName = "x".repeat(33)
        val result = valid("""{"formatVersion": 3, "meta": {"name": "$longName"}}""")
        assertEquals("", result.skin.metaName)
        assertTrue(result.issues.any { it.path == "meta.name" })
    }

    @Test
    fun jsonDepthExceededRejected() {
        var inner = "1"
        repeat(9) { inner = """{"a": $inner}""" }
        val result = validate("""{"formatVersion": 3, "deep": $inner}""")
        assertTrue(result is SkinValidator.Result.Rejected)
    }

    @Test
    fun jsonStringTooLongRejected() {
        val long = "x".repeat(4097)
        val result = validate("""{"formatVersion": 3, "meta": {"author": "$long"}}""")
        assertTrue(result is SkinValidator.Result.Rejected)
    }

    @Test
    fun keyMappingsTooManyRejected() {
        val entries = (1..513).joinToString(",") { "\"K$it\": {}" }
        val result = validate("""{"formatVersion": 3, "keyMappings": {$entries}}""")
        assertTrue(result is SkinValidator.Result.Rejected)
    }

    @Test
    fun structuralGarbageRejected() {
        assertTrue(validate("""{"meta": {}}""") is SkinValidator.Result.Rejected)
        assertTrue(validate("""{"formatVersion": "3"}""") is SkinValidator.Result.Rejected)
    }
}
