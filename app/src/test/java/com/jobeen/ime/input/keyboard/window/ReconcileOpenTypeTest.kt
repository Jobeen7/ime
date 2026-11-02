package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 打开时方案与留存类型对账：陈旧主类型以方案为准，主动英文与非主类型不动。 */
class ReconcileOpenTypeTest {

    @Test
    fun staleAlphabeticOnT9SchemeReturnsPinyin() {
        assertEquals("pinyin", reconcileOpenType("pinyin", "alphabetic", asciiMode = false))
    }

    @Test
    fun stalePinyinOnQwertySchemeReturnsAlphabetic() {
        assertEquals("alphabetic", reconcileOpenType("alphabetic", "pinyin", asciiMode = false))
    }

    @Test
    fun matchingMainTypesStay() {
        assertEquals("pinyin", reconcileOpenType("pinyin", "pinyin", asciiMode = false))
        assertEquals("alphabetic", reconcileOpenType("alphabetic", "alphabetic", asciiMode = false))
    }

    @Test
    fun asciiModeKeepsDeliberateEnglish() {
        assertEquals("alphabetic", reconcileOpenType("pinyin", "alphabetic", asciiMode = true))
        assertEquals("pinyin", reconcileOpenType("alphabetic", "pinyin", asciiMode = true))
    }

    @Test
    fun toolAndCustomTypesStay() {
        assertEquals("symbolic", reconcileOpenType("pinyin", "symbolic", asciiMode = false))
        assertEquals("emojis", reconcileOpenType("alphabetic", "emojis", asciiMode = false))
        assertEquals("my-board", reconcileOpenType("pinyin", "my-board", asciiMode = false))
    }

    @Test
    fun unknownSchemeTypeOrNullCurrentStay() {
        assertEquals("alphabetic", reconcileOpenType(null, "alphabetic", asciiMode = false))
        assertEquals("pinyin", reconcileOpenType("numeric", "pinyin", asciiMode = false))
        assertNull(reconcileOpenType("pinyin", null, asciiMode = false))
    }
}
