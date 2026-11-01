package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按符号网格（网格版）：解析、相对位移选择、收容、网格优先。 */
class HintSymbolsGridTest {

    private val surroundJson = """
        {"symbolRows": [
          [null, "up", null],
          ["left", null, "right"],
          [null, "down", null]
        ],
        "size": {"width": 44, "height": 44},
        "anchor": {"row": 1, "col": 1},
        "selectedBackgroundStyle": "sel"}
    """.trimIndent()

    private val panelJson = """
        {"symbolRows": [
          ["s1", "s2", "s3"],
          ["s4", "s5", "s6"]
        ],
        "size": {"width": 40, "height": 40},
        "spacing": {"horizontal": 2, "vertical": 2},
        "selected": {"row": 1, "col": 1}}
    """.trimIndent()

    private fun parse(json: String): HintSymbolsGridStyle? =
        HintSymbolsGridParser.parseStyle(Json.parseToJsonElement(json))

    @Test
    fun surroundLayoutParsesWithGaps() {
        val style = parse(surroundJson)
        assertEquals(3, style?.rowCount)
        assertEquals(3, style?.columnCount)
        assertNull(style?.cellAt(1, 1))
        assertEquals("up", style?.cellAt(0, 1))
        assertEquals(44f, style?.size?.width)
        assertEquals(1, style?.anchorRow)
        assertEquals(1, style?.anchorCol)
        assertEquals(10f, style?.moveThreshold)
    }

    @Test
    fun tildeStringCountsAsGap() {
        val style = parse(
            """{"symbolRows": [["a", "~", "b"]]}""",
        )
        assertNull(style?.cellAt(0, 1))
        assertEquals("b", style?.cellAt(0, 2))
    }

    @Test
    fun emptyOrAllGapRowsRejected() {
        assertNull(parse("""{"symbolRows": []}"""))
        assertNull(parse("""{"symbolRows": [[null, null]]}"""))
        assertNull(parse("""{"other": 1}"""))
    }

    @Test
    fun moveStepNeedsBothAxes() {
        // 只配一轴时双双失效（对端口径），回落单元格步进。
        val style = parse(
            """{"symbolRows": [["a", "b"]],
               "moveStep": {"horizontal": 20}}""",
        )
        assertNull(style?.moveStepHorizontal)
        assertNull(style?.moveStepVertical)
        val both = parse(
            """{"symbolRows": [["a", "b"]],
               "moveStep": {"horizontal": 20, "vertical": 20}}""",
        )
        assertEquals(20f, both?.moveStepHorizontal)
        assertEquals(20f, both?.moveStepVertical)
    }

    @Test
    fun displacementBelowThresholdKeepsInitial() {
        val style = parse(panelJson)!!
        val origin = HintGridSelection.initialCell(style)
        assertEquals(1 to 1, origin)
        // 5 点抖动（阈值 10）不换格。
        assertEquals(1 to 1, HintGridSelection.selectByDisplacement(style, origin, 5f, 0f, 42f, 42f))
        assertEquals(1 to 1, HintGridSelection.selectByDisplacement(style, origin, 0f, -9f, 42f, 42f))
    }

    @Test
    fun displacementStepsFromOrigin() {
        val style = parse(panelJson)!!
        val origin = 1 to 1
        // 右移 42 点（一步进）→ (1,2)；上移 42 点 → (0,1)。
        assertEquals(1 to 2, HintGridSelection.selectByDisplacement(style, origin, 42f, 0f, 42f, 42f))
        assertEquals(0 to 1, HintGridSelection.selectByDisplacement(style, origin, 0f, -42f, 42f, 42f))
    }

    @Test
    fun gapsAreNotDeadZones() {
        // 环绕布局：起始格取 anchor 最近的非空格（上）；向左一格
        // 目标 (0,0) 为空格 → 最近非空格为 (0,1) 或 (1,0)，不落空。
        val style = parse(surroundJson)!!
        val origin = HintGridSelection.initialCell(style)
        assertEquals(0 to 1, origin)
        val moved = HintGridSelection.selectByDisplacement(style, origin, -44f, 0f, 44f, 44f)
        assertTrue(moved == (0 to 1) || moved == (1 to 0))
    }

    @Test
    fun outOfBoundsClearsAndMoveBackReselects() {
        val style = parse(panelJson)!!
        val origin = 1 to 1
        // 右移两格越界 → 清除高亮（释放 = 取消）。
        assertNull(HintGridSelection.selectByDisplacement(style, origin, 84f, 0f, 42f, 42f))
        // 移回一格 → 重新选中（无状态函数，同一输入同一输出）。
        assertEquals(1 to 2, HintGridSelection.selectByDisplacement(style, origin, 42f, 0f, 42f, 42f))
    }

    @Test
    fun edgeContainmentKeepsPanelInside() {
        val (x, y) = HintGridSelection.containPanel(350f, -10f, 100f, 120f, 390f, 400f)
        assertEquals(290f, x, 0f)
        assertEquals(0f, y, 0f)
        val (x2, y2) = HintGridSelection.containPanel(50f, 50f, 100f, 120f, 390f, 400f)
        assertEquals(50f, x2, 0f)
        assertEquals(50f, y2, 0f)
    }

    @Test
    fun gridWinsOverLegacy() {
        assertEquals(
            HintGridChoice.Grid("g"),
            resolveHintGrid("g", "legacy"),
        )
        assertEquals(
            HintGridChoice.Legacy("legacy"),
            resolveHintGrid(null, "legacy"),
        )
        assertEquals(HintGridChoice.None, resolveHintGrid(null, null))
    }

    @Test
    fun validatorTableFlows() {
        val json = """{"formatVersion": 3,
            "hintSymbolsGridStyles": {"surround": $surroundJson},
            "styles": {"keyBackground": "#FFFFFFFF"},
            "stylesDark": {"keyBackground": "#FF222222"}}"""
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 143, GeometryFallbacks(12f, 6f, 5f, 10f), assetKind = { null },
        )
        assertTrue(result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        assertEquals(3, skin.hintSymbolsGridStyles["surround"]?.rowCount)
    }
}
