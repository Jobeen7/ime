// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

data class SchemaItem(
    val id: String,
    val name: String = "",
    val layout: String = "",
    val punctuation: String = "",
    val kind: String = "",
)
