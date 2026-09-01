package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.Segmentation

class Segmentation : Segmentation(), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {}
}
