package com.jobeen.ime.input.keyboard.key

import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.engine.event.KeyModifiers

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

    /** 空格键滑动移动光标：direction < 0 左移，> 0 右移 */
    data class CursorMoveAction(val direction: Int) : KeyboardAction()

    data object LangSwitchAction : KeyboardAction()

    data object RotateSchema : KeyboardAction()

    data class SelectSchema(val schemaId: String) : KeyboardAction()

    data object ShowInputMethodPickerAction : KeyboardAction()

    /** 长按触发语音：pointerId 为触发瞬间那根手指的 id，父容器据此认领松手；
     * -1 表示未知（工具栏等无指针来源），松手判定回退为任意 UP 可结束 */
    data class VoiceInputAction(val pointerId: Int = -1) : KeyboardAction()

    data object StopVoiceInputAction : KeyboardAction()

    data class VoiceDragPosition(val pointerId: Int, val rawX: Float, val rawY: Float) : KeyboardAction()

    data object VoiceDragUp : KeyboardAction()

    data class MultiReturnAction(val text: String) : KeyboardAction()

    data object UndoAction : KeyboardAction()

    data object RedoAction : KeyboardAction()
}