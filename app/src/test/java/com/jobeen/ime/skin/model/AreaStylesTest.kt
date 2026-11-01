package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区域样式解析（审计 B 项）：dp / "Nvh" 长度写法与内边距。
 * 规格来自对端文档（数字 = dp，字符串 "Nvh" = 视口高度百分比）。
 */
class AreaStylesTest {

    private fun parseLength(raw: String, dpRange: ClosedFloatingPointRange<Float> = 16f..120f): Pair<VhLengthSpec?, List<SkinIssue>> {
        val issues = mutableListOf<SkinIssue>()
        val node: JsonElement = Json.parseToJsonElement(raw)
        return parseVhLength(node, "toolbarHeight", issues, dpRange) to issues
    }

    @Test
    fun toolbarHeightNumberIsDp() {
        val (spec, issues) = parseLength("48")
        assertEquals(48f, spec?.dp)
        assertNull(spec?.vhPercent)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun toolbarHeightVhStringIsPercent() {
        val (spec, issues) = parseLength("\"5.2vh\"")
        assertNull(spec?.dp)
        assertEquals(5.2f, spec?.vhPercent)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun toolbarHeightVhResolvesAgainstScreenHeight() {
        val spec = VhLengthSpec(dp = null, vhPercent = 5.2f)
        // 对端 5.2vh：800dp 视口高 → 41.6dp。
        assertEquals(41.6f, spec.resolveDp(800f, 48f), 0.001f)
    }

    @Test
    fun toolbarHeightDpResolvesDirectly() {
        val spec = VhLengthSpec(dp = 40f, vhPercent = null)
        assertEquals(40f, spec.resolveDp(800f, 48f), 0f)
    }

    @Test
    fun toolbarHeightStringNumberIsInvalid() {
        // 严格口径：字符串数字不算数值。
        val (spec, issues) = parseLength("\"48\"")
        assertNull(spec)
        assertTrue(issues.any { it.path == "toolbarHeight" })
    }

    @Test
    fun toolbarHeightBadVhIsInvalid() {
        val (spec, issues) = parseLength("\"abc\"")
        assertNull(spec)
        assertTrue(issues.any { it.path == "toolbarHeight" })
    }

    @Test
    fun toolbarHeightOutOfRangeDpIsInvalid() {
        val (spec, issues) = parseLength("500")
        assertNull(spec)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun areaInsetsPartialSidesDefaultToZero() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("""{"left": 3}""")
        val insets = parseAreaInsets(node, "toolbarStyle.insets", issues)
        assertEquals(3f, insets.left, 0f)
        assertEquals(0f, insets.top, 0f)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun areaInsetsBadSideIsZeroWithIssue() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("""{"left": "x", "top": 2}""")
        val insets = parseAreaInsets(node, "toolbarStyle.insets", issues)
        assertEquals(0f, insets.left, 0f)
        assertEquals(2f, insets.top, 0f)
        assertTrue(issues.any { it.path == "toolbarStyle.insets.left" })
    }

    @Test
    fun toolbarStyleParsesAllFields() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement(
            """{"insets": {"bottom": 4, "left": 6, "right": 6, "top": 0},
               "background": "#FF112233", "borderWidthDp": 1, "borderColor": "#FF000000",
               "cornerRadiusDp": 8}""",
        )
        val style = parseToolbarStyle(node, "toolbarStyle", issues)
        assertTrue(issues.isEmpty())
        assertEquals(6f, style?.insets?.left)
        assertEquals(4f, style?.insets?.bottom)
        assertEquals(0xFF112233.toInt(), style?.background)
        assertEquals(1f, style?.borderWidthDp)
        assertEquals(8f, style?.cornerRadiusDp)
    }

    @Test
    fun toolbarStyleContentRectStacksInsetsOnAppPad() {
        // 对端 insets {left 6, right 6}：App 横向内边距 12dp 之上再叠加。
        val style = ToolbarStyleSpec(
            insets = AreaInsets(left = 6f, top = 0f, right = 6f, bottom = 4f),
            cornerRadiusDp = 8f,
        )
        val rect = style.contentRect(1080, 144, 3f, 12f * 3f)
        assertEquals(54f, rect.left, 0f)
        assertEquals(1026f, rect.right, 0f)
        // 上下内边距决定内容垂直中心：(0 + (144 - 12)) / 2 = 66。
        assertEquals(66f, rect.cy, 0f)
        assertEquals(24f, rect.radiusPx, 0f)
    }

    @Test
    fun toolbarStyleAbsentIsNull() {
        val issues = mutableListOf<SkinIssue>()
        assertNull(parseToolbarStyle(null, "toolbarStyle", issues))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun toolbarLayoutParsesOrderAndDedupes() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("""["close", "center", "menu", "menu"]""")
        val layout = parseToolbarLayout(node, "toolbarLayout", issues)
        assertEquals(
            listOf(ToolbarSlot.CLOSE, ToolbarSlot.CENTER, ToolbarSlot.MENU),
            layout?.slots,
        )
        assertTrue(issues.any { it.detail == "toolbar_layout_duplicate_slot" })
    }

    @Test
    fun toolbarLayoutBadSlotDroppedAndEmptyIsNull() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("""["bogus"]""")
        assertNull(parseToolbarLayout(node, "toolbarLayout", issues))
        assertTrue(issues.any { it.detail == "toolbar_layout_bad_slot" })
    }

    @Test
    fun toolbarSlotGeometryDefaultMatchesOldRow() {
        // 旧固定行：左菜单（内边距 12dp + 固定 32dp）、中央区、右收起。
        val geo = toolbarSlotGeometry(1080, 3f, 36f, 36f, 36f, ToolbarLayoutSpec.DEFAULT.slots)
        assertEquals(36f + 48f, geo.menuCenter)
        assertEquals(1080f - 36f - 48f, geo.closeCenter)
        assertEquals(36f + 96f + 36f, geo.centerLeft, 0f)
        assertEquals(1080f - 36f - 96f - 36f - (36f + 96f + 36f), geo.centerWidth, 0f)
    }

    @Test
    fun toolbarSlotGeometrySwappedOrderMirrorsSlots() {
        val geo = toolbarSlotGeometry(
            1080, 3f, 36f, 36f, 36f,
            listOf(ToolbarSlot.CLOSE, ToolbarSlot.CENTER, ToolbarSlot.MENU),
        )
        assertEquals(36f + 48f, geo.closeCenter)
        assertEquals(1080f - 36f - 48f, geo.menuCenter)
        assertTrue(geo.centerWidth > 0f)
    }

    @Test
    fun preeditHeightVhResolvesAgainstScreenHeight() {
        // 对端 1.53vh：800dp 视口高 → 12.24dp。
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("\"1.53vh\"")
        val spec = parseVhLength(node, "preeditHeight", issues, 12f..64f)
        assertTrue(issues.isEmpty())
        assertEquals(12.24f, spec?.resolveDp(800f, 30f), 0.001f)
    }

    @Test
    fun candidateAreaStyleParsesInsetsAndBackground() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement(
            """{"insets": {"bottom": 3, "left": 4, "right": 4, "top": 3},
               "background": "#FF112233"}""",
        )
        val style = parseCandidateAreaStyle(node, "verticalCandidatesStyle", issues)
        assertTrue(issues.isEmpty())
        assertEquals(4f, style?.insets?.left)
        assertEquals(3f, style?.insets?.top)
        assertEquals(0xFF112233.toInt(), style?.background)
    }

    @Test
    fun horizontalCandidatesMaxColumnsParses() {
        val issues = mutableListOf<SkinIssue>()
        val spec = parseHorizontalCandidates(
            Json.parseToJsonElement("""{"maxColumns": 7}"""),
            "horizontalCandidates", issues,
        )
        assertEquals(7, spec?.maxColumns)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun horizontalCandidatesAbsentIsNull() {
        val issues = mutableListOf<SkinIssue>()
        assertNull(parseHorizontalCandidates(null, "horizontalCandidates", issues))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun horizontalCandidatesOutOfRangeIsDropped() {
        val issues = mutableListOf<SkinIssue>()
        val spec = parseHorizontalCandidates(
            Json.parseToJsonElement("""{"maxColumns": 0}"""),
            "horizontalCandidates", issues,
        )
        assertNull(spec?.maxColumns)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun candidateAreaStyleItemInsetsParse() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement(
            """{"pillPadDp": 10, "pillGapDp": 4,
               "cellPadDp": 8, "minWordSpaceDp": 12, "minRowHeightDp": 40}""",
        )
        val style = parseCandidateAreaStyle(node, "horizontalCandidatesStyle", issues)
        assertTrue(issues.isEmpty())
        assertEquals(10f, style?.pillPadDp)
        assertEquals(4f, style?.pillGapDp)
        assertEquals(8f, style?.cellPadDp)
        assertEquals(12f, style?.minWordSpaceDp)
        assertEquals(40f, style?.minRowHeightDp)
    }

    @Test
    fun candidateAreaStyleItemInsetsAbsentAreNull() {
        val issues = mutableListOf<SkinIssue>()
        val style = parseCandidateAreaStyle(
            Json.parseToJsonElement("""{"insets": {"left": 3}}"""),
            "verticalCandidatesStyle", issues,
        )
        assertNull(style?.pillPadDp)
        assertNull(style?.cellPadDp)
        assertNull(style?.minRowHeightDp)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun candidateAreaStyleHorizontalDefaultsToNoBackground() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("""{"insets": {"left": 3}}""")
        val style = parseCandidateAreaStyle(node, "horizontalCandidatesStyle", issues)
        assertEquals(3f, style?.insets?.left)
        assertNull(style?.background)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun preeditStyleParsesAllFields() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement(
            """{"heightDp": 32, "cornerRadiusDp": 10, "paddingDp": 12,
               "fontSizeDp": 16, "secondaryFontSizeDp": 14,
               "textColor": "#FF000000", "secondaryTextColor": "#FF666666",
               "background": "#FFFFFFFF", "insets": {"left": 8, "top": 2}}""",
        )
        val style = parsePreeditStyle(node, "preeditStyle", issues)
        assertTrue(issues.isEmpty())
        assertEquals(32f, style?.heightDp)
        assertEquals(10f, style?.cornerRadiusDp)
        assertEquals(12f, style?.paddingDp)
        assertEquals(16f, style?.fontSizeDp)
        assertEquals(14f, style?.secondaryFontSizeDp)
        assertEquals(0xFF000000.toInt(), style?.textColor)
        assertEquals(8f, style?.insets?.left)
    }

    @Test
    fun preeditStyleBadColorDroppedWithIssue() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("""{"textColor": "notacolor", "paddingDp": 12}""")
        val style = parsePreeditStyle(node, "preeditStyle", issues)
        assertNull(style?.textColor)
        assertEquals(12f, style?.paddingDp)
        assertTrue(issues.any { it.path == "preeditStyle.textColor" })
    }

    @Test
    fun preeditHeightNumberIsDp() {
        val issues = mutableListOf<SkinIssue>()
        val node = Json.parseToJsonElement("15")
        val spec = parseVhLength(node, "preeditHeight", issues, 12f..64f)
        assertEquals(15f, spec?.dp)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun toolbarSlotGeometryWithoutCenterSpansFixedZones() {
        val geo = toolbarSlotGeometry(
            1080, 3f, 36f, 36f, 36f,
            listOf(ToolbarSlot.MENU, ToolbarSlot.CLOSE),
        )
        assertEquals(36f + 48f, geo.menuCenter)
        assertEquals(1080f - 36f - 48f, geo.closeCenter)
        // 无中央槽时跨度 = 左固定槽后到右固定槽前（不计中央内边距）。
        assertEquals(36f + 96f, geo.centerLeft, 0f)
        assertEquals(1080f - 36f - 96f - (36f + 96f), geo.centerWidth, 0f)
    }
}
