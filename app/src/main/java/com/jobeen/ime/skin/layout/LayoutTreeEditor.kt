package com.jobeen.ime.skin.layout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * layout.json 原始树的编辑操作（B3 详设 §4.2）：全部为纯函数，
 * 只动格式已知的位置，未知节点逐一原样保留。畸形形状（rows 非
 * 数组等）按空集读、越界操作原样返回——编辑器永不因坏树崩溃，
 * 坏到校验不过时由校验器给出问题清单、预览停留在最近合法结构。
 */
object LayoutTreeEditor {

    fun rowsOf(tree: JsonObject): List<JsonObject> =
        (tree["rows"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun keysOf(row: JsonObject): List<JsonObject> =
        (row["keys"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun keyCount(tree: JsonObject): Int = rowsOf(tree).sumOf { keysOf(it).size }

    fun layoutIdOf(tree: JsonObject): String? =
        ((tree["meta"] as? JsonObject)?.get("id") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

    fun nameOf(tree: JsonObject): String? =
        ((tree["meta"] as? JsonObject)?.get("name") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

    fun setName(tree: JsonObject, name: String): JsonObject {
        val meta = tree["meta"] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(tree + ("meta" to JsonObject(meta + ("name" to JsonPrimitive(name)))))
    }

    private fun withRows(tree: JsonObject, rows: List<JsonObject>): JsonObject =
        JsonObject(tree + ("rows" to JsonArray(rows)))

    private fun withKeys(row: JsonObject, keys: List<JsonObject>): JsonObject =
        JsonObject(row + ("keys" to JsonArray(keys)))

    /** 追加空行。 */
    fun addRow(tree: JsonObject): JsonObject =
        withRows(tree, rowsOf(tree) + JsonObject(mapOf("keys" to JsonArray(emptyList()))))

    fun removeRow(tree: JsonObject, rowIndex: Int): JsonObject {
        val rows = rowsOf(tree)
        if (rowIndex !in rows.indices) return tree
        return withRows(tree, rows.filterIndexed { i, _ -> i != rowIndex })
    }

    fun addKey(tree: JsonObject, rowIndex: Int, keyNode: JsonObject): JsonObject {
        val rows = rowsOf(tree)
        if (rowIndex !in rows.indices) return tree
        return withRows(
            tree,
            rows.mapIndexed { i, row ->
                if (i == rowIndex) withKeys(row, keysOf(row) + keyNode) else row
            },
        )
    }

    fun removeKey(tree: JsonObject, rowIndex: Int, keyIndex: Int): JsonObject {
        val rows = rowsOf(tree)
        if (rowIndex !in rows.indices) return tree
        val keys = keysOf(rows[rowIndex])
        if (keyIndex !in keys.indices) return tree
        return withRows(
            tree,
            rows.mapIndexed { i, row ->
                if (i == rowIndex) {
                    withKeys(row, keys.filterIndexed { j, _ -> j != keyIndex })
                } else {
                    row
                }
            },
        )
    }

    fun setKeyNode(
        tree: JsonObject,
        rowIndex: Int,
        keyIndex: Int,
        node: JsonObject,
    ): JsonObject {
        val rows = rowsOf(tree)
        if (rowIndex !in rows.indices) return tree
        val keys = keysOf(rows[rowIndex])
        if (keyIndex !in keys.indices) return tree
        return withRows(
            tree,
            rows.mapIndexed { i, row ->
                if (i == rowIndex) {
                    withKeys(
                        row,
                        keys.mapIndexed { j, key -> if (j == keyIndex) node else key },
                    )
                } else {
                    row
                }
            },
        )
    }

    /** 键跨行/行内移动（toIndex 为目标行内的插入位，钳制到行尾）。 */
    fun moveKey(
        tree: JsonObject,
        fromRow: Int,
        fromIndex: Int,
        toRow: Int,
        toIndex: Int,
    ): JsonObject {
        val rows = rowsOf(tree)
        if (fromRow !in rows.indices || toRow !in rows.indices) return tree
        val fromKeys = keysOf(rows[fromRow])
        if (fromIndex !in fromKeys.indices) return tree
        val moved = fromKeys[fromIndex]
        val without = removeKey(tree, fromRow, fromIndex)
        val targetKeys = keysOf(rowsOf(without)[toRow]).toMutableList()
        targetKeys.add(toIndex.coerceIn(0, targetKeys.size), moved)
        return withRows(
            without,
            rowsOf(without).mapIndexed { i, row ->
                if (i == toRow) withKeys(row, targetKeys) else row
            },
        )
    }

    /** 下一个 stableId：现有数值型 id 的最大值 + 1（无则 1），十进制字符串。 */
    fun nextStableId(tree: JsonObject): String {
        val maxNumeric = rowsOf(tree)
            .flatMap { keysOf(it) }
            .mapNotNull { key ->
                ((key["stableId"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                    ?.toIntOrNull()
            }
            .maxOrNull() ?: 0
        return (maxNumeric + 1).toString()
    }

    /** 字母键节点（tap = 文本自身）。 */
    fun letterKey(stableId: String, label: String): JsonObject = buildJsonObject {
        put("stableId", stableId)
        put("label", label)
        put("tap", buildJsonObject {
            put("type", "text")
            put("value", label)
        })
    }

    /** 命令键节点（tap = 白名单命令，可带 value）。 */
    fun commandKey(
        stableId: String,
        label: String,
        action: String,
        value: String? = null,
        widthWeight: Double? = null,
    ): JsonObject = buildJsonObject {
        put("stableId", stableId)
        put("label", label)
        if (widthWeight != null) put("widthWeight", widthWeight)
        put("tap", buildJsonObject {
            put("type", "command")
            put("action", action)
            if (value != null) put("value", value)
        })
    }

    /**
     * 空白新建的起始布局：4 行 QWERTY 字母 + 功能底行（删除/空格/
     * 回车），stableId 全局顺序编号。结构合法（可直接提交），
     * 用户在此之上增删改。
     */
    fun blankStarter(layoutId: String, name: String): JsonObject {
        var next = 0
        fun id(): String = (++next).toString()
        val rows = mutableListOf<JsonObject>()
        for (letters in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")) {
            val keys = letters.map { letterKey(id(), it.toString()) }.toMutableList()
            if (letters == "zxcvbnm") {
                keys += commandKey(id(), "⌫", "delete", widthWeight = 1.5)
            }
            rows += JsonObject(mapOf("keys" to JsonArray(keys)))
        }
        rows += JsonObject(
            mapOf(
                "keys" to JsonArray(
                    listOf(
                        commandKey(id(), ", ", "commit_direct", value = ","),
                        commandKey(id(), "空格", "space", widthWeight = 3.0),
                        commandKey(id(), ". ", "commit_direct", value = "."),
                        commandKey(id(), "回车", "enter", widthWeight = 1.5),
                    ),
                ),
            ),
        )
        return buildJsonObject {
            put("formatVersion", 3)
            put("meta", buildJsonObject {
                put("id", layoutId)
                put("name", name)
            })
            put("rows", buildJsonArray { rows.forEach { add(it) } })
        }
    }
}
