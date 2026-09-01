package com.ninthsoft.ime.input.keyboard.impl

import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.window.IManagedView

interface IKeyboard : IManagedView {
    var keyActionListener: KeyActionListener?
    fun name(): String
    fun updateSpaceKeyText(text: String)
    fun updatePunctuationMode(mode: PunctuationMode)
    fun updateEditorInfo(info: EditorInfo, empty: Boolean)
    fun setRippleEnabled(enabled: Boolean)
}
