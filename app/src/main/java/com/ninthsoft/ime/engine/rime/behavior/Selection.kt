package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.Selection


class Selection(override val index: Int) : Selection(index), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { selectCandidate(index, true) }
    }
}