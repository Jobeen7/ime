package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.engine.data.CandidatePinYin

interface ISidePanelKeyboard : IKeyboard {
    fun onPossibleCandidatePinYin(data: List<CandidatePinYin>)
}
