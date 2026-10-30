package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction

/**
 * 工具栏中央区按钮注册表（静态，不含用户配置）：
 *
 * App 自定义工具栏功能已随第四组删除移除（2026-10-28）：
 * 设置里的工具栏自定义页与 `toolbar_config` 存储路径已删除，
 * 无皮肤工具栏时一律用出厂默认五项，不再读任何用户存值。
 *
 * 本对象仅剩皮肤工具栏系统共用的静态映射，供三处复用：
 * 皮肤工具栏解析（ToolbarSkinResolution）、皮肤校验
 * （SkinValidator 的已知按钮判定）与渲染器（ToolbarRenderer
 * 的动作↔按钮 id 映射及默认中央区）。上限与皮肤侧
 * SkinToolbarParser.MAX_BUTTONS 同值。
 */
object ToolbarConfig {

    /**
     * 中央区最多放几个按钮（再多单键触控宽过窄）。
     * 2026-10-29 item-1 拟态项：7→9（一行 9 图标容得下）。
     */
    const val MAX_ITEMS = 9

    /** 一个可放进中央区的候选按钮：稳定 id + 动作 + 显示名。 */
    data class Item(val id: String, val action: PanelAction, val label: String)

    /** 候选池（顺序即皮肤工具栏按钮 id 的注册顺序）。 */
    val POOL: List<Item> = listOf(
        Item("emoji", PanelAction.EmojiKeyboard, "表情"),
        Item("symbol", PanelAction.SymbolKeyboard, "符号"),
        Item("select_all", PanelAction.SelectAll, "全选"),
        Item("copy", PanelAction.Copy, "复制"),
        Item("paste", PanelAction.Paste, "粘贴"),
        Item("clipboard", PanelAction.Clipboard, "剪贴板"),
        Item("phrases", PanelAction.CommonPhrases, "常用语"),
        Item("voice", PanelAction.ToggleVoice, "语音"),
        Item("cursor", PanelAction.CursorMove, "光标移动"),
        Item("undo", PanelAction.Undo, "撤销"),
        Item("redo", PanelAction.Redo, "重做"),
        Item("palette", PanelAction.Palette, "主题"),
    )

    private val BY_ID: Map<String, Item> = POOL.associateBy { it.id }

    /** 出厂默认 = 改版前的固定五项，顺序不变。 */
    val DEFAULT_IDS: List<String> = listOf("emoji", "select_all", "copy", "paste", "clipboard")

    val DEFAULT_ACTIONS: List<PanelAction> = DEFAULT_IDS.map { BY_ID.getValue(it).action }

    fun item(id: String): Item? = BY_ID[id]
}
