// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

/**
 * 集合（collection）约束（模型侧行为，对端校验口径的 Jime 映射）：
 *
 * - 前景只取文字属性：集合单元格的文字由数据源提供，单元格
 *   前景样式只定文字属性（字号／字重／文字色），不渲染成文字
 *   图层之外的东西——本类型 [ForegroundTextAttrs] 除此三项外
 *   什么都装不下，规则由类型强制，而非运行时检查。
 * - styleName 只取背景＋前景：数据项 styleName 所指样式中，
 *   只有 backgroundStyle 与 foregroundStyle 两项生效，其余属
 *   性一律无效——本类型 [StyleNameStyle] 除此两项外什么都装
 *   不下；行为（action）恒取自数据项本身，永不取自样式。
 * - 悬空样式引用 = 静默空白：引用了不存在的样式名时不报错、
 *   不回退、不崩溃，该处渲染为空白（见 [resolveStyleNameCell]
 *   的 null 口径与 [findDanglingRefs]）。
 * - 深浅未成对 = 切换时空白：light/dark 只有一侧提供时，切换
 *   到缺失侧的样式取 null（空白），不回落另一侧（见
 *   [themeStyleFor]）。注意与 Jime 导入期口径的区别：导入期
 *   校验器对单边皮肤整包拒绝（skin_missing_dark_mode），那是
 *   导入门；运行期取样式缺失侧时按本函数空白处理。
 * - 非法颜色静默失败：只接受 #RRGGBB / #RRGGBBAA（对端口径，
 *   alpha 在末尾，见 parsePeerColor）；其余一律解析为 null，
 *   调用方回落，不拒包、不抛错（见测试钉住的口径）。
 * - `<<` 非穿透键：少数键只查本层、不会穿透合并基底，放进
 *   基底即失效——转换器必须把它们写在使用节点本层（见
 *   [MergeKeyPolicy]）。
 *
 * 纯函数，可直接单测；转换器校验另见系统侧 QA 说明
 * （~/workspace/system/skin-converter-qa-note.md，引用对端
 * 校验与预览脚本，不复制进仓库）。
 */
data class ForegroundTextAttrs(
    val textSizeDp: Float? = null,
    val textWeight: CandidateFontWeight? = null,
    val textColor: Int? = null,
)

/**
 * styleName 所指样式的全部有效内容：背景色（经背景样式解析）
 * 与前景文字属性；除此之外该样式的任何属性皆无效。
 */
data class StyleNameStyle(
    val background: Int? = null,
    val foreground: ForegroundTextAttrs? = null,
)

object CollectionConstraints {

    /**
     * 解析数据项 styleName 单元格样式：空名 = 取格默认文字样式
     * （返回空前景的非空结果，调用方按默认渲染）；未知名字 =
     * 悬空引用，返回 null（调用方渲染空白格，不报错不回退）。
     */
    fun resolveStyleNameCell(
        styleName: String,
        styles: Map<String, StyleNameStyle>,
    ): StyleNameStyle? {
        if (styleName.isEmpty()) return StyleNameStyle()
        return styles[styleName]
    }

    /** 悬空引用集合：被引用但未定义的样式名（各处渲染为空白）。 */
    fun findDanglingRefs(
        referenced: Set<String>,
        defined: Set<String>,
    ): Set<String> = referenced - defined

    /**
     * 深浅主题取样式：isDark 取 dark、否则取 light；所需侧缺失
     * 时返回 null（空白），绝不回落另一侧。
     */
    fun themeStyleFor(
        light: StyleNameStyle?,
        dark: StyleNameStyle?,
        isDark: Boolean,
    ): StyleNameStyle? = if (isDark) dark else light
}

/**
 * YAML 合并键 `<<` 非穿透键（对端架构 §6 全表）：这些键只查
 * 本层，不会穿透 `<<` 基底；只写在基底里即失效，转换器必须
 * 把它们写在使用节点本层。校验见 [MergeKeyPolicy]。
 */
object MergeKeyPolicy {

    /** 非穿透键全集（对端架构 §6 原文顺序）。 */
    val NON_PENETRATING: Set<String> = setOf(
        "maxColumns",
        "maxRows",
        "contentRightToLeft",
        "colorLocation",
        "colorStartPoint",
        "colorEndPoint",
        "colorGradientType",
        "shadowOpacity",
        "type",
        "floatKeyboardAlpha",
        "floatKeyboardLockedState",
        "floatTargetScale",
    )

    /**
     * 找出只写在基底、未写在本层的非穿透键（即会静默失效的
     * 键）。本层已写同名键时不算违规（本层值生效）。
     */
    fun findBaseOnlyNonPenetrating(
        baseKeys: Set<String>,
        ownKeys: Set<String>,
    ): Set<String> = (baseKeys intersect NON_PENETRATING) - ownKeys
}
