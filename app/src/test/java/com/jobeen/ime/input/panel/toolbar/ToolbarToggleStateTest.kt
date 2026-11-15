package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
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

}
