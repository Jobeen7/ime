// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 7.4：用户自定义 Emoji 键盘——分组／顺序／内容编辑（存储＋加载）。 */
class EmojiCustomKeyboardTest {

    private fun config() = EmojiCustomConfig(
        listOf(
            EmojiCustomGroup("我的", listOf("😀", "😂")),
            EmojiCustomGroup("动物", listOf("🐶", "🐱")),
        ),
    )

    @Test
    fun seedGroups_matchBuiltinNineGroups() {
        val groups = EmojiCustomOps.seedGroups()
        assertEquals(9, groups.size)
        assertEquals("😀", groups[0].label)
        assertEquals(170, groups[0].symbols.size)
        assertEquals(1925, groups.sumOf { it.symbols.size })
    }

    @Test
    fun addGroup_rejectsBlankDuplicateAndKeepsOrder() {
        val added = EmojiCustomOps.addGroup(config(), "食物")
        assertEquals(listOf("我的", "动物", "食物"), added.groups.map { it.label })
        assertEquals(config(), EmojiCustomOps.addGroup(config(), ""))
        assertEquals(config(), EmojiCustomOps.addGroup(config(), "我的"))
    }

    @Test
    fun renameGroup_rejectsBlankDuplicateOutOfRange() {
        val renamed = EmojiCustomOps.renameGroup(config(), 0, "常用")
        assertEquals("常用", renamed.groups[0].label)
        assertEquals(config(), EmojiCustomOps.renameGroup(config(), 0, ""))
        assertEquals(config(), EmojiCustomOps.renameGroup(config(), 0, "动物"))
        assertEquals(config(), EmojiCustomOps.renameGroup(config(), 9, "常用"))
    }

    @Test
    fun deleteGroup_removesOnlyThatGroup() {
        val next = EmojiCustomOps.deleteGroup(config(), 0)
        assertEquals(listOf("动物"), next.groups.map { it.label })
        assertEquals(config(), EmojiCustomOps.deleteGroup(config(), 9))
    }

    @Test
    fun moveGroup_reordersAndRejectsOutOfRange() {
        val moved = EmojiCustomOps.moveGroup(config(), 0, 1)
        assertEquals(listOf("动物", "我的"), moved.groups.map { it.label })
        assertEquals(config(), EmojiCustomOps.moveGroup(config(), 0, 0))
        assertEquals(config(), EmojiCustomOps.moveGroup(config(), 0, 9))
    }

    @Test
    fun addSymbol_rejectsBlankDuplicateAndAppends() {
        val added = EmojiCustomOps.addSymbol(config(), 0, "🥰")
        assertEquals(listOf("😀", "😂", "🥰"), added.groups[0].symbols)
        assertEquals(config(), EmojiCustomOps.addSymbol(config(), 0, ""))
        assertEquals(config(), EmojiCustomOps.addSymbol(config(), 0, "😀"))
        assertEquals(config(), EmojiCustomOps.addSymbol(config(), 9, "🥰"))
    }

    @Test
    fun removeSymbol_removesOnlyThatSymbol() {
        val next = EmojiCustomOps.removeSymbol(config(), 1, 0)
        assertEquals(listOf("🐱"), next.groups[1].symbols)
        assertEquals(config(), EmojiCustomOps.removeSymbol(config(), 1, 9))
    }

    @Test
    fun moveSymbol_reordersWithinGroup() {
        val moved = EmojiCustomOps.moveSymbol(config(), 0, 0, 1)
        assertEquals(listOf("😂", "😀"), moved.groups[0].symbols)
        assertEquals(config(), EmojiCustomOps.moveSymbol(config(), 0, 0, 0))
    }

    @Test
    fun editRoundTrip_storageAndLoad() {
        // 编辑后分组／顺序／内容生效：改名＋移组＋加符号，存盘再读回一致。
        var edited = EmojiCustomOps.renameGroup(config(), 0, "常用")
        edited = EmojiCustomOps.moveGroup(edited, 0, 1)
        edited = EmojiCustomOps.addSymbol(edited, 1, "🥰")
        val store = InMemoryEmojiCustomStore()
        assertNull(store.load())
        store.save(edited)
        val loaded = store.load()!!
        assertEquals(listOf("动物", "常用"), loaded.groups.map { it.label })
        assertEquals(listOf("😀", "😂", "🥰"), loaded.groups[1].symbols)
        store.save(null)
        assertNull(store.load())
    }

    @Test
    fun serializeParse_roundTrip() {
        val parsed = EmojiCustomOps.parse(EmojiCustomOps.serialize(config()))
        assertEquals(config(), parsed)
    }

    @Test
    fun parse_corruptOrEmpty_returnsNull() {
        assertNull(EmojiCustomOps.parse(null))
        assertNull(EmojiCustomOps.parse(""))
        assertNull(EmojiCustomOps.parse("not json"))
        assertNull(EmojiCustomOps.parse("""{"groups":[]}"""))
        assertNull(EmojiCustomOps.parse("""{"groups":[{"label":"","symbols":[]}]}"""))
    }

    @Test
    fun normalize_dropsBlankLabelsAndDupes() {
        val groups = EmojiCustomOps.normalize(
            listOf(
                EmojiCustomGroup("  ", listOf("😀")),
                EmojiCustomGroup("我的", listOf("😀", "", "😀", "😂")),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("😀", "😂"), groups[0].symbols)
    }

    @Test
    fun normalize_keepsLargeBuiltinGroupsWhole() {
        // B-EM-1：删 200 上限后，首次启用的 9 组归一化后 1920 条
        // （源数据 1925 条，组内去空去重 5 条），一个不少。
        val normalized = EmojiCustomOps.normalize(EmojiCustomOps.seedGroups())
        assertEquals(9, normalized.size)
        assertEquals(1920, normalized.sumOf { it.symbols.size })
        assertEquals(389, normalized.maxOf { it.symbols.size })
    }

    @Test
    fun addSymbol_noLongerCapsAtTwoHundred() {
        val big = EmojiCustomGroup("大组", (1..250).map { "x$it" })
        val config = EmojiCustomConfig(listOf(big))
        val next = EmojiCustomOps.addSymbol(config, 0, "x251")
        assertEquals(251, next.groups[0].symbols.size)
    }
}
