package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按键渐变与对端颜色解析（审计 A 项，取值来自对端参考皮肤实测）。 */
class KeyGeometryGradientTest {

    @Test
    fun peerEightDigitColorReadsAlphaFromTail() {
        // 对端深色 systemButton 实测 #D1D1D624：alpha 在末尾。
        assertEquals(0x24D1D1D6.toInt(), parsePeerColor("#D1D1D624"))
        // 对端深色渐变实测 #78787D80 / #5C5C6047。
        assertEquals(0x8078787D.toInt(), parsePeerColor("#78787D80"))
        assertEquals(0x475C5C60.toInt(), parsePeerColor("#5C5C6047"))
    }

    @Test
    fun sixDigitColorIsOpaque() {
        assertEquals(0xFF23C891.toInt(), parsePeerColor("#23C891"))
    }

    @Test
    fun invalidPeerColorsReturnNull() {
        assertNull(parsePeerColor("23C891"))
        assertNull(parsePeerColor("#12345"))
        assertNull(parsePeerColor("#GGGGGG"))
        assertNull(parsePeerColor("#123456789"))
    }

    @Test
    fun gradientArrayWithLocationsParses() {
        val geometry = Json.parseToJsonElement(
            """{"normalColor": ["#78787D80", "#5C5C6047"],
                "highlightColor": ["#96969BF0", "#7C7C80E8"],
                "colorLocation": [0, 1], "colorGradientType": "axial",
                "colorStartPoint": {"x": 0, "y": 0},
                "colorEndPoint": {"x": 0, "y": 1}}""",
        ) as kotlinx.serialization.json.JsonObject
        val (normal, highlight, warnings) = KeyGradientParser.parseFaces(geometry)
        assertTrue(warnings.isEmpty())
        assertEquals(
            listOf(0x8078787D.toInt(), 0x475C5C60.toInt()),
            normal?.colors,
        )
        assertEquals(listOf(0f, 1f), normal?.locations)
        assertEquals(KeyGradientType.LINEAR, normal?.type)
        assertEquals(KeyGradientPoint(0f, 0f), normal?.startPoint)
        assertEquals(KeyGradientPoint(0f, 1f), normal?.endPoint)
        assertEquals(
            listOf(0xF096969B.toInt(), 0xE87C7C80.toInt()),
            highlight?.colors,
        )
    }

    @Test
    fun singleColorIsNotAGradient() {
        val geometry = Json.parseToJsonElement(
            """{"normalColor": "#23C891", "highlightColor": "#FCFCFE"}""",
        ) as kotlinx.serialization.json.JsonObject
        val (normal, highlight, warnings) = KeyGradientParser.parseFaces(geometry)
        assertNull(normal)
        assertNull(highlight)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun gradientTypeNamesMap() {
        for ((name, expected) in mapOf(
            "radial" to KeyGradientType.RADIAL,
            "conic" to KeyGradientType.CONIC,
            "axial" to KeyGradientType.LINEAR,
        )) {
            val geometry = Json.parseToJsonElement(
                """{"normalColor": ["#000000", "#FFFFFF"],
                    "colorGradientType": "$name"}""",
            ) as kotlinx.serialization.json.JsonObject
            val (normal, _, _) = KeyGradientParser.parseFaces(geometry)
            assertEquals(expected, normal?.type)
        }
    }

    @Test
    fun locationMismatchFallsBackToEvenDistribution() {
        val geometry = Json.parseToJsonElement(
            """{"normalColor": ["#000000", "#FFFFFF"],
                "colorLocation": [0, 0.5, 1]}""",
        ) as kotlinx.serialization.json.JsonObject
        val (normal, _, warnings) = KeyGradientParser.parseFaces(geometry)
        assertNull(normal?.locations)
        assertTrue(warnings.contains("gradient_location_mismatch"))
    }

    @Test
    fun invalidGradientColorEntryIsDroppedWithWarning() {
        val geometry = Json.parseToJsonElement(
            """{"normalColor": ["#000000", "not-a-color", "#FFFFFF"]}""",
        ) as kotlinx.serialization.json.JsonObject
        val (normal, _, warnings) = KeyGradientParser.parseFaces(geometry)
        assertEquals(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), normal?.colors)
        assertTrue(warnings.contains("gradient_normalColor_invalid"))
    }

    @Test
    fun sizeFractionFormsParse() {
        assertEquals(2.0 / 3.0, SizeFraction.parse(
            Json.parseToJsonElement(""""2/3""""),
        )!!, 1e-9)
        assertEquals(0.15, SizeFraction.parse(
            Json.parseToJsonElement("""{"percentage": 0.15}"""),
        )!!, 1e-9)
        assertEquals(0.2, SizeFraction.parse(
            Json.parseToJsonElement("""0.2"""),
        )!!, 1e-9)
    }

    @Test
    fun sizeFractionInvalidFormsReturnNull() {
        assertNull(SizeFraction.parse(Json.parseToJsonElement(""""1/0"""")))
        assertNull(SizeFraction.parse(Json.parseToJsonElement(""""a/b"""")))
        assertNull(SizeFraction.parse(Json.parseToJsonElement(""""1/2/3"""")))
        assertNull(SizeFraction.parse(Json.parseToJsonElement("""true""")))
    }
}
