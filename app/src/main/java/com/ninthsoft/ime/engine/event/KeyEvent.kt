package com.ninthsoft.ime.engine.event

import java.util.Locale.getDefault
import android.view.KeyEvent as KEvent


sealed class KeyEvent {

    data class SequenceEvent(val sequence: String) : KeyEvent() {
        companion object {
            fun isLowerAlphabet(s: String): Boolean {
                return s.length == 1 && s[0] in 'a'..'z'
            }
        }
    }

    data class CodeEvent(
        val keyCode: Int, val modifiers: KeyModifiers, val isVirtual: Boolean = true
    ) : KeyEvent() {
        companion object {
            fun keyCode(character: String): Int {
                val code = KEvent.keyCodeFromString("KEYCODE_${character.uppercase(getDefault())}")
                if (code == KEvent.KEYCODE_UNKNOWN) {
                    throw Exception("keyEvent return unknown for character: $character")
                }
                return code
            }

            fun isAlphabet(code: Int): Boolean {
                return code in KEvent.KEYCODE_A..KEvent.KEYCODE_Z
            }
        }
    }
}