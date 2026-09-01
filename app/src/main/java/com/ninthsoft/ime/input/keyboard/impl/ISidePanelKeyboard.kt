package com.ninthsoft.ime.input.keyboard.impl

import com.ninthsoft.ime.engine.data.CandidatePinYin

interface ISidePanelKeyboard : IKeyboard {
    fun onPossibleCandidatePinYin(data: List<CandidatePinYin>)
}
