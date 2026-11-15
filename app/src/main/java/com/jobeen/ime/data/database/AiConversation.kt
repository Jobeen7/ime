// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 对话（WO52 9.1）：
 * 索引 `index_AIConversation_updatedAt`。
 * `modelID` 是 API 模型字符串（如 `deepseek-v4-flash`），与
 * `AIProviderModel.id`（UUID）不是同一字段。
 */
@Entity(
    tableName = "AIConversation",
    indices = [Index("updatedAt")],
)
data class AIConversation(
    @PrimaryKey
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isTitleUserEdited: Boolean = false,
    val systemPromptID: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
    val snapshotJSON: String? = null,
)

/**
 * AI 消息（WO52 9.1）：
 * `conversationId` 外键 → `AIConversation.id`（ON DELETE CASCADE）；
 * 索引 `index_AIMessage_conversationId`、`index_AIMessage_sequence`、
 * `index_AIMessage_status`。
 *
 * 注意：原表**没有** `updatedAt` 列（单子里的 `updatedAt?` 即此意），
 * 不要补——补了 Room schema 与原表对不上。
 * `role`：`user`／`assistant`／`system`；`status`：`streaming`／
 * `complete`／`error`（Jime 侧约定，见 AiChatClient）。
 */
@Entity(
    tableName = "AIMessage",
    indices = [Index("conversationId"), Index("sequence"), Index("status")],
    foreignKeys = [
        ForeignKey(
            entity = AIConversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AIMessage(
    @PrimaryKey
    val id: String,
    val conversationId: String?,
    val role: String,
    val status: String,
    val sequence: Int,
    val createdAt: Long,
    val originalInput: String? = null,
    val renderedRequest: String? = null,
    val userPromptName: String? = null,
    val content: String,
    val tokenEstimate: Int = 0,
    val usageJSON: String? = null,
    val errorCategory: String? = null,
    val requestID: String? = null,
    val regenerationOfID: String? = null,
    val isSuperseded: Boolean = false,
    val configSnapshotJSON: String? = null,
)
