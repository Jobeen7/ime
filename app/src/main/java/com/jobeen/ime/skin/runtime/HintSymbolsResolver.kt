// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.HintGridChoice
import com.jobeen.ime.skin.model.HintSymbolNode
import com.jobeen.ime.skin.model.HintSymbolsStyle
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.resolveHintGrid

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

    fun resolveBackgrounds(
        style: HintSymbolsStyle,
        keyStyles: Map<String, Map<KeyStyleRole, String>>,
        scheme: KeyboardColors.ColorScheme,
    ): Backgrounds {
        fun resolve(name: String?): KeyStyleResolver.ResolvedKeyStyle? {
            if (name == null) return null
            return KeyStyleResolver.resolve(name, null, keyStyles, scheme).style
        }
        return Backgrounds(
            background = resolve(style.backgroundStyle),
            selectedBackground = resolve(style.selectedBackgroundStyle),
        )
    }

    /**
     * 长按面板（旧版单行）决议：面板挂载期按键取用。
     *
     * - 网格版样式名非空时返回 null（网格优先，网格面板另行挂载）；
     * - 旧版样式名为空、样式表无此名、节点全部悬空时返回 null
     *  （无面板，键保持原长按行为）；
     * - 悬空样式名（节点表无此名）按项跳过，动作与标签两列
     *   始终平行；初始选中按解析期 selectedIndex 再夹取到
     *   过滤后范围。
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
        gridStyleName: String?,
        legacyStyleName: String?,
        legacyStyles: Map<String, HintSymbolsStyle>,
        nodes: Map<String, HintSymbolNode>,
        toRuntime: (SkinAction) -> KeyboardAction,
    ): LegacyPanel? {
        val styleName =
            (resolveHintGrid(gridStyleName, legacyStyleName) as? HintGridChoice.Legacy)
                ?.styleName ?: return null
        val style = legacyStyles[styleName] ?: return null
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
