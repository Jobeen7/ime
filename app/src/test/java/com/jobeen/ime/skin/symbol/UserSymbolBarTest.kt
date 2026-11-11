// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 用户自定义常用符号栏（施工单 10 H2-5；2026-11-12 符号栏重做：删置顶、默认 16 符号）：纯函数＋内存存储。 */
class UserSymbolBarTest {

    private val config = UserSymbolBarConfig(
        symbols = listOf("。", "，", "、"),
    )

    @Test
    fun defaultSymbols_hasSixteen() {
        assertEquals(16, UserSymbolBarOps.DEFAULT_SYMBOLS.size)
        assertEquals(
            listOf("，", "。", "？", "！", "…", "～", "、", "=", "+", "-", "*", "/", "%", "#", "@", "&"),
            UserSymbolBarOps.DEFAULT_SYMBOLS,
        )
        assertEquals(
            UserSymbolBarConfig(symbols = UserSymbolBarOps.DEFAULT_SYMBOLS),
            UserSymbolBarOps.defaultConfig(),
        )
    }

    @Test
    fun normalize_trimsDropsBlanksDedupesCaps() {
        assertEquals(
            listOf("。", "，", "、"),
            UserSymbolBarOps.normalize(listOf(" 。 ", "", "，", "。", "、", "  ")),
        )
        val many = (1..25).map { "符$it" }
        assertEquals(20, UserSymbolBarOps.normalize(many).size)
        assertEquals("符20", UserSymbolBarOps.normalize(many).last())
    }

    @Test
    fun add_appendsAndIgnoresBlankOrDuplicate() {
        assertEquals(
            listOf("。", "，", "、", "！"),
            UserSymbolBarOps.add(config, "！").symbols,
        )
        assertSame(config, UserSymbolBarOps.add(config, "。"))
        assertSame(config, UserSymbolBarOps.add(config, "   "))
    }

    @Test
    fun add_atCapIsNoop() {
        val full = UserSymbolBarConfig((1..20).map { "符$it" })
        assertSame(full, UserSymbolBarOps.add(full, "新"))
    }

    @Test
    fun removeAt_removesAndIgnoresOutOfBounds() {
        assertEquals(
            listOf("。", "、"),
            UserSymbolBarOps.removeAt(config, 1).symbols,
        )
        assertSame(config, UserSymbolBarOps.removeAt(config, -1))
        assertSame(config, UserSymbolBarOps.removeAt(config, 3))
    }

    @Test
    fun move_reordersAndIgnoresOutOfBoundsOrSame() {
        assertEquals(
            listOf("，", "、", "。"),
            UserSymbolBarOps.move(config, 0, 2).symbols,
        )
        assertEquals(
            listOf("、", "。", "，"),
            UserSymbolBarOps.move(config, 2, 0).symbols,
        )
        assertSame(config, UserSymbolBarOps.move(config, 0, 0))
        assertSame(config, UserSymbolBarOps.move(config, -1, 1))
        assertSame(config, UserSymbolBarOps.move(config, 0, 3))
    }

    @Test
    fun serializeParse_roundTrip() {
        val parsed = UserSymbolBarOps.parse(UserSymbolBarOps.serialize(config))
        assertEquals(config, parsed)
    }

    @Test
    fun parse_invalidFallsBackToDefault() {
        val defaults = UserSymbolBarOps.defaultConfig()
        assertEquals(defaults, UserSymbolBarOps.parse(null))
        assertEquals(defaults, UserSymbolBarOps.parse(""))
        assertEquals(defaults, UserSymbolBarOps.parse("not json"))
        assertEquals(defaults, UserSymbolBarOps.parse("[]"))
        // 非法符号项丢弃；旧 pinned 字段直接忽略。
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。")),
            UserSymbolBarOps.parse("""{"symbols":["。", "", 1]}"""),
        )
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。")),
            UserSymbolBarOps.parse("""{"pinned":false,"symbols":["。"]}"""),
        )
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。")),
            UserSymbolBarOps.parse("""{"pinned":true,"symbols":["。"]}"""),
        )
        // 用户主动删空的已存空列原样保留，不补默认。
        assertEquals(
            UserSymbolBarConfig(symbols = emptyList()),
            UserSymbolBarOps.parse("""{"symbols":[]}"""),
        )
    }

    @Test
    fun inMemoryStore_roundTrip() {
        val store = InMemoryUserSymbolBarStore()
        assertEquals(UserSymbolBarOps.defaultConfig(), store.load())
        store.save(config)
        assertEquals(config, store.load())
    }
}
