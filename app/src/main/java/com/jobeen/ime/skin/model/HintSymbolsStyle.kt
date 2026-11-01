// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 长按符号气泡尺寸（dp）：宽高必须成对出现才生效，
 * 单边缺失/非数值/非正数时整体回落按键可视区域尺寸
 * （与短按气泡 size 同口径：宽高须同时为数值才生效）。
 */
data class HintBubbleSize(
    val width: Float,
    val height: Float,
)

/**
 * 长按符号样式（现行 form：旧 holdSymbolsStyle 已废止）：
 *
 * - symbolStyles：必填。每个元素是一个按键样式名，横向依次
 *   排列；每个符号样式本身是完整按键样式节点（前景样式 +
 *   动作），不再是旧 form 的 foregroundStyle 与 actions 两列
 *   并行数组；空/缺失时整个样式无效（不显示）。
 * - size：可选，单个符号尺寸；缺省 = 按键可视区域尺寸。
 * - insets：面板内边距（外扩作用于整体气泡），缺省 0。
 * - backgroundStyle：面板背景样式名；缺省无面板背景。
 * - selectedBackgroundStyle：选中符号的背景样式名（旧
 *   selectedStyle 已废止，旧名出现时直接忽略）。
 * - selectedIndex：初始选中下标，缺省 0；解析期夹取到
 *   [0, symbolStyles.size - 1]，使模型恒有效。
 * - checkIfOverflowsParentHeight：是否检测面板超出键盘顶部
 *   并自动调整位置，缺省 true。
 *
 * 旧 form 字段（foregroundStyle/actions/selectedStyle）一律
 * 不读取：旧 form 节点因无 symbolStyles 而整体无效。
 */
data class HintSymbolsStyle(
    val symbolStyles: List<String>,
    val size: HintBubbleSize? = null,
    val insets: AreaInsets = AreaInsets(),
    val backgroundStyle: String? = null,
    val selectedBackgroundStyle: String? = null,
    val selectedIndex: Int = 0,
    val checkIfOverflowsParentHeight: Boolean = true,
)

/**
 * 短按气泡样式（hintStyle 节点）：气泡本身是完整按键样式
 * 节点，本模型只取气泡几何与溢出两项——颜色/字形仍由按键
 * 本体变体决定（现有按键预览弹窗已按变体取色）：
 *
 * - size：可选，气泡尺寸；缺省 = 现有弹窗默认尺寸。
 * - checkIfOverflowsParentHeight：是否检测气泡超出父视图
 *   顶部并自动下移，缺省 true（与顶层 bubbleOverflowCheck
 *   同口径；逐键值优先于顶层值）。
 */
data class HintBubbleStyle(
    val size: HintBubbleSize? = null,
    val checkIfOverflowsParentHeight: Boolean = true,
)

/** 长按符号样式与短按气泡样式的纯解析（非法字段级回退，永不抛错）。 */
object HintSymbolsParser {

    /** 解析长按符号样式节点：symbolStyles 为空/缺失时返回 null。 */
    fun parseStyle(node: JsonElement?): HintSymbolsStyle? {
        val obj = node as? JsonObject ?: return null
        val symbolStyles = (obj["symbolStyles"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.mapNotNull { it.takeIf { s -> s.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val selectedIndex = (obj["selectedIndex"] as? JsonPrimitive)
            ?.intOrNull?.takeIf { it >= 0 }?.coerceIn(0, symbolStyles.lastIndex) ?: 0
        return HintSymbolsStyle(
            symbolStyles = symbolStyles,
            size = parseSize(obj["size"]),
            insets = parseInsets(obj["insets"]),
            backgroundStyle = obj.stringField("backgroundStyle"),
            selectedBackgroundStyle = obj.stringField("selectedBackgroundStyle"),
            selectedIndex = selectedIndex,
            checkIfOverflowsParentHeight = parseOverflowCheck(obj),
        )
    }

    /** 解析短按气泡样式节点：非对象时返回 null；空对象 = 全缺省。 */
    fun parseBubble(node: JsonElement?): HintBubbleStyle? {
        val obj = node as? JsonObject ?: return null
        return HintBubbleStyle(
            size = parseSize(obj["size"]),
            checkIfOverflowsParentHeight = parseOverflowCheck(obj),
        )
    }

    /**
     * 尺寸解析：width 与 height 必须同时为有限正数才生效，
     * 否则整体为 null（回落按键可视区域/默认尺寸）；
     * 百分比写法暂不支持（面板期需按键尺寸换算，见 held 项）。
     */
    fun parseSize(node: JsonElement?): HintBubbleSize? {
        val obj = node as? JsonObject ?: return null
        val width = obj["width"].strictFloatOrNull()?.takeIf { it > 0f } ?: return null
        val height = obj["height"].strictFloatOrNull()?.takeIf { it > 0f } ?: return null
        return HintBubbleSize(width, height)
    }

    /** 内边距解析：缺失边为 0，非数值/负数边按 0 计。 */
    fun parseInsets(node: JsonElement?): AreaInsets {
        val obj = node as? JsonObject ?: return AreaInsets()
        fun edge(name: String): Float =
            obj[name].strictFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        return AreaInsets(
            left = edge("left"),
            top = edge("top"),
            right = edge("right"),
            bottom = edge("bottom"),
        )
    }

    private fun parseOverflowCheck(obj: JsonObject): Boolean {
        val primitive = obj["checkIfOverflowsParentHeight"] as? JsonPrimitive ?: return true
        return primitive.booleanOrNull ?: true
    }

    private fun JsonObject.stringField(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content.takeIf { it.isNotEmpty() } else null
    }

    private fun JsonElement?.strictFloatOrNull(): Float? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.content.toFloatOrNull() ?: return null
        return value.takeIf { it.isFinite() }
    }
}
