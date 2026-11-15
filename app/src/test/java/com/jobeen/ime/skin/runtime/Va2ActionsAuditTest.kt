package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WO52 8.3：va2 注册表 23 个动作逐项核对。
 *
 * 表中每行：va2 动作名 → Jime 白名单格式名 → 期望运行时动作；
 * 「不挂」表示 Jime 已定不做（两锁定态）或无可靠等价
 * （function，SkinKeyCodes 已定论），白名单中须查不到该名。
 *
 * 注意：va2 名与 Jime 格式名不同（如 backspace→delete），
 * 核对以 Jime 格式名解析＋分派为准。
 */
class Va2ActionsAuditTest {

    /** va2 名 →（Jime 格式名，期望运行时动作）。 */
    private val expected: List<Triple<String, String, KeyboardAction>> = listOf(
        Triple("backspace", "delete", KeyboardAction.BackspaceAction),
        Triple("deleteForward", "delete_forward", KeyboardAction.DeleteForwardAction),
        Triple("command", "command", KeyboardAction.AppKeyEventAction(117)),
        Triple("control", "control", KeyboardAction.AppKeyEventAction(113)),
        Triple("dictation", "voice_input", KeyboardAction.VoiceInputAction()),
        Triple("dismissKeyboard", "dismiss_keyboard", KeyboardAction.HideKeyboardAction),
        Triple("escape", "escape", KeyboardAction.AppKeyEventAction(111)),
        Triple("moveCursorBackward", "cursor_left", KeyboardAction.CursorMoveAction(-1)),
        Triple("moveCursorForward", "cursor_right", KeyboardAction.CursorMoveAction(1)),
        Triple("nextKeyboard", "switch_system_ime", KeyboardAction.LangSwitchAction),
        Triple("option", "option", KeyboardAction.AppKeyEventAction(57)),
        Triple("settings", "settings", KeyboardAction.SettingsAction),
        Triple("space", "space", KeyboardAction.SpaceAction),
        Triple("systemSettings", "system_settings", KeyboardAction.SystemSettingsAction),
        Triple("tab", "tab", KeyboardAction.AppKeyEventAction(61)),
        Triple("shift", "shift", KeyboardAction.MomentaryShiftAction),
        Triple("enter", "enter", KeyboardAction.ReturnAction()),
        Triple(
            "returnPrimaryKeyboard",
            "return_primary_keyboard",
            KeyboardAction.ReturnPrimaryAction,
        ),
        Triple(
            "returnLastKeyboard",
            "return_last_keyboard",
            KeyboardAction.ReturnLastAction,
        ),
        Triple("none", "none", KeyboardAction.NoOpAction),
    )

    @Test
    fun `all 20 mapped va2 actions resolve and dispatch as expected`() {
        assertEquals(20, expected.size)
        for ((_, formatName, runtime) in expected) {
            val action = WhitelistAction.fromFormatName(formatName)
                ?: error("va2 action missing from whitelist: $formatName")
            assertEquals(
                "runtime mismatch for $formatName",
                runtime,
                KeyOverrideApplier.toRuntime(SkinAction.Command(action)),
            )
        }
    }

    @Test
    fun `two lock toggles are not mounted`() {
        // symbolicKeyboardLockStateToggle／emojiKeyboardLockStateToggle
        // 已定不做，不挂白名单；兼容别名 symbolic_lock_toggle／
        // emoji_lock_toggle 只映 NONE（非锁定动作）。
        assertNull(
            WhitelistAction.fromFormatName("symbolicKeyboardLockStateToggle"),
        )
        assertNull(
            WhitelistAction.fromFormatName("emojiKeyboardLockStateToggle"),
        )
        assertEquals(
            WhitelistAction.NONE,
            WhitelistAction.fromFormatName("symbolic_lock_toggle"),
        )
        assertEquals(
            WhitelistAction.NONE,
            WhitelistAction.fromFormatName("emoji_lock_toggle"),
        )
    }

    @Test
    fun `function has no whitelist entry`() {
        // SkinKeyCodes 已定：function 在 Android 侧无可靠等价键码，
        // 解析期剔除；独立动作同样不挂。
        assertNull(WhitelistAction.fromFormatName("function"))
    }
}
