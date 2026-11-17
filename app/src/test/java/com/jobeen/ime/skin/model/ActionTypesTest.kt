package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import java.util.Calendar
import java.util.GregorianCalendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动作子集：rimeOption / snippet / delay（combine 专用）/ 前删与光标移动核查。 */
class ActionTypesTest {






    @Test
    fun snippetExpandsDatesAndCursor() {
        val now = GregorianCalendar(2026, Calendar.NOVEMBER, 1, 12, 59, 0).time
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
