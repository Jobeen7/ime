package com.jobeen.ime.skin.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Material Symbols 注册表（纯映射部分）：子集清单与字形解析。 */
class MaterialSymbolsTest {

    @Test
    fun subsetHasExpectedSizeAndDistinctCodepoints() {
        assertEquals(59, MaterialSymbols.NAMES.size)
        assertEquals(59, MaterialSymbols.NAMES.values.toSet().size)
    }

    @Test
    fun knownNamesResolveToPrivateUseGlyphs() {
        assertEquals("\uE14A", MaterialSymbols.glyphFor("backspace"))
        assertEquals("\uE31D", MaterialSymbols.glyphFor("mic"))
        assertEquals("\uE31B", MaterialSymbols.glyphFor("keyboard_return"))
        assertTrue(MaterialSymbols.isKnown("mood"))
        assertTrue(MaterialSymbols.isKnown("content_paste"))
    }

    @Test
    fun newlyAddedNamesResolve() {
        assertTrue(MaterialSymbols.isKnown("subdirectory_arrow_left"))
        assertEquals("\uE5D9", MaterialSymbols.glyphFor("subdirectory_arrow_left"))
        assertTrue(MaterialSymbols.isKnown("home"))
        assertEquals("\uE88A", MaterialSymbols.glyphFor("home"))
    }

    @Test
    fun unknownNameFallsBackToNull() {
        assertNull(MaterialSymbols.glyphFor("not_a_symbol_xyz"))
        assertNull(MaterialSymbols.glyphFor(""))
        assertFalse(MaterialSymbols.isKnown("sf_symbol_placeholder"))
    }
}
