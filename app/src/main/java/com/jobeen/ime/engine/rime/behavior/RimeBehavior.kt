package com.jobeen.ime.engine.rime.behavior

import com.jobeen.ime.engine.rime.core.IRimeJob

interface RimeBehavior {
    var job: IRimeJob?

    fun withRimeJob(rimeJob: IRimeJob) {
        job = rimeJob
    }

    class Impl : RimeBehavior {
        override var job: IRimeJob? = null
    }
}