package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.InputKey
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import timber.log.Timber

class InputKey(
    override val code: Int, override val modifiers: Int, override val isVirtual: Boolean
) : InputKey(code, modifiers, isVirtual), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        val kcode = KeyMapping.keyCodeToVal(code = code)
        job?.sendJob { processKey(kcode, modifiers.toUInt(), isVirtual) }
    }
}
