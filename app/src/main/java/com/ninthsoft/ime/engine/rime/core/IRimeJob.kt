package com.ninthsoft.ime.engine.rime.core

interface IRimeJob {
    fun sendJob(block: suspend RimeApi.() -> Unit)
    suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T
}