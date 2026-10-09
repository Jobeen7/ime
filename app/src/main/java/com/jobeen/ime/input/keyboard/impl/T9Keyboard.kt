package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
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
                segmentKey(percentWidth = 0.23333f),
                mixedAlphabetKey("2", "ABC"),
                mixedAlphabetKey("3", "DEF"),
                backspaceKey(),
            ),
            listOf(
                mixedAlphabetKey("4", "GHI"),
                mixedAlphabetKey("5", "JKL"),
                mixedAlphabetKey("6", "MNO"),
                newlineKey(0.15f),
            ),
            listOf(
                mixedAlphabetKey("7", "PQRS"),
                mixedAlphabetKey("8", "TUV"),
                mixedAlphabetKey("9", "WXYZ"),
                returnKey(percentWidth = 0.15f, rowSpan = 2),
            ),
            listOf(
                layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = 0.15f),
                peroidKey(percentWidth = 0.13f),
                spaceKey(percentWidth = 0.44f),
                schemaSwitchKey(0.13f),
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
