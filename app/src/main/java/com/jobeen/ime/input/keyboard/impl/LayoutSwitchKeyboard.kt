// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.runtime.SkinColorMode

/**
 * 布局切换板（工具类型 layoutSwitch 的无绑定回退视图）：
 * 对端面板为 6 个拼音布局钮（9/14/17/18/26/27 键）；Jime
 * 的拼音主布局只有五档，故本板只列 9 键／14 键／15 键／18 键／
 * 26 键五钮，各钮按名切布局（LayoutSwitchAction，与既有布局键同口径）。
 * 皮肤为 layoutSwitch 绑定了自定义布局时不走本板——类型解析
 * 优先返回 custom:<布局 id>，由通用布局键盘渲染。
 */
@SuppressLint("ViewConstructor")
class LayoutSwitchKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(context),
) : BaseKeyboard(context, colors, layout) {

    override fun name(): String = NAME

    companion object {
        const val NAME = "layoutSwitch"

        /**
         * 生效布局：出厂行经当前皮肤快照的覆写装配；无快照/
         * 无命中时恒等返回出厂行（与 NumberKeyboard 同口径）。
         */
        fun effectiveLayout(context: Context): List<List<KeyDef>> {
            val raw = buildLayout()
            return raw
        }

        /** 出厂行（纯函数）：单行五钮，行宽和恰为 1.0。 */
        fun buildLayout(): List<List<KeyDef>> = listOf(
            listOf(
                layoutButton("9键", T9Keyboard.NAME, 0.2f),
                layoutButton("14键", T14Keyboard.NAME, 0.2f),
                layoutButton("15键", T15Keyboard.NAME, 0.2f),
                layoutButton("18键", T18Keyboard.NAME, 0.2f),
                layoutButton("26键", QwertyKeyboard.NAME, 0.2f),
            ),
        )

        private fun layoutButton(
            displayText: String,
            target: String,
            percentWidth: Float,
        ): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = displayText,
                textSize = 18f,
                percentWidth = percentWidth,
                variant = Variant.Alternative,
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyboardAction.LayoutSwitchAction(target)),
            ),
        )
    }
}
