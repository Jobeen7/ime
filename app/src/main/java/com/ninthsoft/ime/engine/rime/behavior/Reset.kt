package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.Reset

class Reset() : Reset(), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { clearComposition() }
    }
}