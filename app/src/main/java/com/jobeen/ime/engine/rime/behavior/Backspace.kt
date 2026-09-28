package com.jobeen.ime.engine.rime.behavior

import com.jobeen.ime.engine.behavior.Backspace
import com.jobeen.ime.engine.rime.core.KeyMapping

class Backspace : Backspace(), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { processKey(KeyMapping.Key_BackSpace, 0U, true) }
    }
}
