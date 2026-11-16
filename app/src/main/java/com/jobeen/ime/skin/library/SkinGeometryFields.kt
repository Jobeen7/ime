// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 可视化皮肤编辑器的几何字段表（与网页编辑器 11 滑块同口径，
 * 见 ~/workspace/system/skin-tools/jime-skin-editor/index.html
 * 的 GEO_FIELDS；区间取 App 滑条口径）。
 *
 * 纯数据＋纯函数，可 JVM 单测：
 * - appearance.* 与 skin.json appearance 段同名；
 * - geometry.* 优先于 appearance 同义字段（与 App 口径一致，
 *   网页编辑器同注），编辑器写哪边就读哪边，不做跨边同步。
 */
data class SkinGeometryField(
    /** dotted path，如 "appearance.cornerRadius" / "geometry.keyRadiusDp"。 */
    val path: String,
    val label: String,
    val min: Float,
    val max: Float,
    val unit: String,
    val step: Float = 0.5f,
)

/** 11 个几何滑块（网页编辑器顺序：appearance 7 项＋geometry 4 项）。 */
val SKIN_GEOMETRY_FIELDS: List<SkinGeometryField> = listOf(
    SkinGeometryField("appearance.cornerRadius", "按键圆角", 0f, 40f, "dp"),
    SkinGeometryField("appearance.keyHMargin", "按键横向间距", 0f, 20f, "dp"),
    SkinGeometryField("appearance.keyVMargin", "按键纵向间距", 0f, 20f, "dp"),
    SkinGeometryField("appearance.bottomInsetDp", "底部留白", 0f, 48f, "dp"),
    SkinGeometryField("appearance.topInsetDp", "顶部留白", 0f, 48f, "dp"),
    SkinGeometryField("appearance.leftInsetDp", "左侧留白", 0f, 48f, "dp"),
    SkinGeometryField("appearance.rightInsetDp", "右侧留白", 0f, 48f, "dp"),
    SkinGeometryField("geometry.keyRadiusDp", "按键圆角（geometry，优先）", 0f, 40f, "dp"),
    SkinGeometryField("geometry.keyHGapDp", "按键横向间距（geometry，优先）", 0f, 20f, "dp"),
    SkinGeometryField("geometry.keyVGapDp", "按键纵向间距（geometry，优先）", 0f, 20f, "dp"),
    SkinGeometryField("geometry.heightPercent", "键盘高度", 20f, 70f, "%"),
)

/** 读某几何字段的当前值（缺失／非数字 → null，调用方用滑块缺省）。 */
fun readGeometryValue(tree: JsonObject, field: SkinGeometryField): Float? {
    var node: kotlinx.serialization.json.JsonElement = tree
    for (segment in field.path.split(".")) {
        node = (node as? JsonObject)?.get(segment) ?: return null
    }
    return (node as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
}

/** 写某几何字段，返回新树（原树不动；中间段缺失时建空对象）。 */
fun writeGeometryValue(tree: JsonObject, field: SkinGeometryField, value: Float): JsonObject {
    val segments = field.path.split(".")
    return writeNested(tree, segments, JsonPrimitive(value.toDouble()))
}

private fun writeNested(
    tree: JsonObject,
    segments: List<String>,
    value: kotlinx.serialization.json.JsonElement,
): JsonObject {
    val head = segments.first()
    val rest = segments.drop(1)
    val child = if (rest.isEmpty()) {
        value
    } else {
        writeNested(tree[head] as? JsonObject ?: JsonObject(emptyMap()), rest, value)
    }
    return JsonObject(tree.toMutableMap().also { it[head] = child })
}

/** 读样式板（styles / stylesDark）某 dotted 路径的颜色值（#AARRGGBB 串）。 */
fun readStyleColor(tree: JsonObject, board: String, path: String): String? {
    var node: kotlinx.serialization.json.JsonElement = tree
    for (segment in (listOf(board) + path.split("."))) {
        node = (node as? JsonObject)?.get(segment) ?: return null
    }
    return (node as? JsonPrimitive)?.contentOrNull
}

/** 写样式板某 dotted 路径的颜色值，返回新树（原树不动）。 */
fun writeStyleColor(tree: JsonObject, board: String, path: String, hex: String): JsonObject {
    val segments = listOf(board) + path.split(".")
    return writeNested(tree, segments, JsonPrimitive(hex))
}
