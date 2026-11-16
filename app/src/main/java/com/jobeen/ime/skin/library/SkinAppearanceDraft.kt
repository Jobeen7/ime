// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.layout.LayoutEditorModel
import com.jobeen.ime.skin.model.StyleField
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 可视化皮肤编辑器的草稿＋撤销栈（松烟式纯模型＋中州鹅式历史）：
 *
 * - 草稿只活在内存：skin.json 树＋本次编辑的布局树（九宫格／26 键两把，
 *   其余绑定布局不动）；取消即丢弃，不写盘、不应用——回滚就是不保存。
 * - 每次改动先把改前快照压进撤销栈（文档快照，30 步上限，超限丢最旧，
 *   与中州鹅 LayoutEditHistory 同口径）；撤销／重做只换内存树，预览
 *   即时重画。应用时由调用方把序列化文本走 SkinEditorSaver
 *   （saveSkinJson / saveLayout：校验→copy-on-write 新代→应用事务），
 *   本类不碰磁盘、不碰应用事务。
 * - keyMappings 键格式与转换器一致：`layoutId:K-stableId`
 *  （conv-current.js:1277）。
 *
 * 纯 Kotlin（除 ColorScheme 外无 Android 依赖），可 JVM 单测。
 */
class SkinAppearanceDraft(
    val skinId: String,
    val originalSkin: JsonObject,
    /** 本次编辑的布局原树（layoutId → 树；只含预览用到的九宫格／26 键）。 */
    val originalLayouts: Map<String, JsonObject>,
) {
    /** 改前快照（撤销栈顶 = 最近一次改动前的状态）。 */
    data class Snapshot(val skin: JsonObject, val layouts: Map<String, JsonObject>)

    companion object {
        /** 撤销步数上限（中州鹅 LayoutEditHistory 同为 30 步）。 */
        const val MAX_HISTORY = 30
    }

    var skin: JsonObject = originalSkin
        private set
    var layouts: Map<String, JsonObject> = originalLayouts
        private set

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val dirty: Boolean get() = skin != originalSkin || layouts != originalLayouts
    /** 本次改动过的布局 id（应用时只保存这些）。 */
    val dirtyLayoutIds: Set<String>
        get() = layouts.keys.filter { layouts[it] != originalLayouts[it] }.toSet()

    private fun mutate(transform: () -> Pair<JsonObject, Map<String, JsonObject>>) {
        undoStack.addLast(Snapshot(skin, layouts))
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        val (newSkin, newLayouts) = transform()
        skin = newSkin
        layouts = newLayouts
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(Snapshot(skin, layouts))
        skin = prev.skin
        layouts = prev.layouts
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(Snapshot(skin, layouts))
        skin = next.skin
        layouts = next.layouts
        return true
    }

    /** 样式板颜色（board = "styles" / "stylesDark"，path 为 StyleField 路径）。 */
    fun setStyleColor(board: String, path: String, hex: String) {
        mutate { writeStyleColor(skin, board, path, hex) to layouts }
    }

    /** 几何滑块值。 */
    fun setGeometry(field: SkinGeometryField, value: Float) {
        mutate { writeGeometryValue(skin, field, value) to layouts }
    }

    /**
     * 预设配色板应用到当前预览板（浅色板或深色板，按调用方传入的 dark）：
     * 预设主题只有一块 ColorScheme，逐字段覆写该板（StyleField 全集，
     * 与网页编辑器 36 色字段同口径）；预设未定义的板字段（null 面，
     * 如 candidateComment）不动，不删用户已有值。
     */
    fun applyPreset(colors: KeyboardColors.ColorScheme, dark: Boolean) {
        val board = if (dark) "stylesDark" else "styles"
        mutate {
            var tree = skin
            for ((path, hex) in presetBoardEntries(colors)) {
                tree = writeStyleColor(tree, board, path, hex)
            }
            tree to layouts
        }
    }

    /** 布局树整体替换（调用方先用 LayoutEditorModel.set* 算出新树）。 */
    fun setLayout(layoutId: String, tree: JsonObject) {
        if (layouts[layoutId] == tree) return
        mutate { skin to (layouts + (layoutId to tree)) }
    }

    /**
     * keyMappings 单字段写（logicalId 如 "bin-pinyin:K-KEY_Q"）：
     * value 为 null 时删该字段；entry 为空时整条删掉，不留空对象。
     */
    fun setKeyMappingField(logicalId: String, field: String, value: JsonElement?) {
        mutate {
            val mappings = (skin["keyMappings"] as? JsonObject) ?: JsonObject(emptyMap())
            val entry = (mappings[logicalId] as? JsonObject) ?: JsonObject(emptyMap())
            val newEntry = JsonObject(
                entry.toMutableMap().also { map ->
                    if (value == null) map.remove(field) else map[field] = value
                },
            )
            val newMappings = JsonObject(
                mappings.toMutableMap().also { map ->
                    if (newEntry.isEmpty()) map.remove(logicalId) else map[logicalId] = newEntry
                },
            )
            val newSkin = JsonObject(
                skin.toMutableMap().also { map ->
                    if (newMappings.isEmpty()) map.remove("keyMappings") else map["keyMappings"] = newMappings
                },
            )
            newSkin to layouts
        }
    }

    /** 读某键的 keyMappings 条目（无则 null）。 */
    fun keyMappingEntry(logicalId: String): JsonObject? =
        (skin["keyMappings"] as? JsonObject)?.get(logicalId) as? JsonObject

    /**
     * 重置单个键（image-68 底部条“重置”）：布局侧把该键恢复到原树状态
     * （原树无此键时删掉它——即撤销本次新增），keyMappings 侧删掉该键
     * 的整条覆写；一次改动、一步撤销。原树与现树均无此键时无操作。
     */
    fun resetKey(layoutId: String, stableId: String) {
        val originalTree = originalLayouts[layoutId] ?: return
        val currentTree = layouts[layoutId] ?: return
        val originalKey = LayoutEditorModel.keyByStableId(originalTree, stableId)
        val newTree = if (originalKey == null) {
            LayoutEditorModel.removeKey(currentTree, stableId)
        } else {
            replaceKeyObject(currentTree, stableId, originalKey)
        } ?: return
        val logicalId = keyMappingId(layoutId, stableId)
        mutate {
            var newSkin = skin
            val mappings = skin["keyMappings"] as? JsonObject
            if (mappings != null && mappings.containsKey(logicalId)) {
                val newMappings = JsonObject(
                    mappings.toMutableMap().also { it.remove(logicalId) },
                )
                newSkin = JsonObject(
                    skin.toMutableMap().also { map ->
                        if (newMappings.isEmpty()) map.remove("keyMappings")
                        else map["keyMappings"] = newMappings
                    },
                )
            }
            newSkin to (layouts + (layoutId to newTree))
        }
    }

    fun toSkinJsonText(): String = LayoutEditorModel.toPrettyJson(skin)

    fun layoutText(layoutId: String): String? =
        layouts[layoutId]?.let { LayoutEditorModel.toPrettyJson(it) }
}

/** keyMappings 逻辑 id：layoutId + ":K-" + stableId（与转换器同格式）。 */
fun keyMappingId(layoutId: String, stableId: String): String = "$layoutId:K-$stableId"

/** 布局树内替换单个键对象（stableId 匹配；结构不对／无此键返回 null）。 */
private fun replaceKeyObject(tree: JsonObject, stableId: String, newKey: JsonObject): JsonObject? {
    val rows = tree["rows"] as? kotlinx.serialization.json.JsonArray ?: return null
    var replaced = false
    val newRows = kotlinx.serialization.json.JsonArray(
        rows.map { rowNode ->
            val row = rowNode as? JsonObject ?: return@map rowNode
            val keys = row["keys"] as? kotlinx.serialization.json.JsonArray ?: return@map rowNode
            val newKeys = kotlinx.serialization.json.JsonArray(
                keys.map { keyNode ->
                    val key = keyNode as? JsonObject ?: return@map keyNode
                    if ((key["stableId"] as? JsonPrimitive)?.content == stableId) {
                        replaced = true
                        newKey
                    } else {
                        keyNode
                    }
                },
            )
            JsonObject(row.toMutableMap().also { it["keys"] = newKeys })
        },
    )
    if (!replaced) return null
    return JsonObject(tree.toMutableMap().also { it["rows"] = newRows })
}

/** ColorScheme → 样式板条目（StyleField 路径 → #AARRGGBB）；null 面跳过。 */
fun presetBoardEntries(colors: KeyboardColors.ColorScheme): List<Pair<String, String>> {
    fun hex(argb: Int): String = "#%08X".format(argb)
    val entries = mutableListOf<Pair<String, String>>()
    fun put(field: StyleField, argb: Int) = entries.add(field.path to hex(argb))
    fun putOpt(field: StyleField, argb: Int?) {
        if (argb != null) entries.add(field.path to hex(argb))
    }
    put(StyleField.KEY_BACKGROUND, colors.keyBackground)
    put(StyleField.KEY_PRESSED, colors.keyPressed)
    put(StyleField.KEY_BORDER_STROKE, colors.keyBorderStroke)
    put(StyleField.SPECIAL_KEY_BACKGROUND, colors.specialKeyBackground)
    put(StyleField.SPECIAL_KEY_PRESSED, colors.specialKeyPressed)
    put(StyleField.SPECIAL_KEY_BORDER_STROKE, colors.specialKeyBorderStroke)
    put(StyleField.ACCENT_KEY_BACKGROUND, colors.accentKeyBackground)
    put(StyleField.ACCENT_KEY_PRESSED, colors.accentKeyPressed)
    put(StyleField.ACCENT_KEY_BORDER_STROKE, colors.accentKeyBorderStroke)
    put(StyleField.KEY_TEXT, colors.keyText)
    put(StyleField.SPECIAL_KEY_TEXT, colors.specialKeyText)
    put(StyleField.ACCENT_KEY_TEXT, colors.accentKeyText)
    put(StyleField.ALT_TEXT, colors.altText)
    put(StyleField.BACKGROUND, colors.background)
    put(StyleField.TOAST_BACKGROUND, colors.toastBackground)
    put(StyleField.TOAST_TEXT, colors.toastText)
    val panel = colors.panel
    put(StyleField.PANEL_BACKGROUND, panel.background)
    put(StyleField.PANEL_TOOLBAR_TEXT, panel.toolbarText)
    put(StyleField.PANEL_TOOLBAR_ACTIVED, panel.toolbarActived)
    put(StyleField.PANEL_TOOLBAR_ICON, panel.toolbarIcon)
    put(StyleField.PANEL_CANDIDATE_BACKGROUND, panel.candidateBackground)
    put(StyleField.PANEL_CANDIDATE_TEXT, panel.candidateText)
    put(StyleField.PANEL_SELECTED_CANDIDATE_TEXT, panel.selectedCandidateText)
    put(StyleField.PANEL_CANDIDATE_INDEX, panel.candidateIndex)
    put(StyleField.PANEL_CANDIDATE_DIVIDER, panel.candidateDivider)
    put(StyleField.PANEL_TOOLBAR_PRESSED, panel.toolbarPressed)
    putOpt(StyleField.PANEL_CANDIDATE_COMMENT, panel.candidateComment)
    putOpt(StyleField.PANEL_CANDIDATE_PRESSED, panel.candidatePressed)
    putOpt(StyleField.PANEL_PREFERRED_BACKGROUND, panel.preferredBackground)
    putOpt(StyleField.PANEL_PREFERRED_TEXT, panel.preferredText)
    putOpt(StyleField.PANEL_PREFERRED_COMMENT, panel.preferredComment)
    putOpt(StyleField.PANEL_PREFERRED_INDEX, panel.preferredIndex)
    putOpt(StyleField.PANEL_VERTICAL_SEPARATOR, panel.verticalSeparator)
    val pinner = colors.pinner
    put(StyleField.PINNER_BACKGROUND, pinner.background)
    put(StyleField.PINNER_TEXT_COLOR, pinner.textColor)
    put(StyleField.PINNER_SECONDARY_TEXT_COLOR, pinner.secondaryTextColor)
    return entries
}
