package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.atKey
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.clearKey
import com.ninthsoft.ime.input.keyboard.key.commitKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.symbolSwitchKey
import com.ninthsoft.ime.input.keyboard.key.miniSpaceKey
import com.ninthsoft.ime.input.keyboard.key.resumeLayoutKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout), ISidePanelKeyboard {

    init {
        this.onPossibleCandidatePinYin(emptyList())
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    companion object {
        const val NAME = "Number"

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4),
                commitKey("1", fontSize = 22f),
                commitKey("2", fontSize = 22f),
                commitKey("3", fontSize = 22f),
                backspaceKey(),
            ),
            listOf(
                commitKey("4", fontSize = 22f),
                commitKey("5", fontSize = 22f),
                commitKey("6", fontSize = 22f),
                clearKey(0.15f)
            ),
            listOf(
                commitKey("7", fontSize = 22f),
                commitKey("8", fontSize = 22f),
                commitKey("9", fontSize = 22f),
                miniSpaceKey()
            ),
            listOf(
                resumeLayoutKey("返回", percentWidth = 0.15f),
                symbolSwitchKey(0.13f),
                commitKey(",", variant = Variant.Alternative, percentWidth = 0.10333f),
                commitKey("0", percentWidth = 0.23333f, variant = Variant.Alternative),
                commitKey(".", percentWidth = 0.13f, variant = Variant.Alternative),
                atKey(percentWidth = 0.10333f),
                returnKey(percentWidth = 0.15f),
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