package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Test

/** 首挂类型恢复：只恢复主类型，其余一律回落初始主类型。 */
class FirstAttachRestoreTest {

    @Test
    fun mainTypesRestoredRegardlessOfInputMode() {
        assertEquals("pinyin", resolveFirstAttachType("pinyin", asciiMode = true))
        assertEquals("alphabetic", resolveFirstAttachType("alphabetic", asciiMode = false))
    }

    @Test
    fun toolTypesFallBackToInitialByInputMode() {
        assertEquals("pinyin", resolveFirstAttachType("symbolic", asciiMode = false))
        assertEquals("alphabetic", resolveFirstAttachType("symbolic", asciiMode = true))
        assertEquals("pinyin", resolveFirstAttachType("emojis", asciiMode = false))
        assertEquals("alphabetic", resolveFirstAttachType("emojis", asciiMode = true))
        assertEquals("pinyin", resolveFirstAttachType("numeric", asciiMode = false))
    }

    @Test
    fun customOrMissingStoredTypeFallsBackToInitial() {
        assertEquals("pinyin", resolveFirstAttachType("my-board", asciiMode = false))
        assertEquals("alphabetic", resolveFirstAttachType("my-board", asciiMode = true))
        assertEquals("pinyin", resolveFirstAttachType(null, asciiMode = false))
        assertEquals("alphabetic", resolveFirstAttachType(null, asciiMode = true))
        assertEquals("pinyin", resolveFirstAttachType("", asciiMode = false))
    }
}
