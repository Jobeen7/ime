// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 服务商（WO52 9.1）。建表 SQL 取自 Room 生成的 schema
 * （`app/schemas/.../AppDatabase/17.json`，与本文件 @Entity 声明一致）：
 * `defaultModelId` 外键 → `AIProviderModel.id`（ON DELETE SET NULL，
 * DEFERRABLE INITIALLY DEFERRED）；索引 `index_AIProvider_defaultModelId`、
 * `index_AIProvider_sort`（Room 按 @Index 默认命名生成）。
 *
 * `type`：OpenAI 兼容类型，种子未给值，Jime 侧默认 `"openai"`。
 * `apiToken`：使用者在服务商行里自填；种子中无此字段、无内置密钥；
 *   token 永不进备份文件（见 AiBackupCodec）。
 * `maxOutputTokens`／`contextWindow` 为 0＝未设定（请求时不发该字段）；
 *   种子只给了 contextWindow 64000，maxOutputTokens 未给。
 */
@Entity(
    tableName = "AIProvider",
    indices = [Index("defaultModelId"), Index("sort")],
    foreignKeys = [
        ForeignKey(
            entity = AIProviderModel::class,
            parentColumns = ["id"],
            childColumns = ["defaultModelId"],
            onDelete = ForeignKey.SET_NULL,
            deferred = true,
        ),
    ],
)
data class AIProvider(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: String = "openai",
    val sort: Int = 0,
    val isDefault: Boolean = false,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val builtInCanBeDelete: Boolean = true,
    val baseURL: String,
    val apiPath: String,
    val apiToken: String = "",
    val requestTimeout: Double = 60.0,
    val maxOutputTokens: Int = 0,
    val contextWindow: Int = 0,
    val temperature: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val customParametersJSON: String? = null,
    val defaultModelId: String? = null,
)

/**
 * 服务商模型（WO52 9.1）：
 * `providerId` 外键 → `AIProvider.id`（ON DELETE CASCADE，
 * DEFERRABLE INITIALLY DEFERRED）；索引 `index_AIProviderModel_providerId`。
 * `modelID` 是调 API 用的模型字符串（如 `deepseek-v4-flash`），
 * 与行 `id`（UUID）是两个字段，不要混淆。
 */
@Entity(
    tableName = "AIProviderModel",
    indices = [Index("providerId")],
    foreignKeys = [
        ForeignKey(
            entity = AIProvider::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
            deferred = true,
        ),
    ],
)
data class AIProviderModel(
    @PrimaryKey
    val id: String,
    val providerId: String?,
    val displayName: String,
    val modelID: String,
    val sort: Int = 0,
    val isEnabled: Boolean = true,
)
