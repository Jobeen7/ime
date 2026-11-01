package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选单元格样式全字段（纠正 1）：按皮肤取值，缺失走 Jime 默认，
 * 不套用任何单一参考值。测试向量只证“各套值都能按皮肤流过”，
 * 不把任一套值钉为通用默认值。
 */
class CandidateCellStyleTest {

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

    private fun cell(json: String): CandidateCellStyleSpec? =
        validated(json).horizontalCandidateStyle

    @Test
    fun currentSkinVectorFlows() {
        // 现行皮肤值 20/10/10（text/index/comment）。
        val style = cell(
            """{"formatVersion": 3,
               "horizontalCandidateStyle": {"textFontSize": 20, "indexFontSize": 10,
               "commentFontSize": 10, "textFontWeight": "medium",
               "backgroundInsets": {"top": 8, "bottom": 8, "left": 8, "right": 8},
               "cornerRadius": 7}}""",
        )
        assertEquals(20f, style?.textSizeDp)
        assertEquals(10f, style?.indexSizeDp)
        assertEquals(10f, style?.commentSizeDp)
        assertEquals(CandidateFontWeight.MEDIUM, style?.textWeight)
        assertEquals(8f, style?.backgroundInsets?.top)
        assertEquals(7f, style?.cornerRadiusDp)
    }

    @Test
    fun referenceSeriesVectorFlows() {
        // 参考皮肤 18/16 系列（text 18 / index 16）。
        val style = cell(
            """{"formatVersion": 3,
               "horizontalCandidateStyle": {"textFontSize": 18, "indexFontSize": 16,
               "commentFontSize": 16}}""",
        )
        assertEquals(18f, style?.textSizeDp)
        assertEquals(16f, style?.indexSizeDp)
        assertEquals(16f, style?.commentSizeDp)
    }

    @Test
    fun docsExampleVectorsFlow() {
        // 文档示例 18/18/18 与 16/16/16。
        val all18 = cell(
            """{"formatVersion": 3,
               "horizontalCandidateStyle": {"textFontSize": 18, "indexFontSize": 18,
               "commentFontSize": 18}}""",
        )
        assertEquals(18f, all18?.textSizeDp)
        assertEquals(18f, all18?.indexSizeDp)
        assertEquals(18f, all18?.commentSizeDp)
        val all16 = cell(
            """{"formatVersion": 3,
               "horizontalCandidateStyle": {"textFontSize": 16, "indexFontSize": 16,
               "commentFontSize": 16}}""",
        )
        assertEquals(16f, all16?.textSizeDp)
        assertEquals(16f, all16?.indexSizeDp)
        assertEquals(16f, all16?.commentSizeDp)
    }

    @Test
    fun cornerRadiusAliasAccepted() {
        // 文档别名 backgroundCornerRadius 与现行名 cornerRadius 同义。
        val style = cell(
            """{"formatVersion": 3,
               "horizontalCandidateStyle": {"backgroundCornerRadius": 9}}""",
        )
        assertEquals(9f, style?.cornerRadiusDp)
    }

    @Test
    fun absentCellStyleIsNull() {
        assertNull(validated("""{"formatVersion": 3}""").horizontalCandidateStyle)
        assertNull(validated("""{"formatVersion": 3}""").verticalCandidateStyle)
    }

    @Test
    fun invalidWeightDroppedNotRejected() {
        val style = cell(
            """{"formatVersion": 3,
               "horizontalCandidateStyle": {"textFontWeight": "ultraheavy"}}""",
        )
        assertNull(style?.textWeight)
    }

    @Test
    fun appWinsOverSkinWinsOverJimeDefault() {
        // App > 皮肤 > Jime 默认（16/16）；App 侧现无设置项，规则以函数钉住。
        val skin = CandidateCellStyleSpec(indexSizeDp = 10f, commentSizeDp = 10f)
        val resolved = CandidateSizeResolution.resolveAll(skin = skin)
        assertEquals(18f, resolved.textDp, 0f)
        assertEquals(10f, resolved.indexDp, 0f)
        assertEquals(10f, resolved.commentDp, 0f)
        val withApp = CandidateSizeResolution.resolveAll(appIndexDp = 14f, skin = skin)
        assertEquals(14f, withApp.indexDp, 0f)
        val absent = CandidateSizeResolution.resolveAll()
        assertEquals(16f, absent.indexDp, 0f)
        assertEquals(16f, absent.commentDp, 0f)
    }

    @Test
    fun legacyTopLevelFieldsStillFeedResolution() {
        // 旧顶层字号字段在无单元格样式时仍参与解析（兼容旧皮肤）。
        val resolved = CandidateSizeResolution.resolveAll(
            skin = null, legacyIndexDp = 13f, legacyCommentDp = 12f,
        )
        assertEquals(13f, resolved.indexDp, 0f)
        assertEquals(12f, resolved.commentDp, 0f)
    }
}
