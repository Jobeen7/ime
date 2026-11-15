// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.input.keyboard.impl.insertIntoSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B-EM-2：Emoji 搜索框插字（搜索态按键进 EditText 的变换部分；
 * 接线本身是 KawaiiPanel.interceptCommit → EmojiKeyboard.appendSearchText，
 * 与剪贴板搜索态拦截同款，无主机单测覆盖，真机验收）。
 */
class EmojiSearchInputTest {

    @Test
    fun appendsAtEnd() {
        assertEquals("ab" to 2, insertIntoSearchQuery("", 0, "ab"))
        assertEquals("hello!" to 6, insertIntoSearchQuery("hello", 5, "!"))
    }

    @Test
    fun insertsAtCursor() {
        assertEquals("aXbc" to 2, insertIntoSearchQuery("abc", 1, "X"))
        assertEquals("ab" to 2, insertIntoSearchQuery("ab", 99, ""))
    }

    @Test
    fun newlinesStrippedLikeClipSearch() {
        assertEquals("ab" to 2, insertIntoSearchQuery("", 0, "a\nb\r"))
        // 全是换行：查询不动
        assertEquals("ab" to 1, insertIntoSearchQuery("ab", 1, "\n\r"))
    }

    @Test
    fun cursorClamped() {
        assertEquals("Xab" to 1, insertIntoSearchQuery("ab", -5, "X"))
        assertEquals("abX" to 3, insertIntoSearchQuery("ab", 99, "X"))
    }
}
