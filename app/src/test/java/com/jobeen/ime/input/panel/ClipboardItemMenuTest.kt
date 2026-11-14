// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel

import com.jobeen.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 剪贴板长按菜单模型（WO52 5.3）：条件出现／隐藏、顺序、收藏切换、搜索结果仅 Delete。 */
class ClipboardItemMenuTest {

    private fun ids(text: String, favorite: Boolean = false, search: Boolean = false, pinned: Boolean = false) =
        ClipboardItemMenu.itemsFor(text, favorite, search, isPinned = pinned).map { it.id }

    @Test
    fun plainText_nineItems_noLinkNoCode() {
        assertEquals(
            listOf(
                ClipboardItemMenu.Id.SPLIT_WORDS,
                ClipboardItemMenu.Id.SAVE_AS_PHRASE,
                ClipboardItemMenu.Id.FAVORITE,
                ClipboardItemMenu.Id.PIN,
                ClipboardItemMenu.Id.MOVE_TO_GROUP,
                ClipboardItemMenu.Id.EDIT,
                ClipboardItemMenu.Id.MULTI_SELECT,
                ClipboardItemMenu.Id.REORDER,
                ClipboardItemMenu.Id.DELETE,
            ),
            ids("hello world"),
        )
    }

    @Test
    fun urlText_openLinkFirst() {
        val items = ClipboardItemMenu.itemsFor("see https://example.com/a")
        assertEquals(ClipboardItemMenu.Id.OPEN_LINK, items.first().id)
        assertEquals(R.string.clipboard_open_link, items.first().labelRes)
        assertTrue(items.none { it.id == ClipboardItemMenu.Id.EXTRACT_CODE })
    }

    @Test
    fun codeText_extractCodeSecond() {
        val items = ClipboardItemMenu.itemsFor("您的验证码是123456，请勿泄露")
        assertEquals(ClipboardItemMenu.Id.EXTRACT_CODE, items.first().id)
        assertEquals(R.string.clipboard_extract_code, items.first().labelRes)
        assertTrue(items.none { it.id == ClipboardItemMenu.Id.OPEN_LINK })
    }

    @Test
    fun urlAndCode_linkThenCode() {
        val items = ClipboardItemMenu.itemsFor("验证码123456，见 https://example.com/a")
        assertEquals(
            listOf(
                ClipboardItemMenu.Id.OPEN_LINK,
                ClipboardItemMenu.Id.EXTRACT_CODE,
                ClipboardItemMenu.Id.SPLIT_WORDS,
            ),
            items.take(3).map { it.id },
        )
    }

    @Test
    fun emptyText_noSplitNoLinkNoCode() {
        val items = ClipboardItemMenu.itemsFor("")
        assertTrue(items.none { it.id == ClipboardItemMenu.Id.SPLIT_WORDS })
        assertTrue(items.none { it.id == ClipboardItemMenu.Id.OPEN_LINK })
        assertTrue(items.none { it.id == ClipboardItemMenu.Id.EXTRACT_CODE })
        assertEquals(8, items.size)
    }

    @Test
    fun favoriteToggle_labelSwitches() {
        val fav = ClipboardItemMenu.itemsFor("x", isFavorite = true)
            .first { it.id == ClipboardItemMenu.Id.FAVORITE }
        assertEquals(R.string.clipboard_remove_favorite, fav.labelRes)
        val unfav = ClipboardItemMenu.itemsFor("x", isFavorite = false)
            .first { it.id == ClipboardItemMenu.Id.FAVORITE }
        assertEquals(R.string.clipboard_favorite, unfav.labelRes)
    }

    @Test
    fun pinToggle_labelSwitches() {
        val pinned = ClipboardItemMenu.itemsFor("x", isPinned = true)
            .first { it.id == ClipboardItemMenu.Id.PIN }
        assertEquals(R.string.clipboard_unpin, pinned.labelRes)
        val unpinned = ClipboardItemMenu.itemsFor("x", isPinned = false)
            .first { it.id == ClipboardItemMenu.Id.PIN }
        assertEquals(R.string.clipboard_pin, unpinned.labelRes)
    }

    @Test
    fun searchResult_onlyDeleteResult() {
        val items = ClipboardItemMenu.itemsFor("https://example.com/a 123456", isSearchResult = true)
        assertEquals(1, items.size)
        assertEquals(ClipboardItemMenu.Id.DELETE_RESULT, items.single().id)
        assertEquals(R.string.clipboard_delete_result, items.single().labelRes)
        assertTrue(items.single().destructive)
    }

    @Test
    fun delete_isDestructive_onlyDelete() {
        val items = ClipboardItemMenu.itemsFor("hello")
        assertEquals(
            listOf(ClipboardItemMenu.Id.DELETE),
            items.filter { it.destructive }.map { it.id },
        )
    }
}
