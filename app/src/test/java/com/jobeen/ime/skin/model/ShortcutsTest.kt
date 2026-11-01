package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新增 # 快捷指令：解析（白名单）与运行时映射。分发见窗口 transformed 分支（需真机/门验证）。 */
class ShortcutsTest {

    private fun parseTap(actionJson: String): SkinAction? {
        val parsed = SkinKeyMappingsParser.parse(
            Json.parseToJsonElement(
                """{"KEY_Q": {"tap": $actionJson}}""",
            ) as JsonObject,
            isOverridable = { true },
            isAnchor = { false },
        )
        return parsed.overrides["KEY_Q"]?.tap
    }

    @Test
    fun keyboardMenuParsesAndMaps() {
        val parsed = parseTap("""{"type": "command", "action": "#keyboardMenu"}""")
        assertEquals(
            SkinAction.Command(WhitelistAction.HASH_KEYBOARD_MENU),
            parsed,
        )
        assertEquals(
            KeyboardAction.KeyboardMenuAction,
            KeyOverrideApplier.toRuntime(parsed!!),
        )
    }

    @Test
    fun symbolBarAndCommandViewParseAndMapToStubs() {
        // 目标 UI 不在本轮：映射为显式空操作动作，分发时记日志，
        // 不伪造界面（见 KeyboardWindowView.transformed 分支）。
        val symbolBar = parseTap("""{"type": "command", "action": "#toggleSymbolBar"}""")
        assertEquals(
            SkinAction.Command(WhitelistAction.HASH_TOGGLE_SYMBOL_BAR),
            symbolBar,
        )
        assertEquals(
            KeyboardAction.ToggleSymbolBarAction,
            KeyOverrideApplier.toRuntime(symbolBar!!),
        )
        val commandView = parseTap("""{"type": "command", "action": "#toggleCommandView"}""")
        assertEquals(
            SkinAction.Command(WhitelistAction.HASH_TOGGLE_COMMAND_VIEW),
            commandView,
        )
        assertEquals(
            KeyboardAction.ToggleCommandViewAction,
            KeyOverrideApplier.toRuntime(commandView!!),
        )
    }
}
