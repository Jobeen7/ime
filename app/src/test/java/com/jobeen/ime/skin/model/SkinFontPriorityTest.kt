package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.FontChoice
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多字体优先级（审计 C 项）：fontFamily / roleFonts 数组顺序即优先级。 */
class SkinFontPriorityTest {

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
    fun arrayOrderKeptFirstIsLegacySingleValue() {
        val result = validate(
            baseJson(
                """"fontFaces": [{"name": "MyFont", "url": "font-a"}],
                  "fontFamily": ["MyFont", "serif", "serif", "default", "nope", 42, ""],
                  "roleFonts": {"key": ["monospace", "MYFONT"]}""",
            ),
        )
        assertEquals(listOf("MyFont", "serif"), result.skin.fontFamilies)
        assertEquals("MyFont", result.skin.fontFamily)
        assertEquals(
            mapOf(FontRole.KEY to listOf("monospace", "MyFont")),
            result.skin.roleFontFamilies,
        )
        assertEquals("monospace", result.skin.roleFonts[FontRole.KEY])
        val codes = result.issues.map { it.code }
        assertTrue("font_family_unknown" in codes)
        assertTrue("font_family_not_string" in codes)
        assertTrue("font_family_empty" in codes)
    }

    @Test
    fun singleStringStillParsesAsOneCandidate() {
        val result = validate(baseJson(""""fontFamily": "serif""""))
        assertEquals(listOf("serif"), result.skin.fontFamilies)
        assertEquals("serif", result.skin.fontFamily)
    }

    @Test
    fun defaultOnlyYieldsNoCandidates() {
        val result = validate(
            baseJson(""""fontFamily": ["default"], "roleFonts": {"key": "default"}"""),
        )
        assertTrue(result.skin.fontFamilies.isEmpty())
        assertEquals(null, result.skin.fontFamily)
        assertTrue(result.skin.roleFontFamilies.isEmpty())
        assertTrue(result.skin.roleFonts.isEmpty())
    }

    @Test
    fun nonStringFamilyNodeFallsBackToEmpty() {
        val result = validate(baseJson(""""fontFamily": {"x": 1}"""))
        assertTrue(result.skin.fontFamilies.isEmpty())
        assertTrue(result.issues.any { it.code == "font_family_not_string" })
    }

    @Test
    fun effectiveSourceTakesFirstResolvableCandidate() {
        // 首候选未知 → 跳到次候选；内嵌候选优先于系统候选。
        assertEquals(
            GlobalFonts.EffectiveSource.Skin("serif"),
            GlobalFonts.effectiveSource(
                null, FontChoice.System("sans-serif"),
                emptyMap(), FontRole.KEY, emptyList(),
                listOf("nope", "serif"),
            ),
        )
        assertEquals(
            GlobalFonts.EffectiveSource.SkinBundled("MyFont"),
            GlobalFonts.effectiveSource(
                null, FontChoice.System("sans-serif"),
                emptyMap(), FontRole.KEY, listOf("MyFont"),
                emptyList(),
                mapOf(FontRole.KEY to listOf("MyFont", "serif")),
            ),
        )
        // 角色候选全不可解析 → 回落皮肤单值候选。
        assertEquals(
            GlobalFonts.EffectiveSource.Skin("monospace"),
            GlobalFonts.effectiveSource(
                "monospace", FontChoice.System("sans-serif"),
                emptyMap(), FontRole.KEY, emptyList(), emptyList(),
                mapOf(FontRole.KEY to listOf("nope")),
            ),
        )
        // 旧单值调用（无列表参数）行为不变。
        assertEquals(
            GlobalFonts.EffectiveSource.SkinRole("serif"),
            GlobalFonts.effectiveSource(
                "monospace", FontChoice.System("sans-serif"),
                mapOf(FontRole.KEY to "serif"), FontRole.KEY,
            ),
        )
    }
}
