package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyEffectPainter
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO38 按键特效参数（对端 Mie v3.3.9 KeyEffects 口径）：
 * 内阴影／渐变／3D 底边厚度／按压下沉／涟漪。
 * 已实现项（渐变、外阴影、缩放）只做回归锁定；新补项（内阴影、
 * 底边厚度、下沉、涟漪参数）覆盖合法／非法／越界回退与零变化。
 */
class Wo38KeyEffectTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun entry(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun validatedSkin(json: String): ValidatedSkin {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    // ---------- 内阴影 ----------

    @Test
    fun innerShadowFullParsesBothLayers() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalInnerShadowColor": "#00000050",
                "highlightInnerShadowColor": "#000000A0",
                "innerShadowOpacity": 0.5, "innerShadowRadius": 4,
                "innerShadowOffset": {"x": 0, "y": 2}}}""",
        ))
        assertTrue(warnings.isEmpty())
        // 80 × 0.5 = 40；160 × 0.5 = 80。
        assertEquals(0x28000000.toInt(), geometry?.innerShadow?.normal?.effectiveColor())
        assertEquals(0x50000000.toInt(), geometry?.innerShadow?.pressed?.effectiveColor())
        assertEquals(4f, geometry?.innerShadow?.normal?.radiusDp)
        assertEquals(2f, geometry?.innerShadow?.normal?.offsetYDp)
    }

    @Test
    fun innerShadowDefaultsMatchMie() {
        // 只给颜色时半径 4、偏移 (0,2)＝对端缺省。
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalInnerShadowColor": "#55000000"}}""",
        ))
        assertTrue(warnings.isEmpty())
        assertEquals(4f, geometry?.innerShadow?.normal?.radiusDp)
        assertEquals(0f, geometry?.innerShadow?.normal?.offsetXDp)
        assertEquals(2f, geometry?.innerShadow?.normal?.offsetYDp)
        assertEquals(1f, geometry?.innerShadow?.normal?.opacity)
        assertNull(geometry?.innerShadow?.pressed)
    }

    @Test
    fun innerShadowWithoutColorIsAbsent() {
        val (geometry, _) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"innerShadowOpacity": 0.5, "innerShadowRadius": 4}}""",
        ))
        assertNull(geometry?.innerShadow)
    }

    @Test
    fun innerShadowInvalidColorDropsLayer() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalInnerShadowColor": "not-a-color",
                "highlightInnerShadowColor": "#00000055"}}""",
        ))
        assertTrue(warnings.isNotEmpty())
        assertNull(geometry?.innerShadow?.normal)
        assertEquals(0x55000000.toInt(), geometry?.innerShadow?.pressed?.color)
    }

    @Test
    fun innerShadowOutOfRangeFallsBackToDefaults() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalInnerShadowColor": "#55000000",
                "innerShadowRadius": 99, "innerShadowOpacity": 2,
                "innerShadowOffset": {"x": 99, "y": -99}}}""",
        ))
        assertTrue(warnings.size >= 3)
        assertEquals(4f, geometry?.innerShadow?.normal?.radiusDp)
        assertEquals(1f, geometry?.innerShadow?.normal?.opacity)
        assertEquals(0f, geometry?.innerShadow?.normal?.offsetXDp)
        assertEquals(2f, geometry?.innerShadow?.normal?.offsetYDp)
    }

    @Test
    fun innerShadowFlatFieldsParseWithoutGeometryWrapper() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"face": "#FFFFFF", "normalInnerShadowColor": "#00000055"}""",
        ))
        assertTrue(warnings.isEmpty())
        assertEquals(0x55000000.toInt(), geometry?.innerShadow?.normal?.color)
    }

    // ---------- 渐变（已实现，回归锁定） ----------

    @Test
    fun gradientTwoColorLinearParses() {
        val (normal, highlight, warnings) = KeyGradientParser.parseFaces(entry(
            """{"normalColor": ["#FFFFFFFF", "#FF000000"],
                "highlightColor": "#FF888888",
                "colorLocation": [0, 1], "colorGradientType": "axial",
                "colorStartPoint": {"x": 0, "y": 0},
                "colorEndPoint": {"x": 0, "y": 1}}""",
        ))
        assertTrue(warnings.isEmpty())
        assertEquals(2, normal?.colors?.size)
        assertEquals(KeyGradientType.LINEAR, normal?.type)
        assertEquals(listOf(0f, 1f), normal?.locations)
        assertNull(highlight)
    }

    @Test
    fun gradientRadialTypeParses() {
        val (normal, _, warnings) = KeyGradientParser.parseFaces(entry(
            """{"normalColor": ["#FFFFFFFF", "#FF000000"],
                "colorGradientType": "radial"}""",
        ))
        assertTrue(warnings.isEmpty())
        assertEquals(KeyGradientType.RADIAL, normal?.type)
    }

    @Test
    fun gradientSingleColorIsNull() {
        val (normal, highlight, _) = KeyGradientParser.parseFaces(entry(
            """{"normalColor": "#FFFFFFFF", "highlightColor": "#FF000000"}""",
        ))
        assertNull(normal)
        assertNull(highlight)
    }

    @Test
    fun gradientBadColorDroppedBelowTwoIsNull() {
        val (normal, _, warnings) = KeyGradientParser.parseFaces(entry(
            """{"normalColor": ["#FFFFFFFF", "nope"]}""",
        ))
        assertTrue(warnings.isNotEmpty())
        assertNull(normal)
    }

    // ---------- 3D 底边厚度 ----------

    @Test
    fun lowerEdgeHeightParses() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalLowerEdgeColor": "#33000000",
                "lowerEdgeHeight": 3}}""",
        ))
        assertTrue(warnings.isEmpty())
        assertEquals(3f, geometry?.lowerEdgeHeightDp)
    }

    @Test
    fun lowerEdgeHeightOutOfRangeIsNull() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalLowerEdgeColor": "#33000000",
                "lowerEdgeHeight": 99}}""",
        ))
        assertTrue(warnings.isNotEmpty())
        assertNull(geometry?.lowerEdgeHeightDp)
    }

    @Test
    fun lowerEdgeHeightAbsentIsNullSoRenderKeepsOneDp() {
        val (geometry, _) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalLowerEdgeColor": "#33000000"}}""",
        ))
        assertNull(geometry?.lowerEdgeHeightDp)
    }

    // ---------- 按压下沉 ----------

    @Test
    fun sinkParsesAndKeepsOtherFields() {
        val (spec, issues) = KeyAnimationParser.parse(
            Json.parseToJsonElement(
                """{"enabled": true, "pressedScale": 0.95,
                   "durationMs": 120, "pressedSinkDp": 2}""",
            ),
            "keyAnimation",
        )
        assertTrue(issues.isEmpty())
        assertEquals(2f, spec.pressedSinkDp)
        assertEquals(0.95f, spec.pressedScale)
    }

    @Test
    fun sinkAliasPressSinkAccepted() {
        val (spec, issues) = KeyAnimationParser.parse(
            Json.parseToJsonElement("""{"enabled": true, "pressSink": 3}"""),
            "keyAnimation",
        )
        assertTrue(issues.isEmpty())
        assertEquals(3f, spec.pressedSinkDp)
    }

    @Test
    fun sinkPrimaryFieldWinsOverAlias() {
        val (spec, _) = KeyAnimationParser.parse(
            Json.parseToJsonElement(
                """{"enabled": true, "pressedSinkDp": 2, "pressSink": 5}""",
            ),
            "keyAnimation",
        )
        assertEquals(2f, spec.pressedSinkDp)
    }

    @Test
    fun sinkOutOfRangeFallsBackToZero() {
        val (over, overIssues) = KeyAnimationParser.parse(
            Json.parseToJsonElement("""{"enabled": true, "pressedSinkDp": 9}"""),
            "keyAnimation",
        )
        assertEquals(0f, over.pressedSinkDp)
        assertTrue(overIssues.isNotEmpty())
        val (negative, negativeIssues) = KeyAnimationParser.parse(
            Json.parseToJsonElement("""{"enabled": true, "pressedSinkDp": -1}"""),
            "keyAnimation",
        )
        assertEquals(0f, negative.pressedSinkDp)
        assertTrue(negativeIssues.isNotEmpty())
    }

    @Test
    fun sinkMissingDefaultsToZero() {
        val (spec, issues) = KeyAnimationParser.parse(null, "keyAnimation")
        assertEquals(0f, spec.pressedSinkDp)
        assertTrue(issues.isEmpty())
    }

    // ---------- 涟漪 ----------

    @Test
    fun rippleFullParses() {
        val skin = validatedSkin(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"},
               "keyRipple": {"color": "#80FF0000", "durationMs": 400}}""",
        )
        assertEquals(0x80FF0000.toInt(), skin.keyRipple.color)
        assertEquals(400, skin.keyRipple.durationMs)
    }

    @Test
    fun rippleMissingDefaultsToHardcodedValues() {
        val (spec, issues) = KeyRippleParser.parse(null)
        assertEquals(KeyRippleSpec.DEFAULT_COLOR, spec.color)
        assertEquals(0xB4B4E6FF.toInt(), spec.color)
        assertEquals(650, spec.durationMs)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun rippleInvalidFieldsFallBackIndividually() {
        val (spec, issues) = KeyRippleParser.parse(
            Json.parseToJsonElement("""{"color": "red", "durationMs": 50}"""),
        )
        assertEquals(KeyRippleSpec.DEFAULT_COLOR, spec.color)
        assertEquals(KeyRippleSpec.DEFAULT_DURATION_MS, spec.durationMs)
        assertEquals(2, issues.size)
    }

    @Test
    fun rippleNotObjectFallsBack() {
        val (spec, issues) = KeyRippleParser.parse(
            Json.parseToJsonElement(""""fast""""),
        )
        assertEquals(KeyRippleSpec.DEFAULT_COLOR, spec.color)
        assertEquals(KeyRippleSpec.DEFAULT_DURATION_MS, spec.durationMs)
        assertEquals(1, issues.size)
    }

    @Test
    fun ripplePassesThroughToSnapshot() {
        val json = """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
            "stylesDark": {"keyBackground": "#FF222222"},
            "keyRipple": {"color": "#80FF0000", "durationMs": 400}}"""
        val scheme = KeyboardColors.ColorScheme(
            keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
            specialKeyBackground = 200, specialKeyPressed = 201,
            specialKeyBorderStroke = 202,
            accentKeyBackground = 300, accentKeyPressed = 301,
            accentKeyBorderStroke = 302,
            keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
            background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 600, toolbarText = 601, toolbarActived = 602,
                toolbarIcon = 603, candidateBackground = 604, candidateText = 605,
                candidateIndex = 606, candidateDivider = 607, toolbarPressed = 608,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 700, textColor = 701, secondaryTextColor = 702,
            ),
            toastBackground = 999, toastText = 998,
        )
        val snapshot = SkinSnapshotBuilder.build(
            validatedSkin(json), SkinGenRef("skin-1", "h".repeat(64), 1),
            scheme, MemoryTier.NORMAL, { "a".repeat(64) }, 1000, 400,
        )
        assertEquals(0x80FF0000.toInt(), snapshot.keyRipple.color)
        assertEquals(400, snapshot.keyRipple.durationMs)
    }

    // ---------- painter 纯函数 ----------

    @Test
    fun innerStrokeWidthIsTwiceBlurWithOnePxFloor() {
        assertEquals(8f, KeyEffectPainter.innerStrokeWidthPx(4f))
        assertEquals(1f, KeyEffectPainter.innerStrokeWidthPx(0f))
        assertEquals(1f, KeyEffectPainter.innerStrokeWidthPx(0.2f))
    }

    @Test
    fun innerBlurThresholdMatchesMie() {
        assertFalse(KeyEffectPainter.innerNeedsBlur(0.5f))
        assertTrue(KeyEffectPainter.innerNeedsBlur(0.6f))
    }

    // ---------- 无参数皮肤零变化 ----------

    @Test
    fun noParamSkinIsZeroChange() {
        val skin = validatedSkin(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"}}""",
        )
        assertFalse(skin.keyShadow.enabled)
        assertFalse(skin.keyAnimation.enabled)
        assertEquals(0f, skin.keyAnimation.pressedSinkDp)
        assertEquals(KeyRippleSpec.DEFAULT_COLOR, skin.keyRipple.color)
        assertEquals(KeyRippleSpec.DEFAULT_DURATION_MS, skin.keyRipple.durationMs)
        val (geometry, _) = KeyStyleGeometryParser.parseEntry(entry("""{"face": "#FFFFFF"}"""))
        assertNull(geometry)
    }

    // ---------- 装配器落键 ----------

    private fun applierSnapshot(
        keyGeometries: Map<String, KeyStyleGeometry> = emptyMap(),
        keyAnimation: KeyAnimationSpec = KeyAnimationSpec(),
    ): SkinSnapshot {
        val scheme = KeyboardColors.ColorScheme(
            keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
            specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
            accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
            keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
            background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 20, toolbarText = 21, toolbarActived = 22,
                toolbarIcon = 23, candidateBackground = 24, candidateText = 25,
                candidateIndex = 26, candidateDivider = 27, toolbarPressed = 28,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 30, textColor = 31, secondaryTextColor = 32,
            ),
            toastBackground = 40, toastText = 41,
        )
        return SkinSnapshot(
            ref = SkinGenRef("skin-1", "h".repeat(64), 1),
            name = "test",
            colorScheme = scheme,
            geometryPresence = emptySet(),
            effectiveGeometry = emptyMap(),
            background = null,
            degradations = emptySet(),
            layoutId = null,
            keyAnimation = keyAnimation,
            keyGeometries = keyGeometries,
        )
    }

    @Test
    fun applierStampsInnerShadowAndEdgeDepthOnStyledKeyOnly() {
        val geometry = KeyStyleGeometry(
            normalLowerEdgeColor = 0x33000000,
            lowerEdgeHeightDp = 3f,
            innerShadow = KeyPerStyleShadow(
                normal = KeyShadowLayer(0x55000000, 1f, 4f, 0f, 2f),
                pressed = KeyShadowLayer(0x55000000, 1f, 4f, 0f, 2f),
            ),
        )
        val factory = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val result = KeyOverrideApplier.apply(
            "Qwerty", factory,
            applierSnapshot(
                keyGeometries = mapOf("geo" to geometry),
            ).copy(
                keyOverrides = mapOf("KEY_Q" to KeyOverride(styleRef = "geo")),
            ),
            emptyMap(),
        )
        val q = result.flatten().first { it.id == "KEY_Q" }.appearance
        assertEquals(geometry.innerShadow, q.skinInnerShadow)
        assertEquals(3f, q.skinLowerEdgeHeightDp)
        // 未配键不携带（出厂外观恒为 null）。
        val w = result.flatten().first { it.id == "KEY_W" }.appearance
        assertNull(w.skinInnerShadow)
        assertNull(w.skinLowerEdgeHeightDp)
    }

    @Test
    fun applierStampsSinkThroughKeyAnimation() {
        val spec = KeyAnimationSpec(enabled = true, pressedScale = 0.95f, pressedSinkDp = 2f)
        val factory = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val result = KeyOverrideApplier.apply(
            "Qwerty", factory,
            applierSnapshot(keyAnimation = spec),
            emptyMap(),
        )
        val q = result.flatten().first { it.id == "KEY_Q" }.appearance
        assertEquals(2f, q.skinKeyAnimation?.pressedSinkDp)
    }
}
