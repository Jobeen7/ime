package com.jobeen.ime.engine.rime.behavior

import com.jobeen.ime.engine.behavior.SelectPinYin
import com.jobeen.ime.engine.data.CandidatePinYin

class SelectPinYin(pinYin: CandidatePinYin) : SelectPinYin(pinYin), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
    }
}
