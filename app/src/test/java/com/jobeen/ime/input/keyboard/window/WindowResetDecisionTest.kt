package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Test

/** 新窗口复位决策：方案回第一个＋类型复位到首个方案的布局类型。 */
class WindowResetDecisionTest {

    @Test
    fun englishSchemeAndTypeResetsBoth() {
        assertEquals(
            WindowReset.Apply(resetScheme = true, targetType = "pinyin"),
            decideWindowReset("wanxiang_english", "wanxiang_t9", "alphabetic", "pinyin"),
        )
    }

    @Test
    fun firstSchemeWithStaleTypeResetsTypeOnly() {
        assertEquals(
            WindowReset.Apply(resetScheme = false, targetType = "pinyin"),
            decideWindowReset("wanxiang_t9", "wanxiang_t9", "alphabetic", "pinyin"),
        )
    }

    @Test
    fun alreadyFirstSchemeAndTypeIsNoop() {
        assertEquals(
            WindowReset.None,
            decideWindowReset("wanxiang_t9", "wanxiang_t9", "pinyin", "pinyin"),
        )
    }

    @Test
    fun englishSchemeWithMatchingTypeResetsSchemeOnly() {
        assertEquals(
            WindowReset.Apply(resetScheme = true, targetType = null),
            decideWindowReset("wanxiang_english", "wanxiang_t9", "pinyin", "pinyin"),
        )
    }

    @Test
    fun noFirstSchemeIsNoop() {
        assertEquals(
            WindowReset.None,
            decideWindowReset("wanxiang_english", null, "alphabetic", "pinyin"),
        )
    }

    @Test
    fun unknownSchemeTypeResetsSchemeOnly() {
        assertEquals(
            WindowReset.Apply(resetScheme = true, targetType = null),
            decideWindowReset("wanxiang_english", "wanxiang_t9", "alphabetic", null),
        )
    }

    @Test
    fun unknownSchemeTypeWithFirstSchemeIsNoop() {
        assertEquals(
            WindowReset.None,
            decideWindowReset("wanxiang_t9", "wanxiang_t9", "alphabetic", null),
        )
    }

    @Test
    fun noCurrentSchemeResetsBoth() {
        assertEquals(
            WindowReset.Apply(resetScheme = true, targetType = "pinyin"),
            decideWindowReset(null, "wanxiang_t9", "alphabetic", "pinyin"),
        )
    }
}
