package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 样式几何解析（审计 A 项：阴影、描边、下边缘、圆角曲线、可视区域）。 */
class KeyGeometryStyleTest {

    private fun entry(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test
    fun peerShadowStyleParsesWithMultipliedAlpha() {
        // 对端深色 systemButton 实测：normalShadowColor #00000050，opacity 0.08。
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"normalShadowColor": "#00000050",
                "highlightShadowColor": "#00000050",
                "shadowOpacity": 0.08, "shadowRadius": 4,
                "shadowOffset": {"x": 0, "y": 5}}}""",
        ))
        assertTrue(warnings.isEmpty())
        // 80 × 0.08 = 6.4 → 6。
        assertEquals(0x06000000.toInt(), geometry?.shadow?.normal?.effectiveColor())
        assertEquals(4f, geometry?.shadow?.normal?.radiusDp)
        assertEquals(5f, geometry?.shadow?.normal?.offsetYDp)
    }

    @Test
    fun shadowWithoutColorIsAbsent() {
        val (geometry, _) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"shadowOpacity": 0.5, "shadowRadius": 4}}""",
        ))
        assertNull(geometry?.shadow)
    }

    @Test
    fun flatPeerStyleFieldsParseWithoutGeometryWrapper() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"face": "#FFFFFF", "borderSize": 1, "cornerRadius": 7,
                "normalBorderColor": "#FFFFFF26",
                "highlightBorderColor": "#FFFFFF40",
                "normalLowerEdgeColor": "#1E1E1E",
                "highlightLowerEdgeColor": "#1D1D1D",
                "cornerCurve": "continuous",
                "bounds": {"alignment": "right", "width": "2/3"},
                "animations": ["ButtonScaleAnimation"]}""",
        ))
        assertTrue(warnings.isEmpty())
        assertEquals(1f, geometry?.borderWidthDp)
        assertEquals(7f, geometry?.cornerRadiusDp)
        assertEquals(KeyCornerCurve.CONTINUOUS, geometry?.cornerCurve)
        assertEquals(0x26FFFFFF.toInt(), geometry?.normalBorderColor)
        assertEquals(0x40FFFFFF.toInt(), geometry?.highlightBorderColor)
        assertEquals(0xFF1E1E1E.toInt(), geometry?.normalLowerEdgeColor)
        assertEquals(0xFF1D1D1D.toInt(), geometry?.highlightLowerEdgeColor)
        assertEquals(2f / 3f, geometry?.bounds?.widthFraction)
        assertEquals(KeyBoundsHorizontal.RIGHT, geometry?.bounds?.horizontal)
        assertEquals(KeyBoundsVertical.CENTER, geometry?.bounds?.vertical)
        assertEquals(listOf("ButtonScaleAnimation"), geometry?.animationNames)
    }

    @Test
    fun invalidGeometryFieldsFallBackIndividually() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"borderSize": 99, "cornerCurve": "squircle",
                "normalBorderColor": "not-a-color"}}""",
        ))
        assertNull(geometry?.borderWidthDp)
        assertNull(geometry?.cornerCurve)
        assertNull(geometry?.normalBorderColor)
        assertTrue(warnings.contains("style_geometry_borderSize_invalid"))
        assertTrue(warnings.contains("style_geometry_corner_curve_invalid"))
        assertTrue(warnings.contains("style_geometry_normalBorderColor_invalid"))
    }

    @Test
    fun geometryKeysDoNotWarnAsUnknownRoles() {
        val (roles, warnings) = KeyStyleParser.parseRoles(
            "s",
            entry("""{"face": "#FFFFFF", "geometry": {}, "animations": []}"""),
        )
        assertEquals(mapOf(KeyStyleRole.FACE to "#FFFFFF"), roles)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun boundsWidthOutOfRangeIsDropped() {
        val (geometry, warnings) = KeyStyleGeometryParser.parseEntry(entry(
            """{"geometry": {"bounds": {"alignment": "left", "width": "3/2"}}}""",
        ))
        assertNull(geometry?.bounds?.widthFraction)
        assertEquals(KeyBoundsHorizontal.LEFT, geometry?.bounds?.horizontal)
        assertTrue(warnings.contains("style_geometry_bounds_width_invalid"))
    }
}
