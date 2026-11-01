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
}
