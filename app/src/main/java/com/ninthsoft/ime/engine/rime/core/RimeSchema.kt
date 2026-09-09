// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

import com.ninthsoft.ime.engine.rime.util.OptionsApplier

class RimeSchema(val schemaId: String) {

    suspend fun applyOptions(api: RimeApi): RimeSchema {
        OptionsApplier.apply(api, this@RimeSchema)
        return this@RimeSchema
    }

    data class Switch(
        val name: String = "",
        val options: List<String> = emptyList(),
        val reset: Int = -1,
        val states: List<String> = emptyList(),
    )

    data class Option(
        val name: String = "",
        val key: String = "",
        val keys: List<String> = emptyList(),
        val lock: Boolean = false,
        val value: Boolean = false,
    )

    val switches: List<Switch>
    val options: List<Option>
    val alphabet: String
    var kind: String
    var candidateKind: String

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
            options = it.getList("options") { path ->
                Option(
                    name = getString("$path/name") ?: "",
                    key = getString("$path/key") ?: "",
                    keys = getList("$path/keys", RimeConfig::getString).filterNotNull(),
                    lock = getBool("$path/lock") ?: false,
                    value = getBool("$path/value") ?: false,
                )
            }
            alphabet = it.getString("speller/alphabet") ?: ""
            kind = it.getString("schema/kind") ?: ""
            candidateKind = it.getString("schema/candidateKind") ?: ""
        }
    }
}
