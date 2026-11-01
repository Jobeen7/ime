package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 区域样式解析（审计 B / I 项：工具栏、预编辑、候选区）。
 *
 * 本文件只做纯解析：输入 JSON 节点，输出强类型规格 + issue，
 * 不读快照、不碰 Android，可直接单测。消费点（窗口、渲染器、
 * 预编辑宿主）各取所需字段，缺失一律回落旧写死值。
 *
 * 长度写法（对端 `preeditHeight` / `toolbarHeight` 同款）：
 * 数字 = dp；字符串 `"Nvh"` = 视口高度百分比（N 为十进制数）。
 * 字符串数字（如 `"48"`）不算数值（与九.1 严格口径一致），
 * 按非法记 issue 并丢弃。
 */

/** 四边内边距（dp）：节点缺失的边为 0（对端 insets 只列非零边）。 */
data class AreaInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isZero: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

/**
 * dp 或视口高度百分比二选一的长度。
 * `dp != null` = 数字写法；`vhPercent != null` = `"Nvh"` 写法；
 * 两者皆 null 不出现（解析失败返回 null，不以空规格占位）。
 */
data class VhLengthSpec(
    val dp: Float?,
    val vhPercent: Float?,
) {
    /**
     * 解析为 dp：vh 写法按视口高度换算，dp 写法直取，
     * 规格缺失（null 接收方）时调用方传 fallback。
     */
    fun resolveDp(screenHeightDp: Float, fallbackDp: Float): Float =
        if (vhPercent != null) {
            (screenHeightDp * vhPercent / 100f).coerceAtLeast(0f)
        } else {
            dp ?: fallbackDp
        }
}

private val VH_PATTERN = Regex("""^\s*(\d+(?:\.\d+)?)\s*vh\s*$""", RegexOption.IGNORE_CASE)

/**
 * 解析 dp / `"Nvh"` 长度节点。
 *
 * @param dpRange 数字写法的合法区间；vh 写法的合法区间固定 0..50。
 * @return null = 节点缺失（调用方回落）或非法（已记 issue，调用方回落）。
 */
fun parseVhLength(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
    dpRange: ClosedFloatingPointRange<Float>,
): VhLengthSpec? {
    if (node == null) return null
    val primitive = node as? JsonPrimitive
    if (primitive == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "length_not_scalar")
        return null
    }
    if (primitive.isString) {
        val raw = primitive.contentOrNull ?: ""
        val match = VH_PATTERN.matchEntire(raw)
        val percent = match?.groupValues?.get(1)?.toFloatOrNull()
        if (percent != null && percent.isFinite() && percent in 0f..50f) {
            return VhLengthSpec(dp = null, vhPercent = percent)
        }
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "length_vh_invalid")
        return null
    }
    val value = primitive.doubleOrNull?.toFloat()
    if (value != null && value.isFinite() && value in dpRange) {
        return VhLengthSpec(dp = value, vhPercent = null)
    }
    issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "length_dp_invalid")
    return null
}

/** 解析 insets 对象：每边为数字（dp，0..64），非数字边记 issue 并按 0 计。 */
fun parseAreaInsets(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): AreaInsets {
    val obj = node as? JsonObject ?: run {
        if (node != null) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "insets_not_object")
        }
        return AreaInsets()
    }
    fun side(name: String): Float {
        val raw = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()
        if (obj.containsKey(name) && (raw == null || !raw.isFinite() || raw !in 0f..64f)) {
            issues += SkinIssue("$path.$name", SkinIssue.Kind.FIELD_FALLBACK, "inset_invalid")
            return 0f
        }
        return raw ?: 0f
    }
    return AreaInsets(
        left = side("left"),
        top = side("top"),
        right = side("right"),
        bottom = side("bottom"),
    )
}

/** 解析颜色字符串节点：非法记 issue 并返回 null（调用方回落）。 */
fun parseStyleColor(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): Int? {
    if (node == null) return null
    val raw = (node as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val parsed = raw?.let { SkinJson.parseColor(it) }
    if (parsed == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "color_invalid")
    }
    return parsed
}

/** 解析 dp 数值节点：非法记 issue 并返回 null（调用方回落）。 */
fun parseDpField(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
    range: ClosedFloatingPointRange<Float>,
): Float? {
    if (node == null) return null
    val value = (node as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()
    if (value == null || !value.isFinite() || value !in range) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "dp_invalid")
        return null
    }
    return value
}
