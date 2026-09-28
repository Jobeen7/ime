package com.jobeen.ime.engine

import com.jobeen.ime.engine.behavior.IBehavior

interface IBehaviorHost {
    fun flowed(behavior: IBehavior): Boolean

    fun resetState()
}