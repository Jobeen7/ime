package com.jobeen.ime.engine.rime.behavior

import com.jobeen.ime.engine.behavior.Selection


class Selection(override val index: Int) : Selection(index), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { selectCandidate(index, true) }
    }
}