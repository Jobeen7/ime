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
 * 返回路径板（工具类型 returnPath 的无绑定回退视图）：
 * 对端 returnPath 不是独立面板，而是拼音主键盘的变体（中→英键
 * 指向按来源布局区分的英文槽位，以原路返回）；Jime 的原路返回
 * 机制已在类型状态机（last/primary + ReturnLast/ReturnPrimary）
 * ，故本板只提供返回 affordance 本身：返回上一键盘与回主键盘
 * 两钮。皮肤为 returnPath 绑定了自定义布局时不走本板。
 */
@SuppressLint("ViewConstructor")
class ReturnPathKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(context),
) : BaseKeyboard(context, colors, layout) {

    override fun name(): String = NAME

    companion object {
        const val NAME = "returnPath"

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

        /** 出厂行（纯函数）：单行两钮，行宽和恰为 1.0。 */
        fun buildLayout(): List<List<KeyDef>> = listOf(
            listOf(
                actionButton("返回", KeyboardAction.ReturnLastAction, 0.5f),
                actionButton("主键盘", KeyboardAction.ReturnPrimaryAction, 0.5f),
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
