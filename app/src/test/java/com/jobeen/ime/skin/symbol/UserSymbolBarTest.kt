// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 用户自定义常用符号栏（施工单 10 H2-5）：纯函数＋内存存储。 */
class UserSymbolBarTest {

    private val config = UserSymbolBarConfig(
        symbols = listOf("。", "，", "、"),
        pinned = true,
    )

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
        val full = UserSymbolBarConfig((1..20).map { "符$it" }, pinned = true)
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
    fun setPinned_isNoopWhenUnchanged() {
        assertEquals(false, UserSymbolBarOps.setPinned(config, false).pinned)
        assertSame(config, UserSymbolBarOps.setPinned(config, true))
    }

    @Test
    fun topStrip_onlyWhenPinnedAndNonEmpty() {
        assertEquals(listOf("。", "，", "、"), UserSymbolBarOps.topStrip(config))
        assertNull(UserSymbolBarOps.topStrip(config.copy(pinned = false)))
        assertNull(UserSymbolBarOps.topStrip(UserSymbolBarConfig()))
        assertNull(UserSymbolBarOps.topStrip(UserSymbolBarConfig(pinned = false)))
    }

    @Test
    fun serializeParse_roundTrip() {
        val parsed = UserSymbolBarOps.parse(UserSymbolBarOps.serialize(config))
        assertEquals(config, parsed)
    }

    @Test
    fun parse_invalidFallsBackToDefault() {
        assertEquals(UserSymbolBarConfig(), UserSymbolBarOps.parse(null))
        assertEquals(UserSymbolBarConfig(), UserSymbolBarOps.parse(""))
        assertEquals(UserSymbolBarConfig(), UserSymbolBarOps.parse("not json"))
        assertEquals(UserSymbolBarConfig(), UserSymbolBarOps.parse("[]"))
        // 缺失 pinned 时默认置顶开；非法符号项丢弃。
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。"), pinned = true),
            UserSymbolBarOps.parse("""{"symbols":["。", "", 1]}"""),
        )
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。"), pinned = false),
            UserSymbolBarOps.parse("""{"pinned":false,"symbols":["。"]}"""),
        )
    }

    @Test
    fun inMemoryStore_roundTrip() {
        val store = InMemoryUserSymbolBarStore()
        assertEquals(UserSymbolBarConfig(), store.load())
        store.save(config)
        assertEquals(config, store.load())
    }
}
