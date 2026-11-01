// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.HintSymbolsStyle
import com.jobeen.ime.skin.model.KeyStyleRole

/**
 * 长按符号样式背景解析（纯函数）：面板背景与选中背景两个
 * 样式名按现有单键样式表解析为角色色（与覆写装配器同口径）；
 * 未知名/无值时对应项为 null（面板期回落默认背景）。
 *
 * per-symbol 样式名（symbolStyles）本身的完整节点解析
 * （前景样式 + 动作）不在此：本模型无具名完整样式节点
 * 注册表，符号动作的执行归未来长按面板挂载时解析；
 * 本解析只覆盖面板级两个背景样式（已有注册表可解）。
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
}
