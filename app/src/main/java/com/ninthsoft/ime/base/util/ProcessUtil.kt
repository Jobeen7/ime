package com.ninthsoft.ime.base.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

fun subProcess(vararg commands: String): Process = Runtime.getRuntime().exec(commands)

fun Process.asFlow(): Flow<String> = inputStream
    .bufferedReader()
    .lineSequence()
    .asFlow()
    .flowOn(Dispatchers.IO)
    .cancellable()

suspend fun Process.readText() = withContext(Dispatchers.IO) {
    inputStream.bufferedReader().readText()
}

suspend fun Process.readLines() = withContext(Dispatchers.IO) {
    inputStream.bufferedReader().readLines()
}
