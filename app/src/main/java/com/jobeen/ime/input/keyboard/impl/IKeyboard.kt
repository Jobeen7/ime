package com.jobeen.ime.input.keyboard.impl

import android.view.inputmethod.EditorInfo
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.window.IManagedView

interface IKeyboard : IManagedView {
    var keyActionListener: KeyActionListener?
    fun name(): String
    fun updateSpaceKeyText(text: String)
    fun updatePunctuationMode(mode: PunctuationMode)
    fun updateEditorInfo(info: EditorInfo, empty: Boolean, isComposing: Boolean)
    fun setRippleEnabled(enabled: Boolean)
}
