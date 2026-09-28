package com.jobeen.ime.engine.rime.behavior

import com.jobeen.ime.engine.behavior.InputKey
import com.jobeen.ime.engine.rime.core.KeyMapping
import timber.log.Timber

class InputKey(
    override val code: Int, override val modifiers: Int, override val isVirtual: Boolean
) : InputKey(code, modifiers, isVirtual), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        val kcode = KeyMapping.keyCodeToVal(code = code)
        job?.sendJob { processKey(kcode, modifiers.toUInt(), isVirtual) }
    }
}
