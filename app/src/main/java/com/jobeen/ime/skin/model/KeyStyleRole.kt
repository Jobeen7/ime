package com.jobeen.ime.skin.model

/**
 * 单键样式角色（自 KeyStyle.kt 迁出的活声明；
 * LayoutModel.KeyDef.styleOverrides 的键类型）。
 */

/**
 * 单键样式（2026-10-27 并入项，样式名引用 + 单键覆盖）：
 *
 * - 皮肤文件 `keyStyles` 表：样式名 → 5 角色 → 取值字符串；
 * - 键（keyMappings 条目 / 自创布局键节点）以 `style` 引用样式名，
 *   以 `styleOverrides` 逐角色覆盖（单键样式覆盖，逐角色优先）。
 * - 取值字符串先按令牌解析：命中 [StyleField] 路径名时取快照
 *   该令牌值（链路：派生 → 快照 → 样式引用）；非令牌时按字面
 *   颜色解析；两者皆非 → 该角色回退（跳过 + 警告，不拒整包）。
 *
 * 6 角色（渲染面，与 KeyDef 皮肤色槽一一对应）：
 * face=键面，pressed=按压面，text=主文字，altText=副文字，border=描边，
 * icon=图标染色（G1 补：Image/ImageText 键的图标 tint；无图标的键忽略该角色）。
 * 审计 A 项补：pressedBorder=按压描边，lowerEdge=下边缘，
 * pressedLowerEdge=按压下边缘；Bin-2 补项：pressedText=按压态主文字。
 */
enum class KeyStyleRole(val jsonName: String) {
    FACE("face"),
    PRESSED("pressed"),
    TEXT("text"),
    ALT_TEXT("altText"),
    BORDER("border"),
    ICON("icon"),
    /** 按压态描边（审计 A 项：highlightBorderColor 的角色面）。 */
    PRESSED_BORDER("pressedBorder"),
    /** 下边缘色（审计 A 项：normalLowerEdgeColor 的角色面）。 */
    LOWER_EDGE("lowerEdge"),
    /** 按压态下边缘色（审计 A 项：highlightLowerEdgeColor 的角色面）。 */
    PRESSED_LOWER_EDGE("pressedLowerEdge"),
    /** 按压态主文字色（Bin-2 补项：text 样式 highlightColor 的角色面）。 */
    PRESSED_TEXT("pressedText"),
    ;

    companion object {
        fun byJsonName(name: String): KeyStyleRole? = entries.firstOrNull { it.jsonName == name }
    }
}
