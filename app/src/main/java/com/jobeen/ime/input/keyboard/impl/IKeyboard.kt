package com.jobeen.ime.input.keyboard.impl

import android.view.inputmethod.EditorInfo
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.window.IManagedView

interface IKeyboard : IManagedView {
    var keyActionListener: KeyActionListener?
    fun name(): String
    fun updateSpaceKeyText(text: String)
    /**
     * G1 动态文字运行时跟随：以最新候选首项/组字串刷新动态键主显文本；
     * 无值时各键回落静态文本（键盘层据副本来源与静态回退刷新）。
     */
    fun updateDynamicTexts(values: Map<String, String>)
    fun updatePunctuationMode(mode: PunctuationMode)
    fun updateEditorInfo(info: EditorInfo, empty: Boolean, isComposing: Boolean)
    fun setRippleEnabled(enabled: Boolean)
}
