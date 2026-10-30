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

    /**
     * 命名方案直切换（重点候选 8-3）：value = 方案 ID（优先）或
     * 方案名原文；ID/名解析在 KeyboardWindowView.transformed
     *（SchemaTargetResolver），未命中时丢弃并记日志、不切换。
     */
    data class SwitchSchemaByValue(val value: String) : KeyboardAction()

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

    /**
     * 字面提交（皮肤覆写专用内部类型，裁定 B2 / 详设 §3.4）：
     * 与 CommitAction 同义上屏，但不经 Qwerty 的标点全半角转换、
     * 不随 Caps 大写。只由皮肤覆写装配产生，永不写入皮肤格式。
     */
    data class LiteralCommitAction(val text: String) : KeyboardAction()

    /**
     * 向前删除（定稿四.4）：仅非组字态生效（选区优先，其次删除
     * 光标后一个字素簇）；组字中无操作。绝不经引擎按键通道、
     * 绝不回退为退格（详设 §五）。
     */
    data object DeleteForwardAction : KeyboardAction()

    /** 打开系统输入法选择器：服务层路由（裁定记录 §11-2），不经引擎。 */
    data object OpenSystemImePickerAction : KeyboardAction()

    /** 隐藏键盘：服务层路由（裁定记录 §11-2），不经引擎。 */
    data object HideKeyboardAction : KeyboardAction()
}