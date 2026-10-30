// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.menu

import com.jobeen.ime.input.panel.PanelAction

/**
 * 键盘菜单页入口注册表（重点候选 8-10，纯数据）：
 *
 * 菜单是一页（面板 State.Menu → MenuGridView），不是悬浮层。
 * 入口取现有工具栏池（ToolbarConfig.POOL 12 项）＋设置项，
 * 并保留菜单原有开关项（不因重构丢失入口）：
 * - 第 1 页：原菜单开关／工具项（原样保留）；
 * - 第 2 页：原菜单剪贴板／工具／设置项（原样保留）；
 * - 第 3 页（新增）：工具栏池中原菜单没有的 5 项（全选／复制／
 *   粘贴／撤销／重做）＋部署／同步入口（执行见
 *   KeyboardDeployController，接线层在 PanelActionListener
 *   的 DeploySync 分支挂回调）。
 *
 * 标签与图标由视图层按 id 映射（R.string／R.drawable），本表
 * 只定顺序、id 与动作；id 唯一、每页不超过 8 项（4 列 × 2 行，
 * 与 MenuGridView 现有分页一致）。
 */
object KeyboardMenuEntries {

    data class Entry(val id: String, val action: PanelAction)

    val PAGES: List<List<Entry>> = listOf(
        listOf(
            Entry("emoji", PanelAction.EmojiKeyboard),
            Entry("symbol", PanelAction.SymbolKeyboard),
            Entry("prediction", PanelAction.TogglePrediction),
            Entry("candidate_comment", PanelAction.ToggleShowComment),
            Entry("traditional_chinese", PanelAction.ToggleTraditionalChinese),
            Entry("ascii_mode", PanelAction.ToggleAsciiMode),
            Entry("emoji_input", PanelAction.ToggleEmojiInput),
            Entry("voice", PanelAction.ToggleVoice),
        ),
        listOf(
            Entry("clipboard", PanelAction.Clipboard),
            Entry("phrases", PanelAction.CommonPhrases),
            Entry("cursor", PanelAction.CursorMove),
            Entry("schema_settings", PanelAction.SchemaSettings),
            Entry("settings", PanelAction.Settings),
            Entry("theme", PanelAction.Palette),
            Entry("reload_engine", PanelAction.ReloadEngine),
            Entry("about", PanelAction.About),
        ),
        listOf(
            Entry("select_all", PanelAction.SelectAll),
            Entry("copy", PanelAction.Copy),
            Entry("paste", PanelAction.Paste),
            Entry("undo", PanelAction.Undo),
            Entry("redo", PanelAction.Redo),
            Entry("deploy_sync", PanelAction.DeploySync),
        ),
    )

    val ALL: List<Entry> get() = PAGES.flatten()
}
