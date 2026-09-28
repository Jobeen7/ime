package com.jobeen.ime.input.panel

import android.view.View
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage

interface IPanel {
    val view: View
    var recording: Boolean
    fun setCandidates(list: List<EngineMessage.Candidate>)
    fun refreshTheme()
    fun onFinishInputView(finishingInput: Boolean)
    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>)
    fun exitAddPhraseMode()
}
