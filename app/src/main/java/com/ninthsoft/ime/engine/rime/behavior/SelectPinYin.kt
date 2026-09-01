package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.SelectPinYin
import com.ninthsoft.ime.engine.data.CandidatePinYin

class SelectPinYin(pinYin: CandidatePinYin) : SelectPinYin(pinYin), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
    }
}
