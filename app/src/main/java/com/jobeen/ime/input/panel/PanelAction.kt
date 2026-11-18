package com.jobeen.ime.input.panel

sealed class PanelAction {
    data object SwitchKeyboard : PanelAction()

    /** 快捷面板（W19-6）：工具栏 MENU 槽位打开的 8 钮面板。 */
    data object ShortcutPanel : PanelAction()

    data object EmojiKeyboard : PanelAction()
    data object SymbolKeyboard : PanelAction()
    data object Clipboard : PanelAction()
    data object CommonPhrases : PanelAction()
    data object TogglePrediction : PanelAction()
    data object ToggleShowComment : PanelAction()
    data object ToggleTraditionalChinese : PanelAction()
    data object ToggleEmojiInput : PanelAction()
    data object ToggleAsciiMode : PanelAction()
    data object AddPhrase : PanelAction()
    data class ClipTab(val isClipboard: Boolean) : PanelAction()
    data object ToggleVoice : PanelAction()
    data object Settings : PanelAction()
    data object SchemaSettings : PanelAction()
    data object About : PanelAction()
    /** 键盘性能面板（P2-10）：菜单第 2 页右下角入口；关于不丢（App 设置首页关于组仍可进）。 */
    data object Performance : PanelAction()
    data object ReloadEngine : PanelAction()
    data object Undo : PanelAction()
    data object Redo : PanelAction()
    data object Palette : PanelAction()
    data object CursorMove : PanelAction()
    /** 命令面板（AI 指令列表）：工具栏池子入口，点开列指令再选着跑。 */
    data object CommandPanel : PanelAction()

    /** 语音插件：工具栏池子入口，点按轮换首选语音服务商（本地／已装云插件）。 */
    data object VoicePlugin : PanelAction()
    data object CloseKeyboard : PanelAction()
    data object ClearClipboard : PanelAction()
    data object ClearPhrases : PanelAction()
    data object ClipSearch : PanelAction()
    data object ClipSearchExit : PanelAction()
    data object ClipSearchClear : PanelAction()
    data object ClipEditSave : PanelAction()
    data object ClipEditCancel : PanelAction()
    data object ClipMultiExit : PanelAction()
    data object ClipMultiDelete : PanelAction()
    data object ClipReorderExit : PanelAction()
    data object SelectAll : PanelAction()
    data object Copy : PanelAction()
    data object Paste : PanelAction()

    /** 键盘内执行部署／同步（执行见 KeyboardDeployController；非菜单入口）。 */
    data object DeploySync : PanelAction()
}
