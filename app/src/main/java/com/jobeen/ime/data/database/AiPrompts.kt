// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 系统提示词（WO52 9.1）：
 * `name` 唯一索引 `index_AISystemPrompt_name`。
 * `generationOverridesJSON` 为空＝不覆盖生成参数。
 */
@Entity(
    tableName = "AISystemPrompt",
    indices = [Index(value = ["name"], unique = true)],
)
data class AISystemPrompt(
    @PrimaryKey
    val id: String,
    val name: String,
    val iconName: String,
    val purpose: String,
    val body: String,
    val generationOverridesJSON: String? = null,
    val sort: Int = 0,
    val isDefault: Boolean = false,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val builtInCanBeDelete: Boolean = true,
)

/**
 * AI 用户提示词（WO52 9.1）：
 * `name` 唯一索引 `index_AIUserPrompt_name`。
 * `templateBody` 默认 `'{{input}}'`（建表 DEFAULT 值，Room
 * @ColumnInfo defaultValue 声明）。
 */
@Entity(
    tableName = "AIUserPrompt",
    indices = [Index(value = ["name"], unique = true)],
)
data class AIUserPrompt(
    @PrimaryKey
    val id: String,
    val name: String,
    val iconName: String,
    val purpose: String,
    @ColumnInfo(defaultValue = "'{{input}}'")
    val templateBody: String = "{{input}}",
    val sort: Int = 0,
    val isDefault: Boolean = false,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val builtInCanBeDelete: Boolean = true,
)
