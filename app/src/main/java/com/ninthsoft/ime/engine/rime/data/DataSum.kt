package com.ninthsoft.ime.engine.rime.data

import kotlinx.serialization.Serializable

@Serializable
data class DataSum(
    val sha256: String,
    val files: Map<String, String>,
)
