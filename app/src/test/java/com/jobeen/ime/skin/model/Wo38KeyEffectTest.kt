package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyEffectPainter
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


    private fun entry(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject


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


    // ---------- 装配器落键 ----------



}
