package com.jobeen.ime.skin.draft

import com.jobeen.ime.skin.model.BackgroundSpec
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.StyleField
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 草稿树编辑纯函数（编辑器 B1 面）：一切修改返回新树，未知节点
 * 与未编辑段逐节点保留（与 round-trip 同口径）。路径以皮肤树
 * 根为起点（styles.* / appearance.* / meta.*）。
 */
object SkinTreeEditor {

    // ---- 名称 ----

    fun nameOf(tree: JsonObject): String =
        ((tree["meta"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: ""

    fun setName(tree: JsonObject, name: String): JsonObject =
        setAtPath(tree, listOf("meta", "name"), JsonPrimitive(name))

    fun skinIdOf(tree: JsonObject): String? =
        ((tree["meta"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content

    fun layoutIdOf(tree: JsonObject): String? =
        (tree["layoutId"] as? JsonPrimitive)?.content

    // ---- 配色（styles 28 叶子） ----

    fun colorOf(tree: JsonObject, field: StyleField): Int? {
        val node = nodeAtPath(tree, listOf("styles") + field.path.split("."))
        return (node as? JsonPrimitive)?.content?.let { SkinJson.parseColor(it) }
    }

    fun setColor(tree: JsonObject, field: StyleField, argb: Int): JsonObject =
        setAtPath(
            tree,
            listOf("styles") + field.path.split("."),
            JsonPrimitive(SkinJson.formatColor(argb)),
        )

    // ---- 几何（appearance 4 字段） ----

    fun geometryOf(tree: JsonObject, field: GeometryField): Float? {
        val node = nodeAtPath(tree, listOf("appearance", field.jsonName))
        val primitive = node as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toFloatOrNull()
    }

    fun setGeometry(tree: JsonObject, field: GeometryField, value: Float): JsonObject =
        setAtPath(tree, listOf("appearance", field.jsonName), JsonPrimitive(value.toDouble()))

    // ---- 背景 ----

    fun backgroundOf(tree: JsonObject): BackgroundSpec? {
        val appearance = tree["appearance"] as? JsonObject ?: return null
        val background = appearance["background"] as? JsonObject ?: return null
        if ((background["type"] as? JsonPrimitive)?.content != "asset") return null
        val assetId = (background["asset"] as? JsonPrimitive)?.content ?: return null
        val blur = (background["blurRadius"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
        val overlay = (background["overlayAlpha"] as? JsonPrimitive)?.content?.toFloatOrNull()
            ?: 0.5f
        return BackgroundSpec(assetId, blur, overlay)
    }

    fun setBackground(tree: JsonObject, spec: BackgroundSpec?): JsonObject {
        val appearance = tree["appearance"] as? JsonObject ?: JsonObject(emptyMap())
        val newAppearance = if (spec == null) {
            JsonObject(appearance - "background")
        } else {
            val background = buildJsonObject {
                put("type", "asset")
                put("asset", spec.assetId)
                put("blurRadius", spec.blurRadius.toDouble())
                put("overlayAlpha", spec.overlayAlpha.toDouble())
            }
            JsonObject(appearance + ("background" to background))
        }
        return JsonObject(tree + ("appearance" to newAppearance))
    }

    fun setBackgroundParams(
        tree: JsonObject,
        blurRadius: Float? = null,
        overlayAlpha: Float? = null,
    ): JsonObject {
        val current = backgroundOf(tree) ?: return tree
        return setBackground(
            tree,
            current.copy(
                blurRadius = blurRadius ?: current.blurRadius,
                overlayAlpha = overlayAlpha ?: current.overlayAlpha,
            ),
        )
    }

    /** 树中引用的全部资产逻辑 id（背景 + audio 值），供提交组包与校验使用。 */
    fun referencedAssetIds(tree: JsonObject): Set<String> {
        val ids = mutableSetOf<String>()
        backgroundOf(tree)?.let { ids += it.assetId }
        val audio = tree["audio"] as? JsonObject
        audio?.values?.forEach { node ->
            (node as? JsonPrimitive)?.content?.let { ids += it }
        }
        return ids
    }

    // ---- 路径工具 ----

    private fun nodeAtPath(root: JsonObject, path: List<String>): JsonElement? {
        var current: JsonElement = root
        for (segment in path) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    private fun setAtPath(root: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        if (path.isEmpty()) return root
        val head = path.first()
        if (path.size == 1) {
            return JsonObject(root + (head to value))
        }
        val child = root[head] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(root + (head to setAtPath(child, path.drop(1), value)))
    }

}
