package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 竖网格底部钮行＋候选上限口径（皮肤值优先，缺失不套文档默认）。 */
class VerticalBottomRowTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validated(json: String): ValidatedSkin {
        val trimmed = json.trimEnd()
        val withLight = if (json.contains("\"styles\"")) json
        else trimmed.dropLast(1) + """, "styles": {"keyBackground": "#FFFFFFFF"}}"""
        val withDark = if (withLight.contains("\"stylesDark\"")) withLight
        else withLight.trimEnd().dropLast(1) +
            """, "stylesDark": {"keyBackground": "#FF222222"}}"""
        val tree = Json.parseToJsonElement(withDark) as JsonObject
        val result = SkinValidator.validate(tree, 143, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    @Test
    fun bottomRowParses() {
        val skin = validated(
            """{"formatVersion": 3,
               "verticalBottomRow": {"pageUp": true, "pageDown": true,
               "returnOrCollapse": true, "backspace": true,
               "returnLabel": "返回"}}""",
        )
        val row = skin.verticalBottomRow
        assertTrue(row?.pageUp == true)
        assertTrue(row?.pageDown == true)
        assertTrue(row?.returnOrCollapse == true)
        assertTrue(row?.backspace == true)
        assertEquals("返回", row?.returnLabel)
        assertTrue(row?.hasAny == true)
    }

    @Test
    fun bottomRowAbsentIsNull() {
        assertNull(validated("""{"formatVersion": 3}""").verticalBottomRow)
    }

    @Test
    fun returnLabelAcceptsBothNames() {
        // 返回与收起同指一钮位，转换器与解析必须双名同认。
        assertTrue(VerticalBottomRowNames.isReturnLabel("返回"))
        assertTrue(VerticalBottomRowNames.isReturnLabel("收起"))
        assertFalse(VerticalBottomRowNames.isReturnLabel("翻页"))
        assertFalse(VerticalBottomRowNames.isReturnLabel(null))
        val skin = validated(
            """{"formatVersion": 3,
               "verticalBottomRow": {"returnOrCollapse": true,
               "returnLabel": "收起"}}""",
        )
        assertTrue(
            VerticalBottomRowNames.isReturnLabel(skin.verticalBottomRow?.returnLabel),
        )
    }

    @Test
    fun skinValuesWinOverDefaults() {
        // 现行皮肤显式值（横条 6、竖行 5）胜过文档默认（7、4）。
        val skin = validated(
            """{"formatVersion": 3,
               "horizontalCandidates": {"maxColumns": 6},
               "verticalCandidates": {"maxRows": 5, "maxColumns": 5}}""",
        )
        assertEquals(6, skin.horizontalMaxColumns)
        assertEquals(5, skin.verticalMaxRows)
        assertEquals(5, skin.verticalMaxColumns)
        assertEquals(7, PeerCandidateDefaults.HORIZONTAL_MAX_COLUMNS)
        assertEquals(4, PeerCandidateDefaults.VERTICAL_MAX_ROWS)
        assertEquals(5, PeerCandidateDefaults.VERTICAL_MAX_COLUMNS)
    }

    @Test
    fun absentLimitsKeepJimeBehavior() {
        // 缺失不套文档默认：横条不限、竖行数走用户偏好。
        assertNull(resolveHorizontalMaxColumns(null))
        assertEquals(6, resolveHorizontalMaxColumns(6))
        assertEquals(3, resolveVerticalMaxRows(null, 3))
        assertEquals(5, resolveVerticalMaxRows(5, 3))
    }
}
