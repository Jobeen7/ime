// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.menu

import com.jobeen.ime.R
import com.jobeen.ime.input.panel.PanelAction

/**
 * 键盘菜单页入口注册表（重点候选 8-10，纯数据）：
 *
 * 菜单是一页（面板 State.Menu → MenuGridView），不是悬浮层。
 * 入口取现有工具栏池（ToolbarConfig.POOL 12 项）＋设置项，
 * 并保留菜单原有开关项（不因重构丢失入口）：
 * - 第 1 页：原菜单开关／工具项（原样保留）；
 * - 第 2 页：原菜单剪贴板／工具／设置项（原样保留）；
 * - 第 3 页已删（2026-10-31 用户定：菜单第三页的 6 项全选／复制／
 *   粘贴／撤销／重做／部署／同步均不需要）：整页移除，菜单剩 2 页，
 *   分页点随之变 2 个；动作本身保留（工具栏池／FAB 侧仍有入口），
 *   仅菜单不再提供。
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
            // P2-10：性能面板顶掉「关于」（两页现均为满 8/8；关于不丢，
            // App 设置首页关于组仍可进）。
            Entry("performance", PanelAction.Performance),
        ),
    )

    val ALL: List<Entry> get() = PAGES.flatten()

    /**
     * 标签／图标映射（唯一来源：菜单视图与管理页共用；新增入口
     * 只改注册表＋本映射，不动分页逻辑）：
     * - select_all／copy／paste／undo／redo／deploy_sync 曾有映射，
     *   现无菜单入口产生这些 id，已随 P2 清理删除；未知 id 走 else
     *   回落，不再为无入口 id 保留映射。
     */
    fun labelRes(id: String): Int = when (id) {
        "emoji" -> R.string.menu_emoji
        "symbol" -> R.string.menu_symbol
        "prediction" -> R.string.model_prediction
        "candidate_comment" -> R.string.menu_candidate_comment
        "traditional_chinese" -> R.string.menu_traditional_chinese
        "ascii_mode" -> R.string.menu_ascii_mode
        "emoji_input" -> R.string.menu_emoji_input
        "voice" -> R.string.menu_voice
        "clipboard" -> R.string.menu_clipboard
        "phrases" -> R.string.phrase_tab
        "cursor" -> R.string.menu_cursor
        "schema_settings" -> R.string.menu_schema
        "settings" -> R.string.menu_settings
        "theme" -> R.string.menu_theme
        "reload_engine" -> R.string.menu_reload_engine
        "about" -> R.string.menu_about
        "performance" -> R.string.menu_performance
        else -> R.string.menu_settings
    }

    fun iconRes(id: String): Int = when (id) {
        "emoji" -> R.drawable.ic_keyboard_emoticon
        "symbol" -> R.drawable.ic_keyboard_symbol
        "prediction" -> R.drawable.ic_keyboard_lightbulb_on_outline
        "candidate_comment" -> R.drawable.ic_keyboard_bubble
        "traditional_chinese" -> R.drawable.ic_keyboard_traditional_ch
        "ascii_mode" -> R.drawable.ic_keyboard_english_mode
        "emoji_input" -> R.drawable.ic_keyboard_sticker_emoji
        "voice" -> R.drawable.ic_keyboard_voice
        "clipboard" -> R.drawable.ic_keyboard_clipboard
        "phrases" -> R.drawable.ic_keyboard_star_david
        "cursor" -> R.drawable.ic_keyboard_cursor_move
        "schema_settings" -> R.drawable.ic_keyboard_tune
        "settings" -> R.drawable.ic_keyboard_setting
        "theme" -> R.drawable.ic_keyboard_palette
        "reload_engine" -> R.drawable.ic_keyboard_reload
        "about" -> R.drawable.ic_keyboard_information_outline
        "performance" -> R.drawable.ic_keyboard_performance
        else -> R.drawable.ic_keyboard_setting
    }
}
