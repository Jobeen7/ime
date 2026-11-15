// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 12-⑨：键盘菜单管理——增删排序（顺序持久化）＋分页。 */
class KeyboardMenuConfigTest {

    @Test
    fun defaultIds_matchRegistrySixteenInOrder() {
        val ids = KeyboardMenuConfigOps.defaultIds()
        assertEquals(16, ids.size)
        assertEquals("emoji", ids.first())
        assertEquals("performance", ids.last())
        assertEquals(KeyboardMenuEntries.ALL.map { it.id }, ids)
    }

    @Test
    fun resolve_dropsUnknownAndDupes() {
        val got = KeyboardMenuConfigOps.resolve(
            listOf("emoji", "nope", "emoji", "voice"),
        )
        assertEquals(listOf("emoji", "voice"), got)
    }

    @Test
    fun resolve_emptyStaysEmpty_nullFallsBackToDefault() {
        // C-L-40：删光就是空菜单，不再回落默认。
        assertEquals(emptyList<String>(), KeyboardMenuConfigOps.resolve(emptyList()))
        // 全是未知 id 亦为空。
        assertEquals(emptyList<String>(), KeyboardMenuConfigOps.resolve(listOf("nope")))
        // 未配置过（null）仍回落默认——首次安装菜单不空白。
        assertEquals(
            KeyboardMenuConfigOps.defaultIds(),
            KeyboardMenuConfigOps.resolve(null),
        )
    }

    @Test
    fun removeLastItem_thenRestoreDefault_returnsSixteen() {
        // C-L-40：删到最后一项后菜单为空；恢复默认回 16 项。
        var ids = KeyboardMenuConfigOps.defaultIds()
        for (id in KeyboardMenuConfigOps.defaultIds()) {
            ids = KeyboardMenuConfigOps.remove(ids, id)
        }
        assertEquals(emptyList<String>(), KeyboardMenuConfigOps.resolve(ids))
        val store = InMemoryKeyboardMenuStore(ids)
        assertEquals(emptyList<String>(), store.load())
        store.save(KeyboardMenuConfigOps.defaultIds())
        assertEquals(16, store.load().size)
    }

    @Test
    fun pages_chunkedEight() {
        val pages = KeyboardMenuConfigOps.pages(KeyboardMenuConfigOps.defaultIds())
        assertEquals(2, pages.size)
        assertEquals(8, pages[0].size)
        assertEquals(8, pages[1].size)
        assertEquals("clipboard", pages[1].first())
    }

    @Test
    fun add_rejectsUnknownAndDuplicate() {
        val ids = listOf("emoji", "voice")
        assertEquals(listOf("emoji", "voice", "clipboard"), KeyboardMenuConfigOps.add(ids, "clipboard"))
        assertEquals(ids, KeyboardMenuConfigOps.add(ids, "emoji"))
        assertEquals(ids, KeyboardMenuConfigOps.add(ids, "nope"))
    }

    @Test
    fun remove_dropsOnlyThatId() {
        val ids = listOf("emoji", "voice", "clipboard")
        assertEquals(listOf("emoji", "clipboard"), KeyboardMenuConfigOps.remove(ids, "voice"))
        assertEquals(ids, KeyboardMenuConfigOps.remove(ids, "nope"))
    }

    @Test
    fun move_reordersAndRejectsOutOfRange() {
        val ids = listOf("emoji", "voice", "clipboard")
        assertEquals(
            listOf("voice", "emoji", "clipboard"),
            KeyboardMenuConfigOps.move(ids, 0, 1),
        )
        assertEquals(ids, KeyboardMenuConfigOps.move(ids, 0, 0))
        assertEquals(ids, KeyboardMenuConfigOps.move(ids, 0, 9))
    }

    @Test
    fun editRoundTrip_orderPersists() {
        // 增删排序后顺序持久化：删 voice、把 clipboard 移到首位、加 theme。
        var ids = KeyboardMenuConfigOps.defaultIds()
        ids = KeyboardMenuConfigOps.remove(ids, "voice")
        ids = KeyboardMenuConfigOps.move(ids, ids.indexOf("clipboard"), 0)
        val store = InMemoryKeyboardMenuStore()
        store.save(ids)
        val loaded = store.load()
        assertEquals("clipboard", loaded.first())
        assertTrue("voice" !in loaded)
        assertEquals(15, loaded.size)
        // 再加回 voice：追加到末尾。
        store.save(KeyboardMenuConfigOps.add(loaded, "voice"))
        assertEquals("voice", store.load().last())
    }

    @Test
    fun serializeParse_roundTrip() {
        val ids = listOf("emoji", "voice")
        assertEquals(ids, KeyboardMenuConfigOps.parse(KeyboardMenuConfigOps.serialize(ids)))
    }

    @Test
    fun parse_corruptOrBlank_returnsNull() {
        assertNull(KeyboardMenuConfigOps.parse(null))
        assertNull(KeyboardMenuConfigOps.parse(""))
        assertNull(KeyboardMenuConfigOps.parse("not json"))
        assertNull(KeyboardMenuConfigOps.parse("{}"))
    }
}
