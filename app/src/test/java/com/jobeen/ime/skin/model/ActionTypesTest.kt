package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动作子集：rimeOption / snippet / delay（combine 专用）/ 前删与光标移动核查。 */
class ActionTypesTest {

    private fun parseTap(actionJson: String): SkinKeyMappingsParser.ParseResult {
        return SkinKeyMappingsParser.parse(
            Json.parseToJsonElement(
                """{"KEY_Q": {"tap": $actionJson}}""",
            ) as JsonObject,
            isOverridable = { true },
            isAnchor = { false },
        )
    }

    @Test
    fun rimeOptionParsesAndMaps() {
        val parsed = parseTap("""{"type": "rimeOption", "value": "ascii_mode"}""")
        assertEquals(
            SkinAction.RimeOption("ascii_mode"),
            parsed.overrides["KEY_Q"]?.tap,
        )
        assertEquals(
            KeyboardAction.ToggleRimeOptionAction("ascii_mode"),
            KeyOverrideApplier.toRuntime(parsed.overrides["KEY_Q"]!!.tap!!),
        )
        // 空名剔除。
        assertNull(parseTap("""{"type": "rimeOption", "value": "  "}""").overrides["KEY_Q"])
    }

    @Test
    fun snippetParsesAndMaps() {
        val parsed = parseTap("""{"type": "snippet", "value": "今天是{{date:yyyy-MM-dd}}"}""")
        assertEquals(
            SkinAction.Snippet("今天是{{date:yyyy-MM-dd}}"),
            parsed.overrides["KEY_Q"]?.tap,
        )
        assertEquals(
            KeyboardAction.SnippetAction("今天是{{date:yyyy-MM-dd}}"),
            KeyOverrideApplier.toRuntime(parsed.overrides["KEY_Q"]!!.tap!!),
        )
    }

    @Test
    fun delayOnlyInsideCombine() {
        // 顶层 delay 剔除。
        val top = parseTap("""{"type": "delay", "value": 200}""")
        assertNull(top.overrides["KEY_Q"])
        assertTrue(top.rejections.isNotEmpty())
        // combine 内 delay 合法；超限剔除整组。
        val inCombine = parseTap(
            """{"type": "combine", "actions": [
                {"type": "delay", "value": 200},
                {"type": "text", "value": "x"}
            ]}""",
        )
        assertEquals(
            SkinAction.Combine(listOf(SkinAction.Delay(200), SkinAction.Text("x"))),
            inCombine.overrides["KEY_Q"]?.tap,
        )
        assertEquals(
            KeyboardAction.DelayAction(200),
            KeyOverrideApplier.toRuntime(SkinAction.Delay(200)),
        )
        val over = parseTap(
            """{"type": "combine", "actions": [{"type": "delay", "value": 5000}]}""",
        )
        assertNull(over.overrides["KEY_Q"])
        val negative = parseTap(
            """{"type": "combine", "actions": [{"type": "delay", "value": -1}]}""",
        )
        assertNull(negative.overrides["KEY_Q"])
    }

    @Test
    fun deleteForwardAndCursorMovesMap() {
        // 前删：白名单已有，Wave-4 缺失的分发分支本轮补齐。
        assertEquals(
            KeyboardAction.DeleteForwardAction,
            KeyOverrideApplier.toRuntime(
                SkinAction.Command(WhitelistAction.DELETE_FORWARD),
            ),
        )
        // 光标移动：对端 moveCursorBackward/Forward 即 Jime
        // cursor_left/cursor_right（转换器映射名，语义同款）。
        assertEquals(
            KeyboardAction.CursorMoveAction(-1),
            KeyOverrideApplier.toRuntime(SkinAction.Command(WhitelistAction.CURSOR_LEFT)),
        )
        assertEquals(
            KeyboardAction.CursorMoveAction(1),
            KeyOverrideApplier.toRuntime(SkinAction.Command(WhitelistAction.CURSOR_RIGHT)),
        )
    }

    @Test
    fun snippetExpandsDatesAndCursor() {
        val now = LocalDateTime.of(2026, 11, 1, 12, 59, 0)
        // 2026-11-01 为星期日（中文地域：全名 星期日，短名 周日）。
        val expanded = SnippetExpander.expand(
            "今天是{{date:yyyy-MM-dd}} {{date:eeee}} {{date:eee}} {{date:HH:mm}}",
            now,
        )
        assertEquals("今天是2026-11-01 星期日 周日 12:59", expanded.text)
        assertEquals(0, expanded.cursorUnitsFromEnd)
        val withCursor = SnippetExpander.expand("::{{cursor}}::", now)
        assertEquals("::::", withCursor.text)
        assertEquals(2, withCursor.cursorUnitsFromEnd)
        // 农历与非法格式原样保留（不猜测、不抛错）。
        val lunar = SnippetExpander.expand("{{lunar_date:U}} {{lunar_zodiac}}", now)
        assertEquals("{{lunar_date:U}} {{lunar_zodiac}}", lunar.text)
        val bad = SnippetExpander.expand("{{date:yyyy'}}", now)
        assertEquals("{{date:yyyy'}}", bad.text)
    }
}
