package com.jobeen.ime.engine.rime.daemon

import com.jobeen.ime.engine.rime.core.RimeApi
import kotlinx.coroutines.CoroutineScope

/**
 * interface to run different operations on RimeApi
 */
interface RimeSession {
    /**
     * Run an operation immediately if rime is at ready state.
     * Otherwise, caller will be suspended until rime is ready and operation is done.
     * The [block] will be executed in caller's thread.
     * Client should use this function in most cases.
     */
    suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T

    val lifecycleScope: CoroutineScope
}
