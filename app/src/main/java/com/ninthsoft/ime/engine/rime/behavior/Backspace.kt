package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.Backspace
import com.ninthsoft.ime.engine.rime.core.KeyMapping

class Backspace : Backspace(), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { processKey(KeyMapping.Key_BackSpace, 0U, true) }
    }
}
