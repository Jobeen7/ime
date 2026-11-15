// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 指令（WO52 9.1）：
 * “指令”＝系统提示词＋用户提示词＋服务商的组合；
 * `name` 唯一索引 `index_AIInstruction_name`。
 * 三个引用 id 为空＝不指定（种子 4 条指令均指向已播种的行）。
 */
@Entity(
    tableName = "AIInstruction",
    indices = [Index(value = ["name"], unique = true)],
)
data class AIInstruction(
    @PrimaryKey
    val id: String,
    val name: String,
    val detail: String,
    val systemPromptID: String? = null,
    val userPromptID: String? = null,
    val providerID: String? = null,
    val sort: Int = 0,
)
