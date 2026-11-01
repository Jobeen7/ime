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
 * 悬浮面板（工具类型 floatPanel 的无绑定回退视图）：
 * 对端面板的钮多为对端应用内 URL（设置／文件／脚本／性能等），
 * 无法移植；本板只取可移植子集——键盘设置、方案切换、符号板、
 * 表情板四钮（状态面板 #RimeSwitcher 在 Jime 无对应物，不列）。
 * 皮肤为 floatPanel 绑定了自定义布局时不走本板。
 */
@SuppressLint("ViewConstructor")
class FloatPanelKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(context),
) : BaseKeyboard(context, colors, layout) {

    override fun name(): String = NAME

    companion object {
        const val NAME = "floatPanel"

        /** 生效布局：与 LayoutSwitchKeyboard 同口径（覆写装配）。 */
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

        /** 出厂行（纯函数）：单行四钮，行宽和恰为 1.0。 */
        fun buildLayout(): List<List<KeyDef>> = listOf(
            listOf(
                actionButton("设置", KeyboardAction.SettingsAction, 0.25f),
                actionButton("方案", KeyboardAction.RotateSchema, 0.25f),
                actionButton(
                    "符号", KeyboardAction.LayoutSwitchAction(SymbolKeyboard.NAME), 0.25f,
                ),
                actionButton(
                    "表情", KeyboardAction.LayoutSwitchAction(EmojiKeyboard.NAME), 0.25f,
                ),
            ),
        )

        private fun actionButton(
            displayText: String,
            action: KeyboardAction,
            percentWidth: Float,
        ): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = displayText,
                textSize = 18f,
                percentWidth = percentWidth,
                variant = Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(action)),
        )
    }
}
