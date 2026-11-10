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
 * 快捷面板（W19-6，源包 panel 板的 Jime 等价物）：
 * 2×4 网格，8 钮——设置／按键／震动／更新／皮肤／微调／同步／内嵌。
 * 入口为工具栏 MENU 槽位（PanelAction.ShortcutPanel → 本板）；
 * 点任一钮先做该钮的事、再回主键盘（CombineAction + ResumeAction），
 * 面板本身不常驻。源包的对端应用内 URL（设置／文件／商店／同步）
 * 一律不移植，各钮只接 Jime 已有等价操作；「按键」「微调」两钮
 * 经 SkinEditorEntry 进内置编辑器（W19-7 实现，未落成前只提示）。
 */
@SuppressLint("ViewConstructor")
class PanelKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(context),
) : BaseKeyboard(context, colors, layout) {

    override fun name(): String = NAME

    companion object {
        const val NAME = "panel"

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

        /** 出厂行（纯函数）：2 行 × 4 钮，行宽和恰为 1.0。 */
        fun buildLayout(): List<List<KeyDef>> = listOf(
            listOf(
                actionButton("设置", KeyboardAction.SettingsAction, 0.25f),
                actionButton(
                    "按键",
                    KeyboardAction.PanelShortcutAction(
                        KeyboardAction.PanelShortcutTarget.EDIT_LAYOUTS,
                    ),
                    0.25f,
                ),
                actionButton(
                    "震动",
                    KeyboardAction.PanelShortcutAction(
                        KeyboardAction.PanelShortcutTarget.VIBRATION_SETTINGS,
                    ),
                    0.25f,
                ),
                actionButton(
                    "更新",
                    KeyboardAction.PanelShortcutAction(
                        KeyboardAction.PanelShortcutTarget.CHECK_UPDATE,
                    ),
                    0.25f,
                ),
            ),
            listOf(
                actionButton(
                    "皮肤",
                    KeyboardAction.PanelShortcutAction(
                        KeyboardAction.PanelShortcutTarget.SKIN_LIBRARY,
                    ),
                    0.25f,
                ),
                actionButton(
                    "微调",
                    KeyboardAction.PanelShortcutAction(
                        KeyboardAction.PanelShortcutTarget.EDIT_SKIN_JSON,
                    ),
                    0.25f,
                ),
                actionButton(
                    "同步",
                    KeyboardAction.PanelShortcutAction(
                        KeyboardAction.PanelShortcutTarget.SYNC_DICTS,
                    ),
                    0.25f,
                ),
                actionButton(
                    "内嵌",
                    KeyboardAction.ToggleEmbeddedInputAction,
                    0.25f,
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
            // 点钮先做事、再回主键盘（窗口层展开 CombineAction，
            // 各子动作照常过 transformed；ResumeAction 由窗口接应）。
            behaviors = setOf(
                KeyDef.Behavior.Press(
                    KeyboardAction.CombineAction(
                        listOf(action, KeyboardAction.ResumeAction),
                    ),
                ),
            ),
        )
    }
}
