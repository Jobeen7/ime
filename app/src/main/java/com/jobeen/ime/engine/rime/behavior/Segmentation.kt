package com.jobeen.ime.engine.rime.behavior

import com.jobeen.ime.engine.behavior.Segmentation

class Segmentation : Segmentation(), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {}
}
