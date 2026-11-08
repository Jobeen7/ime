// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.panel.menu.KeyboardMenuEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音总开关关闭时语音入口隐藏（H2-13，CyIME D22/F7）：
 * 工具栏池钮与菜单格共用同一纯判定；渲染器实例化依赖 Android
 * Paint，JVM 单测只覆盖纯函数，绘制／命中接线以编译＋真机为准
 * （与 ToolbarRendererHiddenTest 同口径）。
 */
class ToolbarVoiceVisibilityTest {

    @Test
    fun voice_hiddenWhenSwitchOff() {
        assertFalse(isToolbarActionVisible(PanelAction.ToggleVoice, false))
    }

    @Test
    fun voice_visibleWhenSwitchOn() {
        assertTrue(isToolbarActionVisible(PanelAction.ToggleVoice, true))
    }

    @Test
    fun otherPoolActions_visibleWhenSwitchOff() {
        for (item in ToolbarConfig.POOL) {
            if (item.action == PanelAction.ToggleVoice) continue
            assertTrue(item.id, isToolbarActionVisible(item.action, false))
        }
    }

    @Test
    fun toolbarList_voiceRemovedWhenOff_orderKept() {
        val actions = listOf(
            PanelAction.EmojiKeyboard,
            PanelAction.ToggleVoice,
            PanelAction.Copy,
            PanelAction.Clipboard,
        )
        val visible = actions.filter { isToolbarActionVisible(it, false) }
        assertEquals(
            listOf(PanelAction.EmojiKeyboard, PanelAction.Copy, PanelAction.Clipboard),
            visible,
        )
        assertEquals(actions, actions.filter { isToolbarActionVisible(it, true) })
    }

    @Test
    fun menuPages_voiceRemovedFromPageOneWhenOff() {
        val pages = KeyboardMenuEntries.PAGES.map { page ->
            page.filter { isToolbarActionVisible(it.action, false) }
        }
        // 第 1 页 8 项去语音剩 7 项，第 2 页 8 项不变；顺序不变。
        assertEquals(7, pages[0].size)
        assertEquals(8, pages[1].size)
        assertFalse(pages.flatten().any { it.action == PanelAction.ToggleVoice })
        assertEquals("emoji", pages[0].first().id)
        assertEquals("emoji_input", pages[0].last().id)
    }

    @Test
    fun menuPages_unchangedWhenOn() {
        val pages = KeyboardMenuEntries.PAGES.map { page ->
            page.filter { isToolbarActionVisible(it.action, true) }
        }
        assertEquals(KeyboardMenuEntries.PAGES, pages)
    }
}
