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

    /**
     * 键盘类型切换（纠正 2：与中英切换并存的独立机制）：
     * 皮肤 keyboardType 动作绑定，目标为类型名（含自定义名），
     * 经类型状态入口切换；与按名切换的 LayoutSwitchAction 不同
     * （后者只认布局名，不更新类型状态）。
     */
    data class SwitchKeyboardTypeAction(val type: String) : KeyboardAction()

    data object ResumeAction : KeyboardAction()

    data object BackspaceAction : KeyboardAction()

    data class ReturnAction(val force: Boolean = false) : KeyboardAction()

    data object SpaceAction : KeyboardAction()

    /** 空格键滑动移动光标：direction < 0 左移，> 0 右移 */
    data class CursorMoveAction(val direction: Int) : KeyboardAction()

    data object LangSwitchAction : KeyboardAction()

    /** 中英切换：切换 ascii 模式（菜单 ascii 开关同款逻辑）。 */
    data object ToggleAsciiModeAction : KeyboardAction()

    /**
     * 符号板锁定切换（审计 G）：翻转符号板锁定态；窗口层
     * 拦截处理，不下发引擎。锁定态本身不由返回动作清除。
     */
    data object ToggleSymbolicLockAction : KeyboardAction()

    /** 表情板锁定切换（审计 G）：与符号板锁互不干扰。 */
    data object ToggleEmojiLockAction : KeyboardAction()

    /**
     * 板提交后自动返回（审计 G）：仅由符号/表情板在未锁定
     * 提交后发出；窗口层拦截走类型模型的上一类型返回
     * （对端 returnLastKeyboard 口径），不下发引擎；显式
     * 返回键仍走 ResumeAction，两者目标口径不同，不得混用。
     */
    data object BoardCommitReturnAction : KeyboardAction()

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
     * 宿主文本撤销（#undo）：经当前输入连接
     * performContextMenuAction(android.R.id.undo)，尽力而为、
     * 宿主相关；与引擎内撤销（UndoAction：清组字／语音账本
     * 删除）不同，不可混用。连接为空时空操作，不伪造成功，
     * 不回退删字。
     */
    data object HostUndoAction : KeyboardAction()

    /**
     * 宿主文本重做（#redo）：经当前输入连接
     * performContextMenuAction(android.R.id.redo)，尽力而为、
     * 宿主相关；与引擎内重做（RedoAction）不同，不可混用。
     * 连接为空时空操作，不伪造成功，不回退删字。
     */
    data object HostRedoAction : KeyboardAction()

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

    /**
     * 删除选中文本（#deleteText）：仅删当前选区；无选区空操作，
     * 绝不回退为退格；组字中分发层禁行（选区与组字重叠时连接
     * 侧删除会损坏组字态，见 DeleteTextPolicy）。
     */
    data object DeleteTextAction : KeyboardAction()

    /** 打开系统输入法选择器：服务层路由（裁定记录 §11-2），不经引擎。 */
    data object OpenSystemImePickerAction : KeyboardAction()

    /** 隐藏键盘：服务层路由（裁定记录 §11-2），不经引擎。 */
    data object HideKeyboardAction : KeyboardAction()

    /**
     * 组合按键序列（审计 E：sendKeys）：有序的（键码，修饰键）
     * 组合，逐个经引擎按键通道下发（区别于 KeySequenceAction
     * 的整串文本事件）。
     */
    data class SendKeyCombo(
        val keyCode: Int,
        val modifiers: KeyModifiers = KeyModifiers.Empty,
    )

    data class SendKeysAction(val keys: List<SendKeyCombo>) : KeyboardAction()

    /**
     * 直送应用的按键事件（审计 E：sendKeyEvent / tab / escape /
     * 行首行尾）：绕过引擎，经输入连接直发应用，不参与组字。
     */
    data class AppKeyEventAction(
        val keyCode: Int,
        val ctrl: Boolean = false,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    ) : KeyboardAction()

    /** 组合动作（审计 E：combine）：按序逐个分发子动作。 */
    data class CombineAction(val actions: List<KeyboardAction>) : KeyboardAction()

    /** 打开链接（审计 E：openURL）：仅 http/https 由分发层放行。 */
    data class OpenUrlAction(val url: String) : KeyboardAction()

    /** 刻意空操作（审计 E：none）：分发层显式吞掉，不回退出厂行为。 */
    data object NoOpAction : KeyboardAction()

    /** 打开本应用设置（审计 E：settings）：MainActivity 意图。 */
    data object SettingsAction : KeyboardAction()

    /**
     * 应用级脚本动作（脚本接线波次）：
     * - [RunScriptAction]：运行应用级脚本库中的一个脚本（值 = 脚本 id）；
     *   执行在后台线程，返回值按脚本桥接规则转动作（见 SkinScriptKeyBridge）。
     * - [OpenScriptsAction]：打开脚本管理页。
     */
    data class RunScriptAction(val scriptId: String) : KeyboardAction()

    /** 打开脚本管理页（open_script）。 */
    data object OpenScriptsAction : KeyboardAction()

    /**
     * 切换脚本视图（#toggleScriptView）：脚本视图即应用级脚本
     * 管理页。键盘按键只在键盘前台时送达，此时管理页必然不
     * 在前台，故按键可达的切换方向恒为显示；收起方向由管理
     * 页自身的返回键承担，不由按键实现。分发上与
     * [OpenScriptsAction] 同目的地、不同动作种类。
     */
    data object ToggleScriptViewAction : KeyboardAction()

    /** 打开系统设置（审计 E：systemSettings）：系统设置意图。 */
    data object SystemSettingsAction : KeyboardAction()

    /** 回主键盘（审计 E：returnPrimaryKeyboard）：经类型状态回主类型。 */
    data object ReturnPrimaryAction : KeyboardAction()

    /** 回上一键盘（审计 E：returnLastKeyboard）：经类型状态回上一类型。 */
    data object ReturnLastAction : KeyboardAction()

    /** 剪贴板动作（审计 E：#cut/copy/paste/clearSystemPasteboard）：经输入连接。 */
    data class ClipboardAction(val kind: Kind) : KeyboardAction() {
        enum class Kind { CUT, COPY, PASTE, CLEAR }
    }

    /** 简繁切换（审计 E：#简繁切换）：翻转繁体偏好。 */
    data object ToggleTraditionalAction : KeyboardAction()

    /** 内嵌输入切换（审计 E：#toggleEmbeddedInputMode）：翻转内嵌偏好。 */
    data object ToggleEmbeddedInputAction : KeyboardAction()

    /**
     * 按序号选候选（审计 E：#次选上屏/#三选上屏）：index 为候选
     * 表顺序（0 = 首选）；窗口层凭上一帧候选表解析，解析不到丢弃。
     */
    data class SelectCandidateIndexAction(val index: Int) : KeyboardAction()

    /** 打开常用语视图（审计 E：#showPhraseView）：窗口层接窗口加词层。 */
    data object ShowPhraseViewAction : KeyboardAction()

    /** 候选栏展开/收起切换（审计 E：#candidatesBarStateToggle）。 */
    data object CandidatesBarToggleAction : KeyboardAction()

    /** 键盘菜单开关（#keyboardMenu）：面板菜单态与空闲态往返。 */
    data object KeyboardMenuAction : KeyboardAction()

    /**
     * 符号条开关（#toggleSymbolBar）：窗口层实现（首次打开、
     * 再次关闭；打开态不记住，键盘切换／收起即关，见
     * KeyboardWindowView.toggleSymbolBar）。
     */
    data object ToggleSymbolBarAction : KeyboardAction()

    /**
     * 命令面板开关（#toggleCommandView）：目标 UI（命令面板）
     * 不在本轮，分发为显式记日志的空操作，不伪造界面。
     */
    data object ToggleCommandViewAction : KeyboardAction()

    /** RIME 选项翻转（rimeOption）：仅布尔选项，不持久化。 */
    data class ToggleRimeOptionAction(val option: String) : KeyboardAction()

    /** 模板文本上屏（snippet）：日期展开＋光标位，分发时展开。 */
    data class SnippetAction(val template: String) : KeyboardAction()

    /** 子动作间停顿（delay，combine 专用）：毫秒数（≤1000）。 */
    data class DelayAction(val ms: Int) : KeyboardAction()

    /**
     * 瞬时上档（审计 E：shift）：标记动作，按键面据此接按下/
     * 抬起钩子（非点击分发）；直接分发到监听时为空操作。
     */
    data object MomentaryShiftAction : KeyboardAction()
}