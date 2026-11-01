// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.HintSymbolNode
import com.jobeen.ime.skin.model.HintSymbolsGridStyle
import com.jobeen.ime.skin.model.HintSymbolsStyle
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinAction

/**
 * 长按符号样式背景解析（纯函数）：面板背景与选中背景两个
 * 样式名按现有单键样式表解析为角色色（与覆写装配器同口径）；
 * 未知名/无值时对应项为 null（面板期回落默认背景）。
 *
 * per-symbol 完整节点走顶层 hintSymbolNodes 注册表
 * （名 → 显示文本 + 动作，颜色／几何仍按名查 keyStyles／
 * keyGeometries）；面板决议见 [resolveLegacyPanel]。
 */
object HintSymbolsResolver {

    /** 面板背景解析结果（两项皆 null = 无面板背景指定）。 */
    data class Backgrounds(
        val background: KeyStyleResolver.ResolvedKeyStyle?,
        val selectedBackground: KeyStyleResolver.ResolvedKeyStyle?,
    )

    private fun resolveStyle(
        name: String?,
        keyStyles: Map<String, Map<KeyStyleRole, String>>,
        scheme: KeyboardColors.ColorScheme,
    ): KeyStyleResolver.ResolvedKeyStyle? {
        if (name == null) return null
        return KeyStyleResolver.resolve(name, null, keyStyles, scheme).style
    }

    fun resolveBackgrounds(
        style: HintSymbolsStyle,
        keyStyles: Map<String, Map<KeyStyleRole, String>>,
        scheme: KeyboardColors.ColorScheme,
    ): Backgrounds = Backgrounds(
        background = resolveStyle(style.backgroundStyle, keyStyles, scheme),
        selectedBackground = resolveStyle(style.selectedBackgroundStyle, keyStyles, scheme),
    )

    /** 网格版面板背景解析（与旧版同名参数同义）。 */
    fun resolveGridBackgrounds(
        style: HintSymbolsGridStyle,
        keyStyles: Map<String, Map<KeyStyleRole, String>>,
        scheme: KeyboardColors.ColorScheme,
    ): Backgrounds = Backgrounds(
        background = resolveStyle(style.backgroundStyle, keyStyles, scheme),
        selectedBackground = resolveStyle(style.selectedBackgroundStyle, keyStyles, scheme),
    )

    /**
     * 长按面板（旧版单行）决议：面板挂载期按键取用。
     *
     * - 旧版样式名为空、样式表无此名、节点全部悬空时返回 null
     *  （无面板，键保持原长按行为）；
     * - 悬空样式名（节点表无此名）按项跳过，动作与标签两列
     *   始终平行；初始选中按解析期 selectedIndex 再夹取到
     *   过滤后范围。
     * 网格优先不在此判定：调用方先试网格决议，网格无效
     * （无名／无样式／全空）时才回落本函数（对端双兼容口径）。
     * 动作转换由调用方传入（生产走覆写装配器的动作映射），
     * 本函数保持纯函数、可单测。
     */
    data class LegacyPanel(
        val style: HintSymbolsStyle,
        val styleNames: List<String>,
        val actions: List<KeyboardAction>,
        val labels: List<String>,
        val initialIndex: Int,
    )

    fun resolveLegacyPanel(
        legacyStyleName: String?,
        legacyStyles: Map<String, HintSymbolsStyle>,
        nodes: Map<String, HintSymbolNode>,
        toRuntime: (SkinAction) -> KeyboardAction,
    ): LegacyPanel? {
        if (legacyStyleName.isNullOrBlank()) return null
        val style = legacyStyles[legacyStyleName] ?: return null
        val resolved = style.symbolStyles.mapNotNull { name ->
            nodes[name]?.let { name to it }
        }
        if (resolved.isEmpty()) return null
        return LegacyPanel(
            style = style,
            styleNames = resolved.map { it.first },
            actions = resolved.map { toRuntime(it.second.action) },
            labels = resolved.map { it.second.displayText },
            initialIndex = style.selectedIndex.coerceIn(0, resolved.lastIndex),
        )
    }

    /**
     * 长按网格面板决议：网格样式名为空、样式表无此名、
     * 全部格为空时返回 null（调用方回落旧版单行）。
     * 空格（`~`／短行缺口）为 null 格（占位、不可选中）；
     * 悬空样式名（节点表无此名）同样为空格；其余格带动作、
     * 显示文本与按名解析的前景／面色（无值时为 null，视图
     * 走默认色）。
     */
    data class GridCell(
        val action: KeyboardAction,
        val label: String,
        val textColor: Int?,
        val faceColor: Int?,
    )

    data class GridPanel(
        val style: HintSymbolsGridStyle,
        val rows: List<List<GridCell?>>,
    )

    fun resolveGridPanel(
        gridStyleName: String?,
        gridStyles: Map<String, HintSymbolsGridStyle>,
        nodes: Map<String, HintSymbolNode>,
        keyStyles: Map<String, Map<KeyStyleRole, String>>,
        scheme: KeyboardColors.ColorScheme,
        toRuntime: (SkinAction) -> KeyboardAction,
    ): GridPanel? {
        if (gridStyleName.isNullOrBlank()) return null
        val style = gridStyles[gridStyleName] ?: return null
        val rows = style.symbolRows.map { row ->
            row.map { name ->
                val node = name?.let { nodes[it] } ?: return@map null
                val resolved = KeyStyleResolver.resolve(name, null, keyStyles, scheme).style
                GridCell(
                    action = toRuntime(node.action),
                    label = node.displayText,
                    textColor = resolved?.text,
                    faceColor = resolved?.face,
                )
            }
        }
        if (rows.all { row -> row.all { it == null } }) return null
        return GridPanel(style, rows)
    }

    /**
     * 面板逐格前景色：每格按同名 keyStyles 条目取 text 角色色；
     * 无条目／无 text 值时对应项为 null（面板用默认键文字色）。
     */
    fun resolveItemTextColors(
        styleNames: List<String>,
        keyStyles: Map<String, Map<KeyStyleRole, String>>,
        scheme: KeyboardColors.ColorScheme,
    ): List<Int?> = styleNames.map { name ->
        KeyStyleResolver.resolve(name, null, keyStyles, scheme).style?.text
    }
}
