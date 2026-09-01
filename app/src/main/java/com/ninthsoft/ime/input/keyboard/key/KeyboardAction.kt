package com.ninthsoft.ime.input.keyboard.key

import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.event.KeyModifiers

sealed class KeyboardAction {

    data class KeyCodeAction(
        val keyCode: Int,
        val modifiers: KeyModifiers = KeyModifiers.Empty,
        val isVirtual: Boolean = true
    ) : KeyboardAction() {
        fun asKeyEvent(): KeyEvent {
            return KeyEvent.CodeEvent(keyCode, modifiers, isVirtual = isVirtual)
        }
    }

    data class KeySequenceAction(val sequence: String) : KeyboardAction() {
        fun asKeyEvent(): KeyEvent {
            return KeyEvent.SequenceEvent(sequence)
        }
    }

    data object ClearAction : KeyboardAction()

    data class CommitAction(val text: String) : KeyboardAction()

    data class SelectCandidatePinYin(val pinYin: CandidatePinYin) : KeyboardAction()

    data object CapsAction : KeyboardAction()

    data class LayoutSwitchAction(val target: String) : KeyboardAction()

    data object ResumeAction : KeyboardAction()

    data object BackspaceAction : KeyboardAction()

    data class ReturnAction(val force: Boolean = false) : KeyboardAction()

    data object SpaceAction : KeyboardAction()

    data object LangSwitchAction : KeyboardAction()

    data object RotateSchema : KeyboardAction()

    data class SelectSchema(val schemaId: String) : KeyboardAction()

    data object ShowInputMethodPickerAction : KeyboardAction()

    data object VoiceInputAction : KeyboardAction()

    data object StopVoiceInputAction : KeyboardAction()

    data class VoiceDragPosition(val rawX: Float, val rawY: Float) : KeyboardAction()

    data object VoiceDragUp : KeyboardAction()

    data class MultiReturnAction(val text: String) : KeyboardAction()
}