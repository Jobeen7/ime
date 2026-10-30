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
import com.jobeen.ime.input.keyboard.key.newlineKey
import com.jobeen.ime.input.keyboard.key.infiniteKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.mixedAlphabetKey
import com.jobeen.ime.input.keyboard.key.peroidKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.skin.runtime.EnterKeyVariant
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.segmentKey
import com.jobeen.ime.input.keyboard.key.sidePannelKey
import com.jobeen.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class T9Keyboard(
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
            return
        }
        // 与候选网格侧栏共用同一份构建缓存，避免同批数据每键重复全量构建
        super.updateSidePanel(com.jobeen.ime.input.panel.component.PinYinKeyDefCache.get(data))
    }

    companion object {
        const val NAME = "T9"

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

        // 布局按构造时现算（不再是类加载时单例）：回车变体由调用方
        // 经 EnterKeyVariant 传入，静态单例会在主题间串色
        fun buildLayout(returnVariant: Variant): List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4),
                segmentKey(percentWidth = 0.22333f, id = KeyIds.T9.T9_SEGMENT),
                mixedAlphabetKey("2", "ABC", 0.22333f, id = KeyIds.T9.T9_KEY_2),
                mixedAlphabetKey("3", "DEF", 0.22333f, id = KeyIds.T9.T9_KEY_3),
                backspaceKey(0.18f, id = KeyIds.T9.T9_DELETE),
            ),
            listOf(
                mixedAlphabetKey("4", "GHI", 0.22333f, id = KeyIds.T9.T9_KEY_4),
                mixedAlphabetKey("5", "JKL", 0.22333f, id = KeyIds.T9.T9_KEY_5),
                mixedAlphabetKey("6", "MNO", 0.22333f, id = KeyIds.T9.T9_KEY_6),
                newlineKey(0.18f, id = KeyIds.T9.T9_NEWLINE),
            ),
            listOf(
                mixedAlphabetKey("7", "PQRS", 0.22333f, id = KeyIds.T9.T9_KEY_7),
                mixedAlphabetKey("8", "TUV", 0.22333f, id = KeyIds.T9.T9_KEY_8),
                mixedAlphabetKey("9", "WXYZ", 0.22333f, id = KeyIds.T9.T9_KEY_9),
                returnKey(percentWidth = 0.18f, rowSpan = 2, variant = returnVariant, id = KeyIds.T9.T9_ENTER),
            ),
            listOf(
                layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = 0.15f, id = KeyIds.T9.T9_LAYOUT_NUMBER),
                peroidKey(percentWidth = 0.13f, id = KeyIds.T9.T9_PERIOD),
                spaceKey(percentWidth = 0.41f, id = KeyIds.T9.T9_SPACE),
                schemaSwitchKey(0.13f, id = KeyIds.T9.T9_SCHEMA),
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
