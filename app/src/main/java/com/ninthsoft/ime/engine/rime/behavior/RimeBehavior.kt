package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.rime.core.IRimeJob

interface RimeBehavior {
    var job: IRimeJob?

    fun withRimeJob(rimeJob: IRimeJob) {
        job = rimeJob
    }

    class Impl : RimeBehavior {
        override var job: IRimeJob? = null
    }
}