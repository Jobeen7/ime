package com.jobeen.ime.engine.rime.behavior

import com.jobeen.ime.engine.behavior.Reset

class Reset() : Reset(), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { clearComposition() }
    }
}