package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * skin.json 原始 JSON 树的通用工具（定稿三.2：以原始树为准存储，
 * 解析只在其上构建快照，未知节点原样保留）。
 *
 * 本文件全部为纯函数，不触碰 Android API，可直接单测。
 */
object SkinJson {

    /** 解析为 JSON 对象；失败或根不是对象 → null（结构性损坏口径，定稿九.3）。 */
    fun parseObject(text: String): JsonObject? =
        runCatching { JsonTreeParser.parse(text) as? JsonObject }.getOrNull()

    /** 树深度：标量为 1，容器 = 1 + 子元素最大深度（空容器为 1）。 */
    fun depth(element: JsonElement): Int = when (element) {
        is JsonObject -> 1 + (element.values.maxOfOrNull { depth(it) } ?: 0)
        is JsonArray -> 1 + (element.maxOfOrNull { depth(it) } ?: 0)
        else -> 1
    }

    /** 树内最长字符串（含对象 key）长度，字符数口径（定稿十一.5 单字符串 ≤4KB 的近似：按字符计）。 */
    fun maxStringLength(element: JsonElement): Int = when (element) {
        is JsonObject -> element.entries.maxOfOrNull { (k, v) ->
            maxOf(k.length, maxStringLength(v))
        } ?: 0
        is JsonArray -> element.maxOfOrNull { maxStringLength(it) } ?: 0
        is JsonPrimitive -> if (element.isString) element.content.length else 0
        else -> 0
    }

    /**
     * 颜色字面量解析（定稿九.1：须为 #RRGGBB 或 #AARRGGBB）。
     * 返回 ARGB Int；格式不符 → null（由调用方按坏字段回填）。
     */
    fun parseColor(raw: String): Int? {
        val s = raw.trim()
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        if (hex.isEmpty() || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return null
        }
        return when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    }

    /**
     * `keyboards` 表解析（多主槽一期）：skin.json 顶层 `keyboards`
     * 对象 → 类型名 → [KeyboardTypeEntry]。字段级回退口径，与
     * 校验器其余字段一致：整表/单项形态不对、字段值非法时只丢
     * 该项/该字段，皮肤照常成立，绝不因表损坏而拒皮肤。
     * - 项形态：对象（含 layoutId/layoutIdLandscape/heightPercent/
     *   heightPercentLandscape 任一有效字段）或字符串简写（即
     *   layoutId）；其余形态（数字/数组/空对象/全空对象）丢弃。
     * - 高度域 50–200（与布局文件 heightPercent 同口径），域外
     *   或非数字丢弃该字段，不丢整项。
     * - 本函数只读原始树：校验器不认 `keyboards` 键（未知顶层
     *   节点原样保留在树里），快照工厂在覆盖层合并后的生效树
     *   上调用本函数，故覆盖层亦可改表。
     */
    fun parseKeyboards(tree: JsonObject): Map<String, KeyboardTypeEntry> {
        val table = tree["keyboards"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, KeyboardTypeEntry>()
        for ((type, raw) in table) {
            if (type.isBlank()) continue
            val entry = when (raw) {
                is JsonPrimitive -> if (raw.isString) {
                    raw.content.takeIf { it.isNotBlank() }
                        ?.let { KeyboardTypeEntry(layoutId = it) }
                } else {
                    null
                }
                is JsonObject -> KeyboardTypeEntry(
                    layoutId = raw.stringField("layoutId"),
                    layoutIdLandscape = raw.stringField("layoutIdLandscape"),
                    heightPercent = raw.rangedPercent("heightPercent"),
                    heightPercentLandscape = raw.rangedPercent("heightPercentLandscape"),
                )
                else -> null
            } ?: continue
            if (entry == KeyboardTypeEntry()) continue
            out[type] = entry
        }
        return out
    }

    private fun JsonObject.stringField(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }
            ?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.rangedPercent(name: String): Double? =
        (get(name) as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?.takeIf { it in 50.0..200.0 }

    /** ARGB Int → #AARRGGBB（与 ReadableTheme 的 HexColorSerializer 同口径）。 */
    fun formatColor(argb: Int): String = "#%08X".format(argb)

    /** 逐节点深比较（round-trip 验收口径，定稿十六.2）：对象按 key 集合比，数组按序比，标量按字面量比。 */
    fun deepEquals(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonObject && b is JsonObject) {
            if (a.keys != b.keys) return false
            return a.keys.all { deepEquals(a.getValue(it), b.getValue(it)) }
        }
        if (a is JsonArray && b is JsonArray) {
            if (a.size != b.size) return false
            return a.indices.all { deepEquals(a[it], b[it]) }
        }
        if (a is JsonPrimitive && b is JsonPrimitive) {
            return a.isString == b.isString && a.content == b.content
        }
        return a == b
    }
}

/** 内部解析入口（与 SkinLibraryStore 的宽容口径分开：格式校验须严格按树解析）。 */
internal object JsonTreeParser {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun parse(text: String): JsonElement = json.parseToJsonElement(text)
}
