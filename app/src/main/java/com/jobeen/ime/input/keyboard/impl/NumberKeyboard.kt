package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.layout.BuiltinLayoutApplier
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.PlaceholderValues
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.atKey
import com.jobeen.ime.input.keyboard.key.backspaceKey
import com.jobeen.ime.input.keyboard.key.clearKey
import com.jobeen.ime.input.keyboard.key.commitKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.symbolSwitchKey
import com.jobeen.ime.input.keyboard.key.miniSpaceKey
import com.jobeen.ime.input.keyboard.key.resumeLayoutKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.skin.runtime.EnterKeyVariant
import com.jobeen.ime.input.keyboard.key.sidePannelKey

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(
        context,
        EnterKeyVariant.resolve(context),
    ),
) : BaseKeyboard(
    context,
    colors,
    layout,
), ISidePanelKeyboard {

    init {
        this.onPossibleCandidatePinYin(emptyList())
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    companion object {
        const val NAME = "Number"

        /**
         * 生效布局（详设 §4.2 接缝）：出厂布局经当前皮肤快照的覆写
         * 装配；无快照/无命中时恒等返回出厂布局。编辑器预览传入
         * 显式 layout 时不走此路（不碰全局快照槽，详设 §10.2-7）。
         * 回车变体按 EnterKeyVariant 现算（皮肤生效跟皮肤、无皮肤
         * 跟内置主题），覆写在其结果上做。
         */
        fun effectiveLayout(context: Context, returnVariant: Variant): List<List<KeyDef>> {
            val raw = buildLayout(returnVariant)
            val snapshot = SkinSnapshotProvider.current(context) ?: return raw
            // 内置布局覆盖（2026-10-28）：皮肤含本键盘的内置布局文件时
            // 按文件重排（出厂键本体 + 文件排版/动作槽），再走既有覆写
            // 装配（样式/图片/字号等）；无覆盖/排空时恒用出厂布局。
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
                // 接线轮 R1/R2：构建期取当前锁定通知与条件门控集。
                notifications = com.jobeen.ime.skin.runtime.SkinRuntimeState.latchedNotifications(),
                gatedStyleNames = com.jobeen.ime.skin.runtime.SkinRuntimeState.gatedStyleNames(),
                activeStyleNames = com.jobeen.ime.skin.runtime.SkinRuntimeState.activeStyleNames(),
                mnemonicEnabled = com.jobeen.ime.data.manager.KeyboardManager.Keyboard.Mnemonic.isEnabled(context),
                decorationEnabled = com.jobeen.ime.data.manager.KeyboardManager.Keyboard.Decoration.isEnabled(context),
            )
        }

        // 行宽账（行内和＋跨行键宽须恰为 1.0）：右列与 T9 对齐为
        // 0.18，数字列随之由 0.23333 收为 0.22333（与 T9 字母列
        // 同宽）；底行回车涨到 0.18 后，从宽键「0」扣回 0.03
        // （0.23333→0.20333），其余底行键不变
        // 布局按构造时主题现算（不再是类加载时单例）：回车变体
        // 随主题取，静态单例会在主题间串色——正是本次要修的根因
        fun buildLayout(returnVariant: Variant): List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4),
                commitKey("1", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_1),
                commitKey("2", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_2),
                commitKey("3", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_3),
                backspaceKey(0.18f, id = KeyIds.Number.NUM_DELETE),
            ),
            listOf(
                commitKey("4", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_4),
                commitKey("5", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_5),
                commitKey("6", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_6),
                clearKey(0.18f, id = KeyIds.Number.NUM_CLEAR)
            ),
            listOf(
                commitKey("7", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_7),
                commitKey("8", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_8),
                commitKey("9", percentWidth = 0.22333f, fontSize = 22f, id = KeyIds.Number.NUM_KEY_9),
                miniSpaceKey(0.18f, id = KeyIds.Number.NUM_SPACE)
            ),
            listOf(
                resumeLayoutKey("返回", percentWidth = 0.15f, id = KeyIds.Number.NUM_RESUME),
                symbolSwitchKey(0.13f, id = KeyIds.Number.NUM_SYMBOL),
                commitKey(",", variant = Variant.Alternative, percentWidth = 0.10333f, id = KeyIds.Number.NUM_COMMA),
                commitKey("0", percentWidth = 0.20333f, variant = Variant.Alternative, id = KeyIds.Number.NUM_KEY_0),
                commitKey(".", percentWidth = 0.13f, variant = Variant.Alternative, id = KeyIds.Number.NUM_DOT),
                atKey(percentWidth = 0.10333f, id = KeyIds.Number.NUM_AT),
                returnKey(percentWidth = 0.18f, variant = returnVariant, id = KeyIds.Number.NUM_ENTER),
            ),
        )
    }

    override fun name(): String {
        return NAME
    }

    override fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        super.updateSidePanel(
            listOf("+", "-", "*", "/", "=", "~", "?", "!").map { ch ->
                KeyDef(
                    appearance = KeyDef.Appearance.Text(
                        displayText = ch,
                        textSize = 15f,
                        percentWidth = 0.5f,
                        margin = false,
                        variant = Variant.Alternative
                    ),
                    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(ch))),
                )
            })
    }

    override fun updatePunctuationMode(mode: PunctuationMode)= run { }
}