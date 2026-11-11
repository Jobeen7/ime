// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.EnterField
import com.jobeen.ime.input.EnterFieldAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新输入会话开始确立当前字段（2026-11-11，.4 下同一字段
 * 文件传输助手一张纸飞机一张 ↵）：
 * 回车刷新只读 lastEditorInfo，而它此前只在 onInputChanged 里
 * 更新；startInput→switchTo 的刷新若读到上个字段的信息，会把
 * 上个字段的回车图标（单行 SEND 的纸飞机／NEXT 的 ＞）带到新
 * 字段，且 onInputChanged 未必随后纠正，旧图标就钉住。
 * beginInputSession 在会话开始即确立新字段（＋复位组字态），
 * 刷新即按新字段算。本测试钉住该确立语义。
 */
class BeginInputSessionFieldTest {

    private val multiLineSend = EnterField(
        isMultiLine = true,
        noEnterAction = false,
        action = EnterFieldAction.SEND,
    )
    private val singleLineSend = EnterField(
        isMultiLine = false,
        noEnterAction = false,
        action = EnterFieldAction.SEND,
    )
    private val singleLineNext = EnterField(
        isMultiLine = false,
        noEnterAction = false,
        action = EnterFieldAction.NEXT,
    )

    private fun reset() {
        KeyboardStateManager.onDestroy()
    }

    @Test
    fun beginInputSession_establishesMultiLineSendField() {
        reset()
        assertNull(KeyboardStateManager.currentEnterField)
        KeyboardStateManager.beginInputSession(multiLineSend)
        assertEquals(multiLineSend, KeyboardStateManager.currentEnterField)
    }

    @Test
    fun beginInputSession_replacesPreviousField() {
        reset()
        // 上个字段：单行 SEND（纸飞机）。切到多行 SEND 的新字段后，
        // 当前字段必须换成新字段，刷新不再按上个字段算。
        KeyboardStateManager.beginInputSession(singleLineSend)
        assertEquals(singleLineSend, KeyboardStateManager.currentEnterField)
        KeyboardStateManager.beginInputSession(multiLineSend)
        assertEquals(multiLineSend, KeyboardStateManager.currentEnterField)
    }

    @Test
    fun beginInputSession_singleLineNextReplacesMultiLine() {
        reset()
        KeyboardStateManager.beginInputSession(multiLineSend)
        KeyboardStateManager.beginInputSession(singleLineNext)
        assertEquals(singleLineNext, KeyboardStateManager.currentEnterField)
    }

    @Test
    fun beginInputSession_alsoResetsComposing() {
        reset()
        KeyboardStateManager.handleEngineMessage(EngineMessage.Status(isComposing = true))
        assertTrue(KeyboardStateManager.isComposingNow)
        KeyboardStateManager.beginInputSession(multiLineSend)
        assertFalse(KeyboardStateManager.isComposingNow)
        assertEquals(multiLineSend, KeyboardStateManager.currentEnterField)
    }

    @Test
    fun onDestroy_clearsCurrentField() {
        reset()
        KeyboardStateManager.beginInputSession(multiLineSend)
        assertEquals(multiLineSend, KeyboardStateManager.currentEnterField)
        KeyboardStateManager.onDestroy()
        assertNull(KeyboardStateManager.currentEnterField)
    }
}
