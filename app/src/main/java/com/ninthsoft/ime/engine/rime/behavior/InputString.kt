package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.InputString

class InputString(override val sequence: String) : InputString(sequence), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { simulateKeySequence(sequence) }
    }
}
