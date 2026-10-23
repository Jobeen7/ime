package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyIds
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
import com.jobeen.ime.input.keyboard.key.sidePannelKey

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

        // 行宽账（行内和＋跨行键宽须恰为 1.0）：右列与 T9 对齐为
        // 0.18，数字列随之由 0.23333 收为 0.22333（与 T9 字母列
        // 同宽）；底行回车涨到 0.18 后，从宽键「0」扣回 0.03
        // （0.23333→0.20333），其余底行键不变
        val Layout: List<List<KeyDef>> = listOf(
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
                returnKey(percentWidth = 0.18f, id = KeyIds.Number.NUM_ENTER),
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