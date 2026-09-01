package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.util.PunctuationUtil
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.input.keyboard.key.AltTextKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import com.ninthsoft.ime.input.keyboard.key.alphabetKey
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.capsLockKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.peroidKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class QwertyKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, buildLayout()) {

    enum class CapsState { None, Once, Lock }

    var punctuationState: PunctuationMode = PunctuationMode.FullWidth

    companion object {
        const val NAME = "Qwerty"

        fun buildLayout(): List<List<KeyDef>> {
            return listOf(
                listOf(
                    alphabetKey("q", "1"),
                    alphabetKey("w", "2"),
                    alphabetKey("e", "3"),
                    alphabetKey("r", "4"),
                    alphabetKey("t", "5"),
                    alphabetKey("y", "6"),
                    alphabetKey("u", "7"),
                    alphabetKey("i", "8"),
                    alphabetKey("o", "9"),
                    alphabetKey("p", "0"),
                ),
                listOf(
                    alphabetKey("a", "~"),
                    alphabetKey("s", "!"),
                    alphabetKey("d", "@"),
                    alphabetKey("f", "#"),
                    alphabetKey("g", "$", mainTextTranslationY = -3),
                    alphabetKey("h", "%"),
                    alphabetKey("j", "^", altTextTranslationY = 6),
                    alphabetKey("k", "&"),
                    alphabetKey("l", "*", altTextTranslationY = 6),
                ),
                listOf(
                    capsLockKey(),
                    alphabetKey("z", "(", altTextTranslationY = 0),
                    alphabetKey("x", ")", altTextTranslationY = 0),
                    alphabetKey("c", ":"),
                    alphabetKey("v", ";"),
                    alphabetKey("b", ",", altTextTranslationY = 0),
                    alphabetKey("n", "?"),
                    alphabetKey("m", "/"),
                    backspaceKey(),
                ),
                listOf(
                    layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = 0.15f),
                    schemaSwitchKey(0.13f),
                    spaceKey(percentWidth = 0.44f),
                    peroidKey(percentWidth = 0.13f),
                    returnKey(percentWidth = 0.15f),
                ),
            )
        }
    }

    private var capsState = CapsState.None
    private val letterKeyViews = mutableListOf<AltTextKeyView>()
    private var capsKeyView: ImageKeyView? = null

    init {
        this.updatePunctuationMode(punctuationState)
        for (row in keyRows) {
            for (i in 0 until row.childCount) {
                when (val child = row.getChildAt(i)) {
                    is AltTextKeyView -> {
                        val text = child.mainText.text.toString()
                        if (text.length == 1 && text[0].isLetter()) {
                            letterKeyViews.add(child)
                        }
                    }

                    is ImageKeyView -> {
                        if ((child.def as KeyDef.Appearance.Image).src == R.drawable.ic_keyboard_capslock_none) {
                            capsKeyView = child
                        }
                    }
                }
            }
        }
    }

    override fun onAction(action: KeyboardAction) {
        val transformed = when (action) {
            is KeyboardAction.CapsAction -> {
                switchCapsState()
                return
            }

            is KeyboardAction.CommitAction -> {
                KeyboardAction.CommitAction(
                    PunctuationUtil.convert(
                        action.text, punctuationState == PunctuationMode.FullWidth
                    )
                )
            }

            is KeyboardAction.KeySequenceAction -> {
                val transformed =
                    if (capsState != CapsState.None && KeyEvent.SequenceEvent.isLowerAlphabet(action.sequence)) {
                        if (capsState == CapsState.Once) {
                            switchCapsState(CapsState.None)
                        }
                        KeyboardAction.KeySequenceAction(action.sequence.uppercase())
                    } else {
                        action
                    }
                transformed
            }

            else -> action
        }
        super.onAction(transformed)
    }

    override fun onAttach() {
        super.onAttach()
        capsState = CapsState.None
        updateKeyTextForState(capsState)
    }

    override fun name(): String {
        return NAME
    }

    private fun switchCapsState(target: CapsState? = null) {
        capsState = target ?: when (capsState) {
            CapsState.None -> CapsState.Once
            CapsState.Once -> CapsState.Lock
            CapsState.Lock -> CapsState.None
        }
        updateKeyTextForState(capsState)
    }


    private fun updateKeyTextForState(state: CapsState) {
        val uppercase = state != CapsState.None
        for (kv in letterKeyViews) {
            val text = kv.mainText.text.toString()
            kv.updateText(if (uppercase) text.uppercase() else text.lowercase())
        }
        capsKeyView?.img?.setImageResource(
            when (state) {
                CapsState.None -> R.drawable.ic_keyboard_capslock_none
                CapsState.Once -> R.drawable.ic_keyboard_capslock_once
                CapsState.Lock -> R.drawable.ic_keyboard_capslock_lock
            }
        )
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        punctuationState = mode
        super.updatePunctuationMode(mode)
    }
}
