package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按码位区间指派字体（审计 C 项）：fontRanges 解析与分段。 */
class SkinFontRangesTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 157, fallbacks,
            assetKind = { if (it == "font-a") AssetKind.FONT else null },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun baseJson(extra: String): String =
        """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
           "stylesDark": {"keyBackground": "#FF222222"}, $extra}"""

    @Test
    fun validRangesParseWithCanonicalFamily() {
        val result = validate(
            baseJson(
                """"fontFaces": [{"name": "MyFont", "url": "font-a"}],
                  "fontRanges": [
                    {"location": 0, "length": 128, "family": "SERIF"},
                    {"location": 0x4E00, "length": 100, "family": "myfont"}
                  ]""",
            ),
        )
        assertEquals(
            listOf(
                SkinFontRange(0, 128, "serif"),
                SkinFontRange(0x4E00, 100, "MyFont"),
            ),
            result.skin.fontRanges,
        )
    }

    @Test
    fun invalidRangeItemsDropIndividually() {
        val result = validate(
            baseJson(
                """"fontRanges": [
                  {"location": 0, "length": 10, "family": "serif"},
                  {"location": -1, "length": 10, "family": "serif"},
                  {"location": 0, "length": 0, "family": "serif"},
                  {"location": 0x110000, "length": 1, "family": "serif"},
                  {"location": 0, "length": 10, "family": "nope"},
                  {"location": 0, "length": 10},
                  "serif"
                ]""",
            ),
        )
        assertEquals(listOf(SkinFontRange(0, 10, "serif")), result.skin.fontRanges)
        val codes = result.issues.map { it.detail }
        assertTrue("font_range_location_invalid" in codes)
        assertTrue("font_range_length_invalid" in codes)
        assertTrue("font_range_overflow" in codes)
        assertTrue("font_range_unknown_family" in codes)
        assertTrue("font_range_family_empty" in codes)
        assertTrue("font_range_not_object" in codes)
    }

    @Test
    fun nonArrayRangesFallsBackToEmpty() {
        val result = validate(baseJson(""""fontRanges": {"location": 0}"""))
        assertTrue(result.skin.fontRanges.isEmpty())
        assertTrue(result.issues.any { it.detail == "font_ranges_not_array" })
    }

    @Test
    fun spansSplitAndCoalesceWithFirstWinOnOverlap() {
        val ranges = listOf(
            SkinFontRange(0, 3, "serif"),
            SkinFontRange(2, 3, "monospace"),
        )
        // 下标 0-1 serif，2 serif（先胜出），3-4 monospace，5 走基础。
        assertEquals(
            listOf(
                FontSpan(0, 3, "serif"),
                FontSpan(3, 5, "monospace"),
                FontSpan(5, 6, null),
            ),
            SkinFontRanges.spansFor("abcdef", ranges),
        )
    }

    @Test
    fun spansHandleSurrogatePairAsTwoUnitsAndClipBeyondText() {
        // 表情占 2 个 UTF-16 码元：区间 location=1 length=2
        // 覆盖整个字符（分段按码元下标，皮肤作者须按 2 计长）。
        val ranges = listOf(SkinFontRange(1, 2, "serif"))
        assertEquals(
            listOf(
                FontSpan(0, 1, null),
                FontSpan(1, 3, "serif"),
                FontSpan(3, 4, null),
            ),
            SkinFontRanges.spansFor("a\uD83D\uDE00b", ranges),
        )
        // 超出文本的区间不命中。
        assertEquals(
            listOf(FontSpan(0, 2, null)),
            SkinFontRanges.spansFor("ab", listOf(SkinFontRange(10, 5, "serif"))),
        )
        assertEquals(emptyList<FontSpan>(), SkinFontRanges.spansFor("", ranges))
    }
}
