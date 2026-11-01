package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选序号／注释字号皮肤字段（审计 I 项）：解析、范围、缺失回落。 */
class CandidateSizeFieldsTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validated(json: String): ValidatedSkin {
        val trimmed = json.trimEnd()
        val withDark = if (json.contains("\"stylesDark\"")) json
        else trimmed.dropLast(1) + """, "stylesDark": {"keyBackground": "#FF222222"}}"""
        val tree = Json.parseToJsonElement(withDark) as JsonObject
        val result = SkinValidator.validate(tree, 143, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    @Test
    fun sizeFieldsParse() {
        val skin = validated(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "candidateIndexSizeDp": 20, "candidateCommentSizeDp": 13}""",
        )
        assertEquals(20f, skin.candidateIndexSizeDp)
        assertEquals(13f, skin.candidateCommentSizeDp)
    }

    @Test
    fun sizeFieldsAbsentAreNull() {
        val skin = validated("""{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}}""")
        assertNull(skin.candidateIndexSizeDp)
        assertNull(skin.candidateCommentSizeDp)
    }

    @Test
    fun sizeFieldsOutOfRangeAreDropped() {
        // 8..32dp 外的值记问题并丢弃（渲染回落 Jime 默认），而非截断。
        val skin = validated(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "candidateIndexSizeDp": 40, "candidateCommentSizeDp": 4}""",
        )
        assertNull(skin.candidateIndexSizeDp)
        assertNull(skin.candidateCommentSizeDp)
    }
}
