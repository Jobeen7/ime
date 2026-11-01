package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 键盘类型切换动作（纠正 2）：与中英切换并存的独立机制。
 * 中英切换（toggle_ascii_mode）仍走 ascii 偏好翻转，不走这里；
 * 本测试只钉 keyboardType 节点的解析与运行时映射。
 */
class KeyboardTypeActionTest {

    private fun parse(json: String): SkinKeyMappingsParser.SingleActionResult {
        val node = Json.parseToJsonElement(json)
        return SkinKeyMappingsParser.parseSingleAction(KeySlot.TAP, node)
    }

    @Test
    fun keyboardTypeParses() {
        val result = parse("""{"type": "keyboardType", "value": "alphabetic"}""")
        assertEquals(SkinAction.KeyboardType("alphabetic"), result.action)
        assertNull(result.rejectReason)
    }

    @Test
    fun keyboardTypeAcceptsCustomNames() {
        val result = parse("""{"type": "keyboardType", "value": "myBoard"}""")
        assertEquals(SkinAction.KeyboardType("myBoard"), result.action)
    }

    @Test
    fun keyboardTypeMissingOrBlankValueRejected() {
        assertEquals(
            BindingRejectReason.VALUE_MISSING,
            parse("""{"type": "keyboardType"}""").rejectReason,
        )
        assertEquals(
            BindingRejectReason.VALUE_INVALID,
            parse("""{"type": "keyboardType", "value": "  "}""").rejectReason,
        )
    }

    @Test
    fun keyboardTypeMapsToRuntimeAction() {
        assertEquals(
            KeyboardAction.SwitchKeyboardTypeAction("alphabetic"),
            KeyOverrideApplier.toRuntime(SkinAction.KeyboardType("alphabetic")),
        )
    }

    @Test
    fun asciiToggleMappingUnchanged() {
        // 中英切换映射不受本项影响：仍为 ToggleAsciiModeAction。
        assertEquals(
            KeyboardAction.ToggleAsciiModeAction,
            KeyOverrideApplier.toRuntime(
                SkinAction.Command(WhitelistAction.TOGGLE_ASCII_MODE),
            ),
        )
    }
}
