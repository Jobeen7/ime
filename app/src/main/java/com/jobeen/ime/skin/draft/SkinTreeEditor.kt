package com.jobeen.ime.skin.draft

import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.model.BackgroundSpec
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.KeyBindingRejection
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinKeyMappingsParser
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

    /**
     * 改绑/解绑自创布局（B3 §4.6 皮肤编辑内改绑）：null = 移除
     * 节点（键缺失即不绑定，与校验器的 layoutId 未设口径一致）。
     */
    fun setLayoutId(tree: JsonObject, layoutId: String?): JsonObject =
        if (layoutId == null) {
            JsonObject(tree - "layoutId")
        } else {
            JsonObject(tree + ("layoutId" to JsonPrimitive(layoutId)))
        }

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

    /** 树中引用的全部资产逻辑 id（背景 + audio 值 + 逐键音效），供提交组包与校验使用。 */
    fun referencedAssetIds(tree: JsonObject): Set<String> {
        val ids = mutableSetOf<String>()
        backgroundOf(tree)?.let { ids += it.assetId }
        val audio = tree["audio"] as? JsonObject
        audio?.values?.forEach { node ->
            (node as? JsonPrimitive)?.content?.let { ids += it }
        }
        val mappings = tree["keyMappings"] as? JsonObject
        mappings?.values?.forEach { entry ->
            val sound = ((entry as? JsonObject)?.get("sound") as? JsonPrimitive)?.content
            if (!sound.isNullOrEmpty()) ids += sound
        }
        return ids
    }

    // ---- keyMappings / audio（B2 逐键编辑） ----

    fun keyMappingsOf(tree: JsonObject): JsonObject? = tree["keyMappings"] as? JsonObject

    fun keyEntryOf(tree: JsonObject, keyId: String): JsonObject? =
        (tree["keyMappings"] as? JsonObject)?.get(keyId) as? JsonObject

    /**
     * 写入/删除单键覆写条目：只动该键节点——条目内未知字段、其余
     * 键、其余段落逐节点保留；映射删空时整段移除（树保持干净）。
     */
    fun setKeyEntry(tree: JsonObject, keyId: String, entry: JsonObject?): JsonObject {
        val mappings = tree["keyMappings"] as? JsonObject ?: JsonObject(emptyMap())
        val newMappings = if (entry == null) {
            JsonObject(mappings - keyId)
        } else {
            JsonObject(mappings + (keyId to entry))
        }
        return if (newMappings.isEmpty()) {
            JsonObject(tree - "keyMappings")
        } else {
            JsonObject(tree + ("keyMappings" to newMappings))
        }
    }

    fun audioTapOf(tree: JsonObject): String? =
        ((tree["audio"] as? JsonObject)?.get("tap") as? JsonPrimitive)?.content

    /** 设置/移除全局点按音（audio.tap）；audio 段删空时整段移除。 */
    fun setAudioTap(tree: JsonObject, assetId: String?): JsonObject {
        val audio = tree["audio"] as? JsonObject ?: JsonObject(emptyMap())
        val newAudio = if (assetId == null) {
            JsonObject(audio - "tap")
        } else {
            JsonObject(audio + ("tap" to JsonPrimitive(assetId)))
        }
        return if (newAudio.isEmpty()) {
            JsonObject(tree - "audio")
        } else {
            JsonObject(tree + ("audio" to newAudio))
        }
    }

    /**
     * 编辑器写前预校验（详设 §6.3-2）：用与导入相同的解析规则试
     * 解析候选条目，返回逐项剔除清单——非空即该绑定不可保存。
     * sequence 占位不产生剔除（保留不生效是合法态）。
     */
    fun validateKeyEntry(keyId: String, entry: JsonObject): List<KeyBindingRejection> {
        val probe = JsonObject(mapOf(keyId to entry))
        return SkinKeyMappingsParser.parse(
            probe, KeyIds::isOverridable, KeyIds::isAnchor,
        ).rejections
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
