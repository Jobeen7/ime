package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具栏开关态（E6）：开关动作集合与着色判定（纯函数部分）。 */
class ToolbarToggleStateTest {

    @Test
    fun toggleActionsAreExactlyFive() {
        val toggles = listOf(
            PanelAction.TogglePrediction,
            PanelAction.ToggleShowComment,
            PanelAction.ToggleTraditionalChinese,
            PanelAction.ToggleEmojiInput,
            PanelAction.ToggleAsciiMode,
        )
        toggles.forEach { assertTrue(ToolbarToggleState.isToggle(it)) }
        // 非开关动作（菜单／剪贴板／键盘切换）不在集合内。
        assertFalse(ToolbarToggleState.isToggle(PanelAction.Clipboard))
        assertFalse(ToolbarToggleState.isToggle(PanelAction.SwitchKeyboard))
        assertFalse(ToolbarToggleState.isToggle(PanelAction.EmojiKeyboard))
    }

    @Test
    fun tintForUsesActivedOnlyForActiveToggle() {
        val actived = 0xFF0A84FF.toInt()
        val normal = 0xFF000000.toInt()
        assertEquals(
            actived,
            ToolbarToggleState.tintFor(PanelAction.TogglePrediction, true, actived, normal),
        )
        assertEquals(
            normal,
            ToolbarToggleState.tintFor(PanelAction.TogglePrediction, false, actived, normal),
        )
        assertEquals(
            normal,
            ToolbarToggleState.tintFor(PanelAction.Clipboard, true, actived, normal),
        )
    }
}
