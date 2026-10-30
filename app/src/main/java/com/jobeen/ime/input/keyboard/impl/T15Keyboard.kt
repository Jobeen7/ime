package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.layout.BuiltinLayoutApplier
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.PlaceholderValues
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.backspaceKey
import com.jobeen.ime.input.keyboard.key.clearKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.peroidKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.skin.runtime.EnterKeyVariant
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.sidePannelKey
import com.jobeen.ime.input.keyboard.key.spaceKey
import com.jobeen.ime.input.keyboard.key.zeroKey

@SuppressLint("ViewConstructor")
class T15Keyboard(
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
    private val fullWidthPunctuations = listOf("，", "。", "？", "！", "：", "~", "...")
    private val halfWidthPunctuations = listOf(",", ".", "!", "?", ":", "~", "...")
    var punctuations = fullWidthPunctuations
    private var state: PunctuationMode = PunctuationMode.FullWidth

    init {
        this.updatePunctuationMode(state)
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    override fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (data.isEmpty()) {
            super.updateSidePanel(
                punctuations.map { ch ->
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
        return
    }


    companion object {
        const val NAME = "T15"

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

        const val percentWidth = 0.13998f
        const val npercentWidth = 0.15f

        fun mixedAlphabetKey(
            digit: String,
            send: String,
            letters: String,
            percentWidth: Float = 0.23333f,
            mainTextTranslationY: Int = 0,
            altTextTranslationY: Int = 4,
            id: String? = null
        ) = KeyDef(
            id = id,
            appearance = KeyDef.Appearance.AltText(
                displayText = letters,
                altText = digit,
                textSize = 20f,
                percentWidth = percentWidth,
                mainTextTranslationY = mainTextTranslationY,
                altTextTranslationY = altTextTranslationY
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(send)),
                KeyDef.Behavior.LongPress(KeyboardAction.CommitAction(digit))
            ),
        )

        // 布局按构造时主题现算（不再是类加载时单例）：回车变体
        // 随主题取，静态单例会在主题间串色——正是本次要修的根因
        fun buildLayout(returnVariant: Variant): List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4, percentWidth = npercentWidth),
                mixedAlphabetKey("1", "q", "b", id = KeyIds.T15.T15_KEY_1, percentWidth = percentWidth),
                mixedAlphabetKey("2", "w", "p", id = KeyIds.T15.T15_KEY_2, percentWidth = percentWidth),
                mixedAlphabetKey("3", "e", "m", id = KeyIds.T15.T15_KEY_3, percentWidth = percentWidth),
                mixedAlphabetKey("4", "r", "rf", id = KeyIds.T15.T15_KEY_4, percentWidth = percentWidth),
                mixedAlphabetKey("5", "t", "ẑz", id = KeyIds.T15.T15_KEY_5, percentWidth = percentWidth),
                backspaceKey(percentWidth = npercentWidth, id = KeyIds.T15.T15_DELETE),
            ),
            listOf(
                mixedAlphabetKey("6", "a", "d", id = KeyIds.T15.T15_KEY_6, percentWidth = percentWidth),
                mixedAlphabetKey("7", "s", "t", id = KeyIds.T15.T15_KEY_7, percentWidth = percentWidth),
                mixedAlphabetKey("8", "d", "n", id = KeyIds.T15.T15_KEY_8, percentWidth = percentWidth),
                mixedAlphabetKey("9", "f", "l", id = KeyIds.T15.T15_KEY_9, percentWidth = percentWidth),
                mixedAlphabetKey("0", "g", "ĉc", id = KeyIds.T15.T15_KEY_10, percentWidth = percentWidth),
                clearKey(percentWidth = npercentWidth, id = KeyIds.T15.T15_CLEAR),
            ),
            listOf(
                mixedAlphabetKey(
                    "&", "z", "gj", id = KeyIds.T15.T15_KEY_11, percentWidth = percentWidth, altTextTranslationY = 0
                ), mixedAlphabetKey(
                    "*", "x", "kq", id = KeyIds.T15.T15_KEY_12, percentWidth = percentWidth, altTextTranslationY = 4
                ), mixedAlphabetKey(
                    "^", "c", "hx", id = KeyIds.T15.T15_KEY_13, percentWidth = percentWidth, altTextTranslationY = 6
                ), mixedAlphabetKey(
                    "#", "v", "yw", id = KeyIds.T15.T15_KEY_14, percentWidth = percentWidth, altTextTranslationY = 2
                ), mixedAlphabetKey(
                    ";", "b", "ŝs", id = KeyIds.T15.T15_KEY_15, percentWidth = percentWidth, altTextTranslationY = 2
                ), zeroKey(percentWidth = npercentWidth, id = KeyIds.T15.T15_ZERO)
            ),
            listOf(
                layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = npercentWidth, id = KeyIds.T15.T15_LAYOUT_NUMBER),
                schemaSwitchKey(0.13f, id = KeyIds.T15.T15_SCHEMA),
                spaceKey(percentWidth = 0.44f, id = KeyIds.T15.T15_SPACE),
                peroidKey(percentWidth = 0.13f, id = KeyIds.T15.T15_PERIOD),
                returnKey(percentWidth = npercentWidth, variant = returnVariant, id = KeyIds.T15.T15_ENTER),
            ),
        )
    }

    override fun name(): String {
        return NAME
    }

    override fun onAttach() {
        this.onPossibleCandidatePinYin(emptyList())
        super.onAttach()
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        val changed = mode != state
        state = mode
        punctuations = when (mode) {
            PunctuationMode.FullWidth -> fullWidthPunctuations
            PunctuationMode.HalfWidth -> halfWidthPunctuations
        }
        if (changed) {
            this.onPossibleCandidatePinYin(emptyList())
        }
        super.updatePunctuationMode(mode)
    }
}
