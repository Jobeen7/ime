package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.clearKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.peroidKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey
import com.ninthsoft.ime.input.keyboard.key.zeroKey

@SuppressLint("ViewConstructor")
class T15Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout), ISidePanelKeyboard {
    private val fullWidthPunctuations = listOf("，", "。", "！", "？", "：", "~", "...")
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

        const val percentWidth = 0.13998f
        const val npercentWidth = 0.15f

        fun mixedAlphabetKey(
            digit: String,
            send: String,
            letters: String,
            percentWidth: Float = 0.23333f,
            mainTextTranslationY: Int = 0,
            altTextTranslationY: Int = 4
        ) = KeyDef(
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

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4, percentWidth = npercentWidth),
                mixedAlphabetKey("1", "q", "b", percentWidth = percentWidth),
                mixedAlphabetKey("2", "w", "p", percentWidth = percentWidth),
                mixedAlphabetKey("3", "e", "m", percentWidth = percentWidth),
                mixedAlphabetKey("4", "r", "rf", percentWidth = percentWidth),
                mixedAlphabetKey("5", "t", "ẑz", percentWidth = percentWidth),
                backspaceKey(percentWidth = npercentWidth),
            ),
            listOf(
                mixedAlphabetKey("6", "a", "d", percentWidth = percentWidth),
                mixedAlphabetKey("7", "s", "t", percentWidth = percentWidth),
                mixedAlphabetKey("8", "d", "n", percentWidth = percentWidth),
                mixedAlphabetKey("9", "f", "l", percentWidth = percentWidth),
                mixedAlphabetKey("0", "g", "ĉc", percentWidth = percentWidth),
                clearKey(percentWidth = npercentWidth),
            ),
            listOf(
                mixedAlphabetKey(
                    "&", "z", "gj", percentWidth = percentWidth, altTextTranslationY = 0
                ), mixedAlphabetKey(
                    "*", "x", "kq", percentWidth = percentWidth, altTextTranslationY = 4
                ), mixedAlphabetKey(
                    "^", "c", "hx", percentWidth = percentWidth, altTextTranslationY = 6
                ), mixedAlphabetKey(
                    "#", "v", "yw", percentWidth = percentWidth, altTextTranslationY = 2
                ), mixedAlphabetKey(
                    ";", "b", "ŝs", percentWidth = percentWidth, altTextTranslationY = 2
                ), zeroKey(percentWidth = npercentWidth)
            ),
            listOf(
                layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = npercentWidth),
                schemaSwitchKey(0.13f),
                spaceKey(percentWidth = 0.44f),
                peroidKey(percentWidth = 0.13f),
                returnKey(percentWidth = npercentWidth),
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
