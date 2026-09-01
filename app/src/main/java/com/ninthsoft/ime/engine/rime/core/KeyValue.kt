// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

import android.view.KeyCharacterMap
import android.view.KeyEvent

@JvmInline
value class KeyValue(val value: Int) {
    val keyCode: Int
        get() = KeyMapping.valToKeyCode(value)

    override fun toString(): String = "0x${value.toString(16).padStart(4, '0')}"

    companion object {
        fun fromKeyEvent(event: KeyEvent): KeyValue {
            val charCode = event.unicodeChar
            if (charCode != 0 &&
                charCode != '\t'.code &&
                charCode != '\n'.code &&
                charCode != KeyCharacterMap.HEX_INPUT.code &&
                charCode != KeyCharacterMap.PICKER_DIALOG_INPUT.code
            ) {
                return KeyValue(charCode)
            }
            return KeyValue(KeyMapping.keyCodeToVal(event.keyCode))
        }
    }
}
