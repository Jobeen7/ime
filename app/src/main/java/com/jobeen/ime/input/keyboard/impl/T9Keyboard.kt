package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.input.keyboard.key.KeyIds
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
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.segmentKey
import com.jobeen.ime.input.keyboard.key.sidePannelKey
import com.jobeen.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout), ISidePanelKeyboard {
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

        val Layout: List<List<KeyDef>> = listOf(
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
                returnKey(percentWidth = 0.18f, rowSpan = 2, id = KeyIds.T9.T9_ENTER),
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
