// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

class RimeSchema(val schemaId: String) {

    data class Switch(
        val name: String = "",
        val options: List<String> = emptyList(),
        val reset: Int = -1,
        val states: List<String> = emptyList(),
    )

    val switches: List<Switch>
    val alphabet: String

    init {
        val config = when {
            schemaId.isEmpty() -> RimeConfig.openConfig("default")
            schemaId.startsWith('.') -> RimeConfig.openSchema(schemaId.substring(1))
            else -> RimeConfig.openSchema(schemaId)
        }
        config.use {
            switches = it.getList("switches") { path ->
                Switch(
                    name = getString("$path/name") ?: "",
                    options = getList("$path/options", RimeConfig::getString).filterNotNull(),
                    reset = getInt("$path/reset") ?: -1,
                    states = getList("$path/states", RimeConfig::getString).filterNotNull(),
                )
            }
            alphabet = it.getString("speller/alphabet") ?: ""
        }
    }
}
