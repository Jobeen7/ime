package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按键样式几何（审计 A 项）：对端背景样式的几何面。
 *
 * - 颜色口径与既有皮肤字段不同：对端 8 位十六进制为 RRGGBBAA
 *  （alpha 在末尾），见 [parsePeerColor]；6 位为不透明。
 *   既有皮肤字段仍走 [SkinJson.parseColor]（AARRGGBB），两者
 *   不可混用——本文件所有颜色解析只认对端口径。
 * - 字段级回退：非法字段逐项丢弃并记警告，绝不拒整包。
 *
 * 纯解析，可直接单测；渲染见 input/keyboard/key/KeyDrawable。
 */

/** 对端颜色解析：#RRGGBB（不透明）或 #RRGGBBAA（alpha 在末尾）。 */
fun parsePeerColor(raw: String): Int? {
    val s = raw.trim()
    if (!s.startsWith("#")) return null
    val hex = s.substring(1)
    if (hex.isEmpty() ||
        hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }
    ) {
        return null
    }
    return when (hex.length) {
        6 -> (0xFF000000L or hex.toLong(16)).toInt()
        8 -> {
            val rgb = hex.substring(0, 6).toLong(16)
            val alpha = hex.substring(6, 8).toLong(16)
            ((alpha shl 24) or rgb).toInt()
        }
        else -> null
    }
}

/** 渐变类型：axial=线性，radial=径向，conic=锥形（扫掠）。 */
enum class KeyGradientType {
    LINEAR,
    RADIAL,
    CONIC,
}

/** 渐变端点（0..1 相对坐标：x 横向，y 纵向）。 */
data class KeyGradientPoint(
    val x: Float,
    val y: Float,
)

/** 单支渐变：颜色数组 + 可选位置数组 + 类型 + 可选起止点。 */
data class KeyGradient(
    val colors: List<Int>,
    /** 与 colors 等长的位置数组；null = 均匀分布。 */
    val locations: List<Float>? = null,
    val type: KeyGradientType = KeyGradientType.LINEAR,
    val startPoint: KeyGradientPoint? = null,
    val endPoint: KeyGradientPoint? = null,
) {
    /** 是否为有效渐变：至少两个颜色（单色等价纯色，由调用方按纯色处理）。 */
    val isMultiColor: Boolean get() = colors.size >= 2
}

/**
 * 尺寸分数解析（审计 A 项）：对端尺寸写法。
 *
 * - 数字 → 本身；
 * - 字符串 "a/b"（a、b 有限数，b 非零，结果为正）→ a/b；
 * - 字符串纯数字 → 该数字；
 * - 对象 {"percentage": n} → n；
 * - 其余 → null（调用方按字段回退处理）。
 */
object SizeFraction {

    fun parse(node: JsonElement?): Double? {
        return when (node) {
            null -> null
            is JsonPrimitive -> {
                if (!node.isString) {
                    node.content.toDoubleOrNull()?.takeIf { it.isFinite() }
                } else {
                    parseString(node.content)
                }
            }
            is JsonObject -> {
                val percentage = node["percentage"] as? JsonPrimitive
                if (percentage != null && !percentage.isString) {
                    percentage.content.toDoubleOrNull()?.takeIf { it.isFinite() }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun parseFloat(node: JsonElement?): Float? = parse(node)?.toFloat()

    private fun parseString(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if ('/' in s) {
            val parts = s.split("/")
            if (parts.size != 2) return null
            val a = parts[0].trim().toDoubleOrNull()
            val b = parts[1].trim().toDoubleOrNull()
            if (a == null || b == null || !a.isFinite() || !b.isFinite() || b == 0.0) {
                return null
            }
            val result = a / b
            return result.takeIf { it.isFinite() && it > 0.0 }
        }
        return s.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }
}

object KeyGradientParser {

    /**
     * 从几何对象解析常态/按压两支渐变。
     * 颜色字段：normalColor / highlightColor（字符串单色或颜色数组）；
     * 位置与类型两支共用：colorLocation / colorGradientType /
     * colorStartPoint / colorEndPoint。单色不构成渐变（返回 null，
     * 调用方按纯色角色处理）；数组含非法项时丢弃该项并记警告，
     * 剩余不足两色时整支丢弃。
     */
    fun parseFaces(
        geometry: JsonObject,
    ): Triple<KeyGradient?, KeyGradient?, List<String>> {
        val warnings = mutableListOf<String>()
        val (normalColors, normalWarnings) = parseColorList(geometry["normalColor"], "normalColor")
        val (highlightColors, highlightWarnings) =
            parseColorList(geometry["highlightColor"], "highlightColor")
        warnings += normalWarnings
        warnings += highlightWarnings
        val locations = parseLocations(geometry["colorLocation"], warnings)
        val type = parseType(geometry["colorGradientType"], warnings)
        val start = parsePoint(geometry["colorStartPoint"], "colorStartPoint", warnings)
        val end = parsePoint(geometry["colorEndPoint"], "colorEndPoint", warnings)
        fun build(colors: List<Int>?): KeyGradient? {
            if (colors == null || colors.size < 2) return null
            val loc = locations?.takeIf { it.size == colors.size }
            if (locations != null && loc == null) {
                warnings += "gradient_location_mismatch"
            }
            return KeyGradient(colors, loc, type, start, end)
        }
        return Triple(build(normalColors), build(highlightColors), warnings)
    }

    private fun parseColorList(
        node: JsonElement?,
        field: String,
    ): Pair<List<Int>?, List<String>> {
        if (node == null) return null to emptyList()
        val warnings = mutableListOf<String>()
        when (node) {
            is JsonPrimitive -> {
                if (!node.isString) return null to listOf("gradient_${field}_invalid")
                val color = parsePeerColor(node.content)
                    ?: return null to listOf("gradient_${field}_invalid")
                return listOf(color) to warnings
            }
            is JsonArray -> {
                val colors = mutableListOf<Int>()
                for (item in node) {
                    val raw = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val color = raw?.let { parsePeerColor(it) }
                    if (color == null) {
                        warnings += "gradient_${field}_invalid"
                    } else {
                        colors += color
                    }
                }
                return colors to warnings
            }
            else -> return null to listOf("gradient_${field}_invalid")
        }
    }

    private fun parseLocations(
        node: JsonElement?,
        warnings: MutableList<String>,
    ): List<Float>? {
        if (node == null) return null
        val array = node as? JsonArray
            ?: run {
                warnings += "gradient_location_invalid"
                return null
            }
        val values = mutableListOf<Float>()
        for (item in array) {
            val primitive = item as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toFloatOrNull()
            } else {
                null
            }
            if (value == null || !value.isFinite()) {
                warnings += "gradient_location_invalid"
                return null
            }
            values += value
        }
        return values
    }

    private fun parseType(
        node: JsonElement?,
        warnings: MutableList<String>,
    ): KeyGradientType {
        if (node == null) return KeyGradientType.LINEAR
        val raw = (node as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
        return when (raw) {
            "axial", "linear" -> KeyGradientType.LINEAR
            "radial" -> KeyGradientType.RADIAL
            "conic", "sweep" -> KeyGradientType.CONIC
            else -> {
                warnings += "gradient_type_invalid"
                KeyGradientType.LINEAR
            }
        }
    }

    private fun parsePoint(
        node: JsonElement?,
        field: String,
        warnings: MutableList<String>,
    ): KeyGradientPoint? {
        if (node == null) return null
        val obj = node as? JsonObject
            ?: run {
                warnings += "gradient_${field}_invalid"
                return null
            }
        fun component(name: String): Float? {
            val primitive = obj[name] as? JsonPrimitive
            if (primitive != null && !primitive.isString) {
                return primitive.content.toFloatOrNull()?.takeIf { it.isFinite() }
            }
            return null
        }
        val x = component("x")
        val y = component("y")
        if (x == null || y == null) {
            warnings += "gradient_${field}_invalid"
            return null
        }
        var clamped = false
        val cx = x.coerceIn(0f, 1f).also { if (it != x) clamped = true }
        val cy = y.coerceIn(0f, 1f).also { if (it != y) clamped = true }
        if (clamped) warnings += "gradient_${field}_clamped"
        return KeyGradientPoint(cx, cy)
    }
}
