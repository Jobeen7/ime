package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 颜色派生（2026-10-27 并入项）：6 基础色 → 28 字段令牌展开。 */
class ColorDerivationTest {

    private val binLike = ColorDerivation.BaseColors(
        face = 0xFFFFFFFF.toInt(),
        pressed = 0xFFE0E0E0.toInt(),
        bed = 0xFFF2F2F2.toInt(),
        ink = 0xFF3A3A3C.toInt(),
        accent = 0xFF9C824A.toInt(),
        hint = 0xFF8A8A8E.toInt(),
    )

    private fun tree(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test
    fun parseRequiresAllSixFields() {
        assertEquals(
            binLike,
            ColorDerivation.parse(
                tree(
                    """{"face": "#FFFFFFFF", "pressed": "#FFE0E0E0", "bed": "#FFF2F2F2",
                       "ink": "#FF3A3A3C", "accent": "#FF9C824A", "hint": "#FF8A8A8E"}""",
                ),
            ),
        )
        // 缺一字段 → 整体 null（不部分派生）
        assertNull(
            ColorDerivation.parse(
                tree(
                    """{"face": "#FFFFFFFF", "pressed": "#FFE0E0E0", "bed": "#FFF2F2F2",
                       "ink": "#FF3A3A3C", "accent": "#FF9C824A"}""",
                ),
            ),
        )
        // 一字段非法 → 整体 null
        assertNull(
            ColorDerivation.parse(
                tree(
                    """{"face": "#FFFFFFFF", "pressed": "bogus", "bed": "#FFF2F2F2",
                       "ink": "#FF3A3A3C", "accent": "#FF9C824A", "hint": "#FF8A8A8E"}""",
                ),
            ),
        )
        assertNull(ColorDerivation.parse(null))
    }

    @Test
    fun expandCoversAllStyleFields() {
        val expanded = ColorDerivation.expand(binLike)
        assertEquals(StyleField.entries.toSet(), expanded.keys)
    }

    @Test
    fun expandBinLikeMapping() {
        val expanded = ColorDerivation.expand(binLike)
        assertEquals(0xFFFFFFFF.toInt(), expanded.getValue(StyleField.KEY_BACKGROUND))
        assertEquals(0xFFE0E0E0.toInt(), expanded.getValue(StyleField.KEY_PRESSED))
        assertEquals(0xFF9C824A.toInt(), expanded.getValue(StyleField.ACCENT_KEY_BACKGROUND))
        assertEquals(0xFF9C824A.toInt(), expanded.getValue(StyleField.PANEL_TOOLBAR_ACTIVED))
        assertEquals(0xFFF2F2F2.toInt(), expanded.getValue(StyleField.BACKGROUND))
        assertEquals(0xFF3A3A3C.toInt(), expanded.getValue(StyleField.KEY_TEXT))
        assertEquals(0xFF8A8A8E.toInt(), expanded.getValue(StyleField.ALT_TEXT))
        assertEquals(0xFF8A8A8E.toInt(), expanded.getValue(StyleField.PANEL_CANDIDATE_INDEX))
        // 金 accent 较暗 → accent 键文字取白
        assertEquals(0xFFFFFFFF.toInt(), expanded.getValue(StyleField.ACCENT_KEY_TEXT))
        // 描边 = face 加深
        assertEquals(
            ColorDerivation.darken(0xFFFFFFFF.toInt(), 0.88f),
            expanded.getValue(StyleField.KEY_BORDER_STROKE),
        )
    }

    @Test
    fun accentTextFollowsLuminance() {
        val lightAccent = binLike.copy(accent = 0xFFFFD700.toInt())
        val expanded = ColorDerivation.expand(lightAccent)
        // 亮金 accent → accent 键文字取 ink（深色可读）
        assertEquals(0xFF3A3A3C.toInt(), expanded.getValue(StyleField.ACCENT_KEY_TEXT))
    }

    @Test
    fun darkenAndMixKeepAlpha() {
        val darkened = ColorDerivation.darken(0x80FF0000.toInt(), 0.5f)
        assertEquals(0x80.toInt(), (darkened ushr 24) and 0xFF)
        assertEquals(0x7F, (darkened shr 16) and 0xFF)
        val mixed = ColorDerivation.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0xFF7F7F7F.toInt(), mixed)
    }

    @Test
    fun luminanceExtremes() {
        assertEquals(0f, ColorDerivation.luminance(0xFF000000.toInt()), 0.001f)
        assertEquals(1f, ColorDerivation.luminance(0xFFFFFFFF.toInt()), 0.001f)
        // 金 accent 亮度 < 0.5（暗色侧），与 expand 的白字选择一致
        assertTrue(ColorDerivation.luminance(0xFF9C824A.toInt()) < 0.5f)
    }
}
