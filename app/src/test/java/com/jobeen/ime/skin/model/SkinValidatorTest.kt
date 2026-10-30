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
        // 2026-10-29 双模式硬要求：夹具默认补深色板（被测行为与深色板
        // 无关时用此默认；validate() 保持原始输入，供双模式规则用例直测）
        val result = validate(withDefaultDark(json), currentVersionCode)
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    /** 夹具默认深色板：已有 stylesDark 时不动；否则在根对象末尾补一色深色板 */
    private fun withDefaultDark(json: String): String {
        if (json.contains("\"stylesDark\"")) return json
        val trimmed = json.trimEnd()
        require(trimmed.endsWith("}")) { "fixture JSON must end with }" }
        return trimmed.dropLast(1) + """, "stylesDark": {"keyBackground": "#FF222222"}}"""
    }

    @Test
    fun lightOnlySkinRejectedMissingDarkMode() {
        // 2026-10-29 双模式硬要求：浅色板齐全但深色段整体缺失即整包拒绝
        val result = validate(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}}""",
        )
        assertTrue(result is SkinValidator.Result.Rejected)
        assertEquals(
            "skin_missing_dark_mode",
            (result as SkinValidator.Result.Rejected).reason,
        )
    }

    @Test
    fun declaredPartialDarkPassesWithFieldFallback() {
        // 仅整段缺失拒包：已声明但只有一色的深色段仍通过，其余叶子逐字段回填浅色板
        val result = valid(
            """{"formatVersion": 3,
                "styles": {"keyBackground": "#FFFFFFFF", "keyText": "#FF000000"},
                "stylesDark": {"keyBackground": "#FF222222"}}""",
        )
        assertEquals(0xFF222222.toInt(), result.skin.darkStyles!!.colors[StyleField.KEY_BACKGROUND])
        assertEquals(0xFF000000.toInt(), result.skin.darkStyles!!.colors[StyleField.KEY_TEXT])
    }

    @Test
    fun candidateRadiusAndPredictionCloseParsed() {
        val result = valid(
            """
            {
              "formatVersion": 3,
              "minAppVersion": 143,
              "meta": {"id": "id-1", "name": "示例", "author": "User", "version": "1.0"},
              "candidateRadiusDp": 12,
              "predictionCloseBg": "#FF112233",
              "predictionCloseRadiusDp": 16,
              "styles": {"keyBackground": "#FFFFFFFF"}
            }
            """.trimIndent(),
        )
        assertEquals(12f, result.skin.candidateRadiusDp, 0.001f)
        assertEquals(0xFF112233.toInt(), result.skin.predictionCloseBg)
        assertEquals(16f, result.skin.predictionCloseRadiusDp ?: -1f, 0.001f)
    }

    @Test
    fun candidateRadiusAndPredictionCloseInvalidFallback() {
        val result = valid(
            """
            {
              "formatVersion": 3,
              "minAppVersion": 143,
              "meta": {"id": "id-1", "name": "示例", "author": "User", "version": "1.0"},
              "candidateRadiusDp": 99,
              "predictionCloseBg": "red",
              "predictionCloseRadiusDp": -1,
              "styles": {"keyBackground": "#FFFFFFFF"}
            }
            """.trimIndent(),
        )
        assertEquals(6f, result.skin.candidateRadiusDp, 0.001f)
        assertNull(result.skin.predictionCloseBg)
        assertNull(result.skin.predictionCloseRadiusDp)
        assertTrue(
            result.issues.any { it.path == "candidateRadiusDp" },
        )
        assertTrue(
            result.issues.any { it.path == "predictionCloseBg" },
        )
        assertTrue(
            result.issues.any { it.path == "predictionCloseRadiusDp" },
        )
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
        // B3 起 layoutId 由快照工厂解析生效：保留不应用段全部退役
        val retained = result.issues.filter { it.kind == SkinIssue.Kind.RETAINED_INACTIVE }
            .map { it.path }.toSet()
        assertEquals(emptySet<String>(), retained)
        // 样例中的键覆写被强类型解析（KEY_Q 的 text 绑定生效）
        assertEquals(1, skin.keyOverrides.size)
        assertTrue(skin.keyRejections.isEmpty())
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
        // 144 的 App 上则可过结构门（带一色，避开无配色拒绝）
        valid("""{"formatVersion": 3, "minAppVersion": 144, "styles": {"keyBackground": "#FFFFFFFF"}}""", 144)
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
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"cornerRadius": 41, "keyHMargin": 7.5}}""",
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
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"background": {"type": "asset", "asset": "nope"}}}""",
        )
        assertNull(result.skin.appearance.background)
        assertTrue(result.issues.any { it.kind == SkinIssue.Kind.RESOURCE_FALLBACK })
    }

    @Test
    fun backgroundWrongTypeFallsBackToNone() {
        val result = valid(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"background": {"type": "asset", "asset": "tap_main"}}}""",
        )
        assertNull(result.skin.appearance.background)
    }

    @Test
    fun blurOutOfRangeFallsBackToZeroButKeepsBackground() {
        val result = valid(
            """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"background":
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
        val result = valid("""{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "meta": {"name": "$longName"}}""")
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

    // ---- 2026-10-27 校验硬化：无配色主题拒绝 ----

    @Test
    fun noColorSchemeRejected() {
        val result = validate("""{"formatVersion": 3, "styles": {}}""")
        assertTrue(result is SkinValidator.Result.Rejected)
        assertEquals("no_color_scheme", (result as SkinValidator.Result.Rejected).reason)
        val noStyles = validate("""{"formatVersion": 3}""")
        assertTrue(noStyles is SkinValidator.Result.Rejected)
        assertEquals("no_color_scheme", (noStyles as SkinValidator.Result.Rejected).reason)
    }

    @Test
    fun allColorsInvalidRejectedAsNoScheme() {
        val result = validate(
            """{"formatVersion": 3, "styles": {"keyBackground": "red", "keyText": "not-a-color"}}""",
        )
        assertTrue(result is SkinValidator.Result.Rejected)
        assertEquals("no_color_scheme", (result as SkinValidator.Result.Rejected).reason)
    }

    @Test
    fun surfaceStyleAloneIsNotAColorScheme() {
        val result = validate(
            """{"formatVersion": 3, "styles": {"surfaceStyle": "glass"}}""",
        )
        assertTrue(result is SkinValidator.Result.Rejected)
        assertEquals("no_color_scheme", (result as SkinValidator.Result.Rejected).reason)
    }

    // ---- 2026-10-27 并入项：颜色派生（校验层） ----

    @Test
    fun derivationOnlySkinValidWithDerivedColors() {
        val result = valid(
            """
            {"formatVersion": 3, "derivation": {
              "face": "#FFFFFFFF", "pressed": "#FFE0E0E0", "bed": "#FFF2F2F2",
              "ink": "#FF3A3A3C", "accent": "#FF9C824A", "hint": "#FF8A8A8E"}}
            """.trimIndent(),
        )
        // 派生展开：键面 = face，工具栏激活 = accent，候选分隔 = bed+ink 混合
        assertEquals(0xFFFFFFFF.toInt(), result.skin.styles.colors.getValue(StyleField.KEY_BACKGROUND))
        assertEquals(0xFF9C824A.toInt(), result.skin.styles.colors.getValue(StyleField.PANEL_TOOLBAR_ACTIVED))
        assertTrue(
            result.issues.any {
                it.kind == SkinIssue.Kind.INFO && it.path == "derivation" &&
                    it.detail.startsWith("derivation_applied:")
            },
        )
    }

    @Test
    fun explicitStyleWinsOverDerivation() {
        val result = valid(
            """
            {"formatVersion": 3,
             "styles": {"keyBackground": "#FF112233"},
             "derivation": {
              "face": "#FFFFFFFF", "pressed": "#FFE0E0E0", "bed": "#FFF2F2F2",
              "ink": "#FF3A3A3C", "accent": "#FF9C824A", "hint": "#FF8A8A8E"}}
            """.trimIndent(),
        )
        assertEquals(0xFF112233.toInt(), result.skin.styles.colors.getValue(StyleField.KEY_BACKGROUND))
        // 未明示字段仍由派生补齐
        assertEquals(0xFF3A3A3C.toInt(), result.skin.styles.colors.getValue(StyleField.KEY_TEXT))
    }

    @Test
    fun partialDerivationIgnoredWithPerFieldIssues() {
        val result = validate(
            """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
             "stylesDark": {"keyBackground": "#FF222222"},
             "derivation": {"face": "#FFFFFFFF", "pressed": "bogus", "bed": "#FFF2F2F2"}}
            """.trimIndent(),
        )
        assertTrue(result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        // 部分派生整体作废：未明示字段不由派生补齐
        assertFalse(skin.styles.colors.containsKey(StyleField.KEY_TEXT))
        assertTrue(
            result.issues.any {
                it.path == "derivation.pressed" && it.kind == SkinIssue.Kind.FIELD_FALLBACK
            },
        )
    }

    @Test
    fun derivationNotObjectFallsBack() {
        val result = valid(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "derivation": "white"}""",
        )
        assertTrue(
            result.issues.any {
                it.path == "derivation" && it.kind == SkinIssue.Kind.FIELD_FALLBACK
            },
        )
    }

    // ---- 2026-10-27 并入项：样式引用（校验层） ----

    @Test
    fun keyStylesTableParsedAndStyleRefKept() {
        val result = valid(
            """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
             "keyStyles": {"gold": {"face": "accentKeyBackground", "text": "#FF000000"}},
             "keyMappings": {"KEY_Q": {"label": "Q", "style": "gold",
               "tap": {"type": "text", "value": "q"}}}}
            """.trimIndent(),
        )
        assertEquals(
            mapOf(KeyStyleRole.FACE to "accentKeyBackground", KeyStyleRole.TEXT to "#FF000000"),
            result.skin.keyStyles["gold"],
        )
        val override = result.skin.keyOverrides["KEY_Q"]
        assertNotNull(override)
        assertEquals("gold", override!!.styleRef)
    }

    @Test
    fun keyStylesNotObjectFallsBack() {
        val result = valid(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "keyStyles": "gold"}""",
        )
        assertTrue(
            result.issues.any {
                it.path == "keyStyles" && it.kind == SkinIssue.Kind.FIELD_FALLBACK
            },
        )
    }

    @Test
    fun keyMappingStyleWarningRecordedNotRejected() {
        val result = valid(
            """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
             "keyMappings": {"KEY_Q": {"label": "Q", "style": "",
               "tap": {"type": "text", "value": "q"}}}}
            """.trimIndent(),
        )
        // 空 style 字段记警告但条目仍有效（style 字段级回退）
        assertTrue(result.skin.keyOverrides.containsKey("KEY_Q"))
        assertTrue(
            result.issues.any {
                it.path.startsWith("keyMappings.KEY_Q") && it.kind == SkinIssue.Kind.FIELD_FALLBACK
            },
        )
    }
}
