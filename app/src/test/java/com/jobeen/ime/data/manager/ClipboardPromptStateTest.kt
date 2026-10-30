// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 剪贴板提示条状态（接线轮 R4）：验证码优先显示、提交后清除、滞后不贴错。 */
class ClipboardPromptStateTest {

    @Test
    fun codePreferredOverFullText() {
        val state = ClipboardPromptState()
        state.onClipboardText("您的验证码是123456，请勿泄露")
        assertEquals("123456", state.displayText)
        assertEquals("123456", state.displayTextFor("您的验证码是123456，请勿泄露"))
    }

    @Test
    fun plainTextShownAsIs() {
        val state = ClipboardPromptState()
        state.onClipboardText("hello world")
        assertEquals("hello world", state.displayText)
        assertEquals("hello world", state.displayTextFor("hello world"))
    }

    @Test
    fun stalePromptNeverOverwritesOtherText() {
        val state = ClipboardPromptState()
        state.onClipboardText("您的验证码是123456，请勿泄露")
        // 提示态跟踪旧文本时，新文本仍显示原文，不贴旧验证码
        assertEquals("另一条消息", state.displayTextFor("另一条消息"))
    }

    @Test
    fun clearHidesPrompt() {
        val state = ClipboardPromptState()
        state.onClipboardText("您的验证码是123456，请勿泄露")
        state.clear()
        assertNull(state.displayText)
        assertEquals(
            "您的验证码是123456，请勿泄露",
            state.displayTextFor("您的验证码是123456，请勿泄露"),
        )
    }
}
