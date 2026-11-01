package com.jobeen.ime.skin.notify

import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新增运行时动作的类型稳定名（审计 E：通知匹配与弹窗标签的穷尽分支守门）。 */
class NotificationActionNamesTest {

    @Test
    fun newActionNamesAreStable() {
        assertEquals(
            "SendKeysAction",
            NotificationActionNames.nameOf(
                KeyboardAction.SendKeysAction(
                    listOf(KeyboardAction.SendKeyCombo(29, KeyModifiers.Empty)),
                ),
            ),
        )
        assertEquals(
            "AppKeyEventAction",
            NotificationActionNames.nameOf(KeyboardAction.AppKeyEventAction(61)),
        )
        assertEquals(
            "CombineAction",
            NotificationActionNames.nameOf(
                KeyboardAction.CombineAction(listOf(KeyboardAction.SpaceAction)),
            ),
        )
        assertEquals(
            "OpenUrlAction",
            NotificationActionNames.nameOf(KeyboardAction.OpenUrlAction("https://example.com")),
        )
        assertEquals("NoOpAction", NotificationActionNames.nameOf(KeyboardAction.NoOpAction))
        assertEquals("SettingsAction", NotificationActionNames.nameOf(KeyboardAction.SettingsAction))
        assertEquals(
            "SystemSettingsAction",
            NotificationActionNames.nameOf(KeyboardAction.SystemSettingsAction),
        )
        assertEquals(
            "ReturnPrimaryAction",
            NotificationActionNames.nameOf(KeyboardAction.ReturnPrimaryAction),
        )
        assertEquals(
            "ReturnLastAction",
            NotificationActionNames.nameOf(KeyboardAction.ReturnLastAction),
        )
        assertEquals(
            "ClipboardAction",
            NotificationActionNames.nameOf(
                KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.COPY),
            ),
        )
        assertEquals(
            "ToggleTraditionalAction",
            NotificationActionNames.nameOf(KeyboardAction.ToggleTraditionalAction),
        )
        assertEquals(
            "ToggleEmbeddedInputAction",
            NotificationActionNames.nameOf(KeyboardAction.ToggleEmbeddedInputAction),
        )
        assertEquals(
            "SelectCandidateIndexAction",
            NotificationActionNames.nameOf(KeyboardAction.SelectCandidateIndexAction(1)),
        )
        assertEquals(
            "ShowPhraseViewAction",
            NotificationActionNames.nameOf(KeyboardAction.ShowPhraseViewAction),
        )
        assertEquals(
            "CandidatesBarToggleAction",
            NotificationActionNames.nameOf(KeyboardAction.CandidatesBarToggleAction),
        )
        assertEquals(
            "MomentaryShiftAction",
            NotificationActionNames.nameOf(KeyboardAction.MomentaryShiftAction),
        )
        assertEquals(
            "RunScriptAction",
            NotificationActionNames.nameOf(KeyboardAction.RunScriptAction("s.one")),
        )
        assertEquals(
            "OpenScriptsAction",
            NotificationActionNames.nameOf(KeyboardAction.OpenScriptsAction),
        )
    }
}
