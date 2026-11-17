// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.random.Random

// 校验限值（内联自已删的 LayoutValidator 常量：本类是唯一使用方，
// 只用常量、不调 validate()；值与 HEAD LayoutValidator.kt:56-63 一致）。
private const val MAX_ROWS = 8
private const val MAX_KEYS_PER_ROW = 12
private const val MAX_TOTAL_KEYS = 64
private const val MIN_WEIGHT = 0.05
private const val MAX_WEIGHT = 20.0
private const val DEFAULT_WEIGHT = 1.0

/**
 * 可视化布局编辑器的数据模型（2026-11-16 松烟可取项 3a：只学思路
 * 自己重写，不搬 GPL 代码）。
 *
 * 直接操作 layout.json 原始树（JsonObject）：只动被编辑的字段，
 * 其余节点逐一原样保留（与 LayoutTreeOps 的 round-trip 口径一致，
 * 未知节点不剥除）。所有操作返回新树（不可变，Compose 状态友好），
 * 入参非法（行／键不存在、越限）返回 null，调用方不写盘。
 *
 * 校验口径：行 ≤ 8、每行键 ≤ 12、总键 ≤ 64、键权重 0.05–20.0、
 * stableId 必填且唯一（限值内联自已删的 LayoutValidator 常量；
 * 保存校验随验证器一并删除，本类只做操作期防呆）。
 *
 * 键身份 = stableId（文件 `stableId` 字段）：重排／增删其他键时
 * 存量键 stableId 不变。新增键的 stableId 由 [newStableId] 生成
 * （8 位小写字母＋数字，避开已有 id）。
 */
object LayoutEditorModel {

    enum class MoveDirection { LEFT, RIGHT, UP, DOWN }

    data class KeyLocation(val rowIndex: Int, val keyIndex: Int)

    private const val FIELD_ROWS = "rows"
    private const val FIELD_KEYS = "keys"
    private const val FIELD_STABLE_ID = "stableId"
    private const val FIELD_LABEL = "label"
    private const val FIELD_WIDTH_WEIGHT = "widthWeight"
    private const val FIELD_TAP = "tap"
    private const val FIELD_LONG_PRESS = "longPress"
    private const val FIELD_SWIPE_UP = "swipeUp"
    private const val FIELD_SWIPE_DOWN = "swipeDown"

    private val prettyJson = Json { prettyPrint = true }

    /** 布局各行（行序 = 文件数组序；非对象行／缺 keys 的行按空行计）。 */
    fun rows(tree: JsonObject): List<List<JsonObject>> {
        val rowsArray = tree[FIELD_ROWS] as? JsonArray ?: return emptyList()
        return rowsArray.map { rowNode ->
            val keys = (rowNode as? JsonObject)?.get(FIELD_KEYS) as? JsonArray
                ?: return@map emptyList()
            keys.mapNotNull { it as? JsonObject }
        }
    }

    /** 全部 stableId（新增键去重用）。 */
    fun allStableIds(tree: JsonObject): Set<String> =
        rows(tree).flatten().mapNotNull {
            (it[FIELD_STABLE_ID] as? JsonPrimitive)?.content
        }.toSet()

    /** 键所在位置（行／行内下标），不存在返回 null。 */
    fun findKey(tree: JsonObject, stableId: String): KeyLocation? {
        rows(tree).forEachIndexed { rowIndex, keys ->
            keys.forEachIndexed { keyIndex, key ->
                if ((key[FIELD_STABLE_ID] as? JsonPrimitive)?.content == stableId) {
                    return KeyLocation(rowIndex, keyIndex)
                }
            }
        }
        return null
    }

    /** 取键节点（只读视图），不存在返回 null。 */
    fun keyByStableId(tree: JsonObject, stableId: String): JsonObject? {
        val loc = findKey(tree, stableId) ?: return null
        return rows(tree)[loc.rowIndex][loc.keyIndex]
    }

    /** 改键标签（空串 = 清掉 label 字段，走校验器的回退链）。 */
    fun setLabel(tree: JsonObject, stableId: String, label: String): JsonObject? =
        updateKey(tree, stableId) { key ->
            if (label.isEmpty()) JsonObject(key - FIELD_LABEL)
            else JsonObject(key + (FIELD_LABEL to JsonPrimitive(label)))
        }

    /** 改键宽度权重（钳制到校验域内；非法输入返回 null）。 */
    fun setWeight(tree: JsonObject, stableId: String, weight: Double): JsonObject? {
        if (!weight.isFinite()) return null
        val clamped = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        return updateKey(tree, stableId) { key ->
            JsonObject(key + (FIELD_WIDTH_WEIGHT to JsonPrimitive(clamped)))
        }
    }

    /** 改点按动作（null = 删掉 tap 字段；自创布局 tap 必填，保存校验会拦）。 */
    fun setTap(tree: JsonObject, stableId: String, action: JsonObject?): JsonObject? =
        updateKey(tree, stableId) { key ->
            if (action == null) JsonObject(key - FIELD_TAP)
            else JsonObject(key + (FIELD_TAP to action))
        }

    /** 改长按动作列（空列 = 删掉 longPress 字段）。 */
    fun setLongPress(
        tree: JsonObject,
        stableId: String,
        actions: List<JsonObject>,
    ): JsonObject? = updateKey(tree, stableId) { key ->
        if (actions.isEmpty()) JsonObject(key - FIELD_LONG_PRESS)
        else JsonObject(key + (FIELD_LONG_PRESS to JsonArray(actions)))
    }

    /** 改上滑／下滑动作（null = 删字段）。 */
    fun setSwipe(
        tree: JsonObject,
        stableId: String,
        field: String,
        action: JsonObject?,
    ): JsonObject? {
        require(field == FIELD_SWIPE_UP || field == FIELD_SWIPE_DOWN)
        return updateKey(tree, stableId) { key ->
            if (action == null) JsonObject(key - field)
            else JsonObject(key + (field to action))
        }
    }

    /** 在行尾新增键（默认 Text 动作，label 即提交文本；行满／行不存在返回 null）。 */
    fun addKey(
        tree: JsonObject,
        rowIndex: Int,
        label: String,
        tap: JsonObject = actionText(label),
    ): JsonObject? {
        val currentRows = rows(tree)
        if (rowIndex !in currentRows.indices) return null
        if (currentRows[rowIndex].size >= MAX_KEYS_PER_ROW) return null
        val totalKeys = currentRows.sumOf { it.size }
        if (totalKeys >= MAX_TOTAL_KEYS) return null
        val newKey = buildJsonObject {
            put(FIELD_STABLE_ID, newStableId(allStableIds(tree)))
            put(FIELD_LABEL, label)
            put(FIELD_WIDTH_WEIGHT, DEFAULT_WEIGHT)
            put(FIELD_TAP, tap)
        }
        return replaceRow(tree, rowIndex, currentRows[rowIndex] + newKey)
    }

    /** 删键（删后空行保留——空行保存校验会判无效，调用方须随后删行或补键）。 */
    fun removeKey(tree: JsonObject, stableId: String): JsonObject? {
        val loc = findKey(tree, stableId) ?: return null
        val currentRows = rows(tree)
        val newRow = currentRows[loc.rowIndex].filterIndexed { i, _ -> i != loc.keyIndex }
        return replaceRow(tree, loc.rowIndex, newRow)
    }

    /** 移键：LEFT/RIGHT 行内换位；UP/DOWN 跨行（移到相邻行尾，目标行满则不动）。 */
    fun moveKey(
        tree: JsonObject,
        stableId: String,
        direction: MoveDirection,
    ): JsonObject? {
        val loc = findKey(tree, stableId) ?: return null
        val currentRows = rows(tree)
        val row = currentRows[loc.rowIndex]
        return when (direction) {
            MoveDirection.LEFT -> {
                if (loc.keyIndex <= 0) return null
                val newRow = row.toMutableList().apply {
                    val key = removeAt(loc.keyIndex)
                    add(loc.keyIndex - 1, key)
                }
                replaceRow(tree, loc.rowIndex, newRow)
            }
            MoveDirection.RIGHT -> {
                if (loc.keyIndex >= row.size - 1) return null
                val newRow = row.toMutableList().apply {
                    val key = removeAt(loc.keyIndex)
                    add(loc.keyIndex + 1, key)
                }
                replaceRow(tree, loc.rowIndex, newRow)
            }
            MoveDirection.UP -> {
                if (loc.rowIndex <= 0) return null
                val target = currentRows[loc.rowIndex - 1]
                if (target.size >= MAX_KEYS_PER_ROW) return null
                val key = row[loc.keyIndex]
                val newRow = row.filterIndexed { i, _ -> i != loc.keyIndex }
                replaceRow(tree, loc.rowIndex, newRow)?.let {
                    replaceRow(it, loc.rowIndex - 1, target + key)
                }
            }
            MoveDirection.DOWN -> {
                if (loc.rowIndex >= currentRows.size - 1) return null
                val target = currentRows[loc.rowIndex + 1]
                if (target.size >= MAX_KEYS_PER_ROW) return null
                val key = row[loc.keyIndex]
                val newRow = row.filterIndexed { i, _ -> i != loc.keyIndex }
                replaceRow(tree, loc.rowIndex, newRow)?.let {
                    replaceRow(it, loc.rowIndex + 1, target + key)
                }
            }
        }
    }

    /** 末尾加空行（行数上限内；新行先放一个默认键，空行保存校验会判无效）。 */
    fun addRow(tree: JsonObject): JsonObject? {
        val currentRows = rows(tree)
        if (currentRows.size >= MAX_ROWS) return null
        val newKey = buildJsonObject {
            put(FIELD_STABLE_ID, newStableId(allStableIds(tree)))
            put(FIELD_LABEL, "键")
            put(FIELD_WIDTH_WEIGHT, DEFAULT_WEIGHT)
            put(FIELD_TAP, actionText("键"))
        }
        val rowsArray = (tree[FIELD_ROWS] as? JsonArray)?.toMutableList() ?: return null
        rowsArray += buildJsonObject { put(FIELD_KEYS, JsonArray(listOf(newKey))) }
        return JsonObject(tree + (FIELD_ROWS to JsonArray(rowsArray)))
    }

    /** 删行（至少保留一行）。 */
    fun removeRow(tree: JsonObject, rowIndex: Int): JsonObject? {
        val currentRows = rows(tree)
        if (currentRows.size <= 1) return null
        if (rowIndex !in currentRows.indices) return null
        val rowsArray = (tree[FIELD_ROWS] as? JsonArray)?.toMutableList() ?: return null
        rowsArray.removeAt(rowIndex)
        return JsonObject(tree + (FIELD_ROWS to JsonArray(rowsArray)))
    }

    /** 动作节点：提交文本（label 即提交文本）。 */
    fun actionText(value: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("value", value)
    }

    /** 动作节点：符号（字面提交，不经组字与标点转换）。 */
    fun actionSymbol(value: String): JsonObject = buildJsonObject {
        put("type", "symbol")
        put("value", value)
    }

    /** 动作节点：白名单命令（value 按命令的 ValueRule 携带／省略）。 */
    fun actionCommand(actionName: String, value: String? = null): JsonObject =
        buildJsonObject {
            put("type", "command")
            put("action", actionName)
            if (value != null) put("value", value)
        }

    /** 新 stableId：8 位小写字母＋数字，避开已有 id。 */
    fun newStableId(existing: Set<String>): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        repeat(100) {
            val candidate = (1..8).map { alphabet[Random.nextInt(alphabet.length)] }
                .joinToString("")
            if (candidate !in existing) return candidate
        }
        return "k${max(0, System.currentTimeMillis() % 100000000).toString().padStart(8, '0')}"
    }

    /** 树 → 保存用 JSON 文本（prettyPrint，与编辑器文本保存同格式）。 */
    fun toPrettyJson(tree: JsonObject): String =
        prettyJson.encodeToString(JsonObject.serializer(), tree)

    private fun updateKey(
        tree: JsonObject,
        stableId: String,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject? {
        val loc = findKey(tree, stableId) ?: return null
        val currentRows = rows(tree)
        val newRow = currentRows[loc.rowIndex].mapIndexed { i, key ->
            if (i == loc.keyIndex) transform(key) else key
        }
        return replaceRow(tree, loc.rowIndex, newRow)
    }

    private fun replaceRow(
        tree: JsonObject,
        rowIndex: Int,
        keys: List<JsonObject>,
    ): JsonObject? {
        val rowsArray = (tree[FIELD_ROWS] as? JsonArray)?.toMutableList() ?: return null
        if (rowIndex !in rowsArray.indices) return null
        val rowObject = rowsArray[rowIndex] as? JsonObject ?: return null
        rowsArray[rowIndex] = JsonObject(rowObject + (FIELD_KEYS to JsonArray(keys)))
        return JsonObject(tree + (FIELD_ROWS to JsonArray(rowsArray)))
    }
}
