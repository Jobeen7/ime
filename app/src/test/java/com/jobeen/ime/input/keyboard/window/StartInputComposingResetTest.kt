// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import com.jobeen.ime.engine.data.EngineMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新输入会话开始复位组字态（2026-11-11，文件传输助手回车键 ↵ 钉死）：
 * isComposing 只由引擎 Status/Commit 消息推进，换框时引擎不发清零
 * Status，旧组字态会一直钉住回车键显示为“上屏候选”（↵），换什么
 * 框都不变。startInput 在新会话开始时复位（见 resetComposingForNewInput），
 * 本测试钉住该复位语义。
 */
class StartInputComposingResetTest {

    private fun reset() {
        KeyboardStateManager.onDestroy()
    }

    @Test
    fun statusTrue_setsComposing() {
        reset()
        KeyboardStateManager.handleEngineMessage(EngineMessage.Status(isComposing = true))
        assertTrue(KeyboardStateManager.isComposingNow)
    }

    @Test
    fun resetComposingForNewInput_clearsStaleComposing() {
        reset()
        KeyboardStateManager.handleEngineMessage(EngineMessage.Status(isComposing = true))
        assertTrue(KeyboardStateManager.isComposingNow)
        // 新输入会话开始：旧组字态复位，回车键不再钉在“上屏候选”。
        KeyboardStateManager.resetComposingForNewInput()
        assertFalse(KeyboardStateManager.isComposingNow)
    }

    @Test
    fun statusFalse_clearsComposing() {
        reset()
        KeyboardStateManager.handleEngineMessage(EngineMessage.Status(isComposing = true))
        assertTrue(KeyboardStateManager.isComposingNow)
        KeyboardStateManager.handleEngineMessage(EngineMessage.Status(isComposing = false))
        assertFalse(KeyboardStateManager.isComposingNow)
    }

    @Test
    fun commit_clearsComposing() {
        reset()
        KeyboardStateManager.handleEngineMessage(EngineMessage.Status(isComposing = true))
        assertTrue(KeyboardStateManager.isComposingNow)
        KeyboardStateManager.handleEngineMessage(EngineMessage.Commit(text = "test"))
        assertFalse(KeyboardStateManager.isComposingNow)
    }

    @Test
    fun onDestroy_clearsComposing() {
        reset()
        KeyboardStateManager.handleEngineMessage(EngineMessage.Status(isComposing = true))
        assertTrue(KeyboardStateManager.isComposingNow)
        KeyboardStateManager.onDestroy()
        assertFalse(KeyboardStateManager.isComposingNow)
    }
}
