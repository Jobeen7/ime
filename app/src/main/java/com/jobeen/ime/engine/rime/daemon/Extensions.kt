package com.jobeen.ime.engine.rime.daemon

import com.jobeen.ime.engine.rime.core.RimeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun RimeSession.launchOnReady(block: suspend CoroutineScope.(RimeApi) -> Unit) {
    lifecycleScope.launch {
        runOnReady { block(this) }
    }
}
