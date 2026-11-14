// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 7.1：Emoji 最近使用——写入／读取／上限／重置（纯逻辑＋内存存储）。 */
class RecentEmojiTest {

    @Test
    fun record_putsNewFirstAndDedupes() {
        val recents = RecentEmojiOps.record(listOf("😀", "😂"), "😂")
        assertEquals(listOf("😂", "😀"), recents)
        val added = RecentEmojiOps.record(recents, "🥰")
        assertEquals(listOf("🥰", "😂", "😀"), added)
    }

    @Test
    fun record_emptyEmojiIsNoop() {
        assertEquals(listOf("😀"), RecentEmojiOps.record(listOf("😀"), ""))
    }

    @Test
    fun record_capsAtThirty_jimeDefinedLimit() {
        // 上限 30 条是 Jime 自定（施工单未载明）：第 31 个最旧的被挤出。
        val full = (1..30).map { "e$it" }
        val next = RecentEmojiOps.record(full, "new")
        assertEquals(30, next.size)
        assertEquals("new", next.first())
        assertTrue("e1" in next)
        assertTrue("e30" !in next)
    }

    @Test
    fun reset_returnsEmpty() {
        assertTrue(RecentEmojiOps.reset().isEmpty())
    }

    @Test
    fun normalize_dropsBlankAndDupesAndCaps() {
        val got = RecentEmojiOps.normalize(listOf("", "😀", "😀", "😂"))
        assertEquals(listOf("😀", "😂"), got)
    }

    @Test
    fun serializeParse_roundTrip() {
        val recents = listOf("😀", "😂", "🥰")
        assertEquals(recents, RecentEmojiOps.parse(RecentEmojiOps.serialize(recents)))
    }

    @Test
    fun parse_corruptOrBlank_returnsEmpty() {
        assertTrue(RecentEmojiOps.parse(null).isEmpty())
        assertTrue(RecentEmojiOps.parse("").isEmpty())
        assertTrue(RecentEmojiOps.parse("not json").isEmpty())
        assertTrue(RecentEmojiOps.parse("{\"a\":1}").isEmpty())
    }

    @Test
    fun inMemoryStore_saveLoadClear() {
        val store = InMemoryRecentEmojiStore()
        assertTrue(store.load().isEmpty())
        store.save(listOf("😀", "😂"))
        assertEquals(listOf("😀", "😂"), store.load())
        store.clear()
        assertTrue(store.load().isEmpty())
    }
}
