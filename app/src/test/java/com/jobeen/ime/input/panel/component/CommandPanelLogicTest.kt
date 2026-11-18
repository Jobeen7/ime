// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import com.jobeen.ime.data.ai.AiStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令面板纯逻辑单测：输入取数口径（选中优先）／流错误文案口径
 * （与设置页同款）／空白结果不回填。
 */
class CommandPanelLogicTest {

    @Test
    fun resolveInputPrefersSelectedText() {
        assertEquals("选中", CommandPanelView.resolveInput("选中", "剪贴板"))
        assertEquals("剪贴板", CommandPanelView.resolveInput("", "剪贴板"))
        assertEquals("剪贴板", CommandPanelView.resolveInput("   ", "剪贴板"))
        assertEquals("", CommandPanelView.resolveInput("", ""))
    }

    @Test
    fun resolveErrorTextMatchesSettingsPage() {
        // messageRes 优先
        assertEquals(
            "res:123",
            CommandPanelView.resolveErrorText(
                AiStreamEvent.Error(message = "m", code = "network", messageRes = 123),
                "缺 token",
            ) { "res:$it" },
        )
        // missing_token 用缺 token 文案
        assertEquals(
            "缺 token",
            CommandPanelView.resolveErrorText(
                AiStreamEvent.Error(message = "", code = "missing_token"),
                "缺 token",
            ) { "res:$it" },
        )
        // 其余用事件自带 message
        assertEquals(
            "断网了",
            CommandPanelView.resolveErrorText(
                AiStreamEvent.Error(message = "断网了", code = "network"),
                "缺 token",
            ) { "res:$it" },
        )
    }

    @Test
    fun shouldCommitOnlyNonBlankResult() {
        assertTrue(CommandPanelView.shouldCommit("润色后"))
        assertFalse(CommandPanelView.shouldCommit(""))
        assertFalse(CommandPanelView.shouldCommit("   "))
    }
}
