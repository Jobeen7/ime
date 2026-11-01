package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选颜色字段（审计 I 项）：注释色、按压色、首选色、竖分隔色
 * 经 styles.panel.* 流入快照方案；缺失时渲染层回落既有字段
 * （此处只钉解析与合成，渲染回落见 Paints / 渲染器注释）。
 */
class CandidateColorFieldsTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-1", "h".repeat(64), 1)

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 100,
        keyPressed = 101,
        keyBorderStroke = 102,
        specialKeyBackground = 200,
        specialKeyPressed = 201,
        specialKeyBorderStroke = 202,
        accentKeyBackground = 300,
        accentKeyPressed = 301,
        accentKeyBorderStroke = 302,
        keyText = 400,
        specialKeyText = 401,
        accentKeyText = 402,
        altText = 403,
        background = 500,
        cornerRadius = 5f,
        keyHMargin = 3f,
        keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999,
        toastText = 998,
    )

    private fun build(json: String): KeyboardColors.ColorScheme {
        val trimmed = json.trimEnd()
        val withDark = if (json.contains("\"stylesDark\"")) json
        else trimmed.dropLast(1) + """, "stylesDark": {"keyBackground": "#FF222222"}}"""
        val tree = Json.parseToJsonElement(withDark) as JsonObject
        val result = SkinValidator.validate(
            tree, 143, fallbacks, assetKind = { null },
        )
        assertTrue(result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        return SkinSnapshotBuilder.build(
            skin, ref, defaultScheme, MemoryTier.NORMAL, { "a".repeat(64) }, 1000, 400,
        ).colorScheme
    }

    private fun base(panelExtra: String): String =
        """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF", "panel": {$panelExtra}}}"""

    @Test
    fun candidateCommentFlowsToScheme() {
        val scheme = build(base(""""candidateComment": "#FF112233"""))
        assertEquals(0xFF112233.toInt(), scheme.panel.candidateComment)
    }

    @Test
    fun candidateCommentAbsentIsNull() {
        val scheme = build(base(""""candidateText": "#FF000000"""))
        assertNull(scheme.panel.candidateComment)
    }

    @Test
    fun candidatePressedFlowsToScheme() {
        val scheme = build(base(""""candidatePressed": "#FF445566"""))
        assertEquals(0xFF445566.toInt(), scheme.panel.candidatePressed)
    }

    @Test
    fun candidatePressedAbsentIsNull() {
        val scheme = build(base(""""candidateText": "#FF000000"""))
        assertNull(scheme.panel.candidatePressed)
    }

    @Test
    fun candidatePressedValueInFallsBackToBackground() {
        // 导出/令牌解析口径：未指定按压色时取候选底。
        val panel = defaultScheme.panel.copy(candidateBackground = 604, candidatePressed = null)
        val scheme = defaultScheme.copy(panel = panel)
        assertEquals(604, StyleField.PANEL_CANDIDATE_PRESSED.valueIn(scheme))
    }

    @Test
    fun preferredColorsFlowToScheme() {
        val scheme = build(
            base(
                """"preferredBackground": "#FF112233", "preferredText": "#FF445566",
                   "preferredComment": "#FF778899", "preferredIndex": "#FFAABBCC"""",
            ),
        )
        assertEquals(0xFF112233.toInt(), scheme.panel.preferredBackground)
        assertEquals(0xFF445566.toInt(), scheme.panel.preferredText)
        assertEquals(0xFF778899.toInt(), scheme.panel.preferredComment)
        assertEquals(0xFFAABBCC.toInt(), scheme.panel.preferredIndex)
    }

    @Test
    fun preferredColorsAbsentAreNull() {
        val scheme = build(base(""""candidateText": "#FF000000"""))
        assertNull(scheme.panel.preferredBackground)
        assertNull(scheme.panel.preferredText)
        assertNull(scheme.panel.preferredComment)
        assertNull(scheme.panel.preferredIndex)
    }

    @Test
    fun verticalSeparatorFlowsToScheme() {
        val scheme = build(base(""""verticalSeparator": "#FF123456"""))
        assertEquals(0xFF123456.toInt(), scheme.panel.verticalSeparator)
    }

    @Test
    fun verticalSeparatorAbsentIsNull() {
        val scheme = build(base(""""candidateText": "#FF000000"""))
        assertNull(scheme.panel.verticalSeparator)
    }

    @Test
    fun verticalSeparatorValueInFallsBackToDivider() {
        val panel = defaultScheme.panel.copy(candidateDivider = 607, verticalSeparator = null)
        val scheme = defaultScheme.copy(panel = panel)
        assertEquals(607, StyleField.PANEL_VERTICAL_SEPARATOR.valueIn(scheme))
    }

    @Test
    fun candidateCommentValueInFallsBackToIndex() {
        // 导出/令牌解析口径：未指定注释色时取序号色。
        val panel = defaultScheme.panel.copy(candidateIndex = 606, candidateComment = null)
        val scheme = defaultScheme.copy(panel = panel)
        assertEquals(606, StyleField.PANEL_CANDIDATE_COMMENT.valueIn(scheme))
    }
}
