// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** T9 快捷符号（9 键快捷符号槽，默认 6 符号、上限 12 槽）：纯函数＋内存存储。 */
class T9QuickSymbolsTest {

    private val config = T9QuickSymbolsConfig(
        symbols = listOf("。", "，", "、"),
    )

    @Test
    fun defaultSymbols_hasSix() {
        assertEquals(6, T9QuickSymbolsOps.DEFAULT_SYMBOLS.size)
        assertEquals(
            listOf("，", "。", "？", "！", "、", "…"),
            T9QuickSymbolsOps.DEFAULT_SYMBOLS,
        )
        assertEquals(
            T9QuickSymbolsConfig(symbols = T9QuickSymbolsOps.DEFAULT_SYMBOLS),
            T9QuickSymbolsOps.defaultConfig(),
        )
        assertEquals(12, T9QuickSymbolsOps.MAX_SLOTS)
    }

    @Test
    fun normalize_trimsDropsBlanksDedupesCapsAtTwelve() {
        assertEquals(
            listOf("。", "，", "、"),
            T9QuickSymbolsOps.normalize(listOf(" 。 ", "", "，", "。", "、", "  ")),
        )
        val many = (1..20).map { "符$it" }
        assertEquals(12, T9QuickSymbolsOps.normalize(many).size)
        assertEquals("符12", T9QuickSymbolsOps.normalize(many).last())
    }

    @Test
    fun add_appendsAndIgnoresBlankDuplicateOrFull() {
        assertEquals(
            listOf("。", "，", "、", "！"),
            T9QuickSymbolsOps.add(config, "！").symbols,
        )
        assertSame(config, T9QuickSymbolsOps.add(config, "。"))
        assertSame(config, T9QuickSymbolsOps.add(config, "   "))
        val full = T9QuickSymbolsConfig(symbols = (1..12).map { "符$it" })
        assertSame(full, T9QuickSymbolsOps.add(full, "新"))
    }

    @Test
    fun removeAtOrRestoreDefault_restoresDefaultWhenEmptied() {
        assertEquals(
            listOf("。", "、"),
            T9QuickSymbolsOps.removeAt(config, 1).symbols,
        )
        assertSame(config, T9QuickSymbolsOps.removeAt(config, 9))
        val single = T9QuickSymbolsConfig(symbols = listOf("。"))
        assertEquals(
            T9QuickSymbolsOps.defaultConfig(),
            T9QuickSymbolsOps.removeAtOrRestoreDefault(single, 0),
        )
    }

    @Test
    fun move_reordersAndIgnoresBadIndex() {
        assertEquals(
            listOf("，", "。", "、"),
            T9QuickSymbolsOps.move(config, 0, 1).symbols,
        )
        assertSame(config, T9QuickSymbolsOps.move(config, 0, 0))
        assertSame(config, T9QuickSymbolsOps.move(config, 0, 9))
    }

    @Test
    fun parse_fallsBackToDefaultOnMissingCorruptOrEmpty() {
        assertEquals(T9QuickSymbolsOps.defaultConfig(), T9QuickSymbolsOps.parse(null))
        assertEquals(T9QuickSymbolsOps.defaultConfig(), T9QuickSymbolsOps.parse(""))
        assertEquals(T9QuickSymbolsOps.defaultConfig(), T9QuickSymbolsOps.parse("not json"))
        assertEquals(T9QuickSymbolsOps.defaultConfig(), T9QuickSymbolsOps.parse("{\"symbols\":[]}"))
        assertEquals(
            T9QuickSymbolsConfig(symbols = listOf("。", "，")),
            T9QuickSymbolsOps.parse("{\"symbols\":[\"。\",\"，\",\"。\"]}"),
        )
    }

    @Test
    fun serialize_roundTrips() {
        val raw = T9QuickSymbolsOps.serialize(config)
        assertEquals(config, T9QuickSymbolsOps.parse(raw))
        val store = InMemoryT9QuickSymbolsStore()
        assertEquals(T9QuickSymbolsOps.defaultConfig(), store.load())
        store.save(config)
        assertEquals(config, store.load())
    }
}
