package com.ninthsoft.ime.engine

import com.ninthsoft.ime.engine.behavior.IBehavior

interface IBehaviorHost {
    fun flowed(behavior: IBehavior): Boolean

    fun resetState()
}