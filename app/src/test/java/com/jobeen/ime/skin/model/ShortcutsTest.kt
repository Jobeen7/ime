package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.runtime.KeyActionMapping
import org.junit.Assert.assertEquals
import org.junit.Test

/** # 快捷指令运行时映射（键映射解析器已随皮肤 v3 死壳删除，动作直接构造）。分发见窗口 transformed 分支（需真机/门验证）。 */
class ShortcutsTest {

    @Test
    fun keyboardMenuMaps() {
        assertEquals(
            KeyboardAction.KeyboardMenuAction,
            KeyActionMapping.toRuntime(SkinAction.Command(WhitelistAction.HASH_KEYBOARD_MENU)),
        )
    }

    @Test
    fun symbolBarAndCommandViewMapToStubs() {
        // 目标 UI 不在本轮：映射为显式空操作动作，分发时记日志，
        // 不伪造界面（见 KeyboardWindowView.transformed 分支）。
        assertEquals(
            KeyboardAction.ToggleSymbolBarAction,
            KeyActionMapping.toRuntime(SkinAction.Command(WhitelistAction.HASH_TOGGLE_SYMBOL_BAR)),
        )
        assertEquals(
            KeyboardAction.ToggleCommandViewAction,
            KeyActionMapping.toRuntime(SkinAction.Command(WhitelistAction.HASH_TOGGLE_COMMAND_VIEW)),
        )
    }
}
