package com.jobeen.ime.input.panel.toolbar

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.input.panel.PanelAction

/**
 * 工具栏中央区自定义配置（真机反馈补充项，2026-10-24 裁定）：
 *
 * - 范围只含候选栏工具栏的中央按钮区：选哪些按钮、什么顺序。
 *   左端菜单/返回、右端收起是状态机结构键，固定不动；剪贴板态
 *   工具栏（clipMode）是功能态专用栏，不在此列。
 * - 存储为全局偏好（工具栏是功能快捷方式、不是外观，不进皮肤，
 *   换皮肤不改变它），即改即存，工具栏下次重建时生效。
 * - 解析口径沿用皮肤系统的容错式：未知 id 丢弃、重复去重、
 *   超上限截断；从未存过 → 出厂默认五项；显式存空 = 中央区留空。
 */
object ToolbarConfig {

    const val PREFS_NAME = "toolbar_config"
    private const val KEY_CENTER_ITEMS = "center_items"

    /** 中央区最多放几个按钮（再多单键触控宽过窄）。 */
    const val MAX_ITEMS = 7

    /** 一个可放进中央区的候选按钮：稳定 id + 动作 + 显示名。 */
    data class Item(val id: String, val action: PanelAction, val label: String)

    /** 候选池（顺序即设置页「可添加」列表顺序）。 */
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

    /**
     * 解析存储串（逗号分隔 id）为有序 id 列表。
     * [raw] 为 null 表示从未存过 → 默认；空串是显式清空 → 空列表。
     */
    fun parseIds(raw: String?): List<String> {
        if (raw == null) return DEFAULT_IDS
        val seen = LinkedHashSet<String>()
        for (part in raw.split(',')) {
            val id = part.trim()
            if (id.isNotEmpty() && BY_ID.containsKey(id)) seen += id
            if (seen.size >= MAX_ITEMS) break
        }
        return seen.toList()
    }

    /** 序列化（与 [parseIds] 互逆：未知 id 同样在写入前剔除）。 */
    fun serializeIds(ids: List<String>): String =
        ids.filter { BY_ID.containsKey(it) }.distinct().take(MAX_ITEMS).joinToString(",")

    fun idsToActions(ids: List<String>): List<PanelAction> =
        ids.mapNotNull { BY_ID[it]?.action }

    fun readIds(context: Context): List<String> =
        parseIds(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CENTER_ITEMS, null),
        )

    fun readActions(context: Context): List<PanelAction> = idsToActions(readIds(context))

    fun writeIds(context: Context, ids: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_CENTER_ITEMS, serializeIds(ids))
        }
    }
}
