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
import com.jobeen.ime.skin.layout.BuiltinLayoutApplier
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.PlaceholderValues
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

/**
 * 布局切换板（工具类型 layoutSwitch 的无绑定回退视图）：
 * 对端面板为 6 个拼音布局钮（9/14/17/18/26/27 键）；Jime
 * 的拼音主布局只有三档，故本板只列 9 键／15 键／26 键三钮，
 * 各钮按名切布局（LayoutSwitchAction，与既有布局键同口径）。
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
            val snapshot = SkinSnapshotProvider.current(context) ?: return raw
            val arranged = snapshot.builtinLayouts[NAME]?.let { override ->
                BuiltinLayoutApplier.apply(
                    raw, override,
                    landscape = context.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE,
                    placeholders = PlaceholderValues.current(context),
                )
            } ?: raw
            return KeyOverrideApplier.apply(
                NAME, arranged, snapshot, PlaceholderValues.current(context),
                scheme = snapshot.effectiveColorScheme(SkinColorMode.isDark(context)),
                notifications = com.jobeen.ime.skin.runtime.SkinRuntimeState.latchedNotifications(),
                gatedStyleNames = com.jobeen.ime.skin.runtime.SkinRuntimeState.gatedStyleNames(),
                activeStyleNames = com.jobeen.ime.skin.runtime.SkinRuntimeState.activeStyleNames(),
                mnemonicEnabled = KeyboardManager.Keyboard.Mnemonic.isEnabled(context),
                decorationEnabled = KeyboardManager.Keyboard.Decoration.isEnabled(context),
            )
        }

        /** 出厂行（纯函数）：单行三钮，行宽和恰为 1.0。 */
        fun buildLayout(): List<List<KeyDef>> = listOf(
            listOf(
                layoutButton("9键", T9Keyboard.NAME, 0.34f),
                layoutButton("15键", T15Keyboard.NAME, 0.33f),
                layoutButton("26键", QwertyKeyboard.NAME, 0.33f),
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
