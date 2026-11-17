// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** AI 服务商（WO52 9.1）。 */
@Dao
interface AiProviderDao {
    @Query("SELECT * FROM AIProvider ORDER BY sort ASC")
    suspend fun getAll(): List<AIProvider>

    @Query("SELECT * FROM AIProvider WHERE id = :id")
    suspend fun getById(id: String): AIProvider?

    @Query("SELECT * FROM AIProvider WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): AIProvider?

    @Insert
    suspend fun insert(provider: AIProvider)

    @Insert
    suspend fun insertAll(providers: List<AIProvider>)

    @Update
    suspend fun update(provider: AIProvider): Int

    /**
     * 旧明文 token 一次性转密文的 CAS 回写（2.4）：仅当落盘值仍是读到
     * 的旧明文时才替换，防并发保存的新 token 被旧密文覆盖。返回受影响
     * 行数（0＝已被他人改写，本次不写）。
     */
    @Query("UPDATE AIProvider SET apiToken = :encrypted WHERE id = :id AND apiToken = :expectedLegacy")
    suspend fun updateTokenIfUnchanged(id: String, expectedLegacy: String, encrypted: String): Int

    @Query("DELETE FROM AIProvider WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM AIProvider")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM AIProvider")
    suspend fun count(): Int
}

/** 服务商模型（WO52 9.1）。 */
@Dao
interface AiProviderModelDao {
    @Query("SELECT * FROM AIProviderModel ORDER BY sort ASC")
    suspend fun getAll(): List<AIProviderModel>

    @Query("SELECT * FROM AIProviderModel WHERE providerId = :providerId ORDER BY sort ASC")
    suspend fun getByProviderId(providerId: String): List<AIProviderModel>

    @Query("SELECT * FROM AIProviderModel WHERE id = :id")
    suspend fun getById(id: String): AIProviderModel?

    @Insert
    suspend fun insert(model: AIProviderModel)

    @Insert
    suspend fun insertAll(models: List<AIProviderModel>)

    @Update
    suspend fun update(model: AIProviderModel): Int

    @Query("DELETE FROM AIProviderModel WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM AIProviderModel WHERE providerId = :providerId")
    suspend fun deleteByProviderId(providerId: String): Int

    @Query("DELETE FROM AIProviderModel")
    suspend fun deleteAll(): Int
}

/** AI 系统提示词（WO52 9.1）。 */
@Dao
interface AiSystemPromptDao {
    @Query("SELECT * FROM AISystemPrompt ORDER BY sort ASC")
    suspend fun getAll(): List<AISystemPrompt>

    @Query("SELECT * FROM AISystemPrompt WHERE id = :id")
    suspend fun getById(id: String): AISystemPrompt?

    @Query("SELECT * FROM AISystemPrompt WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): AISystemPrompt?

    @Insert
    suspend fun insert(prompt: AISystemPrompt)

    @Insert
    suspend fun insertAll(prompts: List<AISystemPrompt>)

    @Update
    suspend fun update(prompt: AISystemPrompt): Int

    @Query("DELETE FROM AISystemPrompt WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM AISystemPrompt")
    suspend fun deleteAll(): Int
}

/** AI 用户提示词（WO52 9.1）。 */
@Dao
interface AiUserPromptDao {
    @Query("SELECT * FROM AIUserPrompt ORDER BY sort ASC")
    suspend fun getAll(): List<AIUserPrompt>

    @Query("SELECT * FROM AIUserPrompt WHERE id = :id")
    suspend fun getById(id: String): AIUserPrompt?

    @Query("SELECT * FROM AIUserPrompt WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): AIUserPrompt?

    @Insert
    suspend fun insert(prompt: AIUserPrompt)

    @Insert
    suspend fun insertAll(prompts: List<AIUserPrompt>)

    @Update
    suspend fun update(prompt: AIUserPrompt): Int

    @Query("DELETE FROM AIUserPrompt WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM AIUserPrompt")
    suspend fun deleteAll(): Int
}

/** AI 指令（WO52 9.1）。 */
@Dao
interface AiInstructionDao {
    @Query("SELECT * FROM AIInstruction ORDER BY sort ASC")
    suspend fun getAll(): List<AIInstruction>

    @Query("SELECT * FROM AIInstruction WHERE id = :id")
    suspend fun getById(id: String): AIInstruction?

    @Query("SELECT * FROM AIInstruction WHERE name = :name")
    suspend fun getByName(name: String): AIInstruction?

    @Insert
    suspend fun insert(instruction: AIInstruction)

    @Insert
    suspend fun insertAll(instructions: List<AIInstruction>)

    @Update
    suspend fun update(instruction: AIInstruction): Int

    @Query("DELETE FROM AIInstruction WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM AIInstruction")
    suspend fun deleteAll(): Int
}

/** AI 对话（WO52 9.1）。 */
@Dao
interface AiConversationDao {
    @Query("SELECT * FROM AIConversation ORDER BY updatedAt DESC")
    suspend fun getAll(): List<AIConversation>

    @Query("SELECT * FROM AIConversation WHERE id = :id")
    suspend fun getById(id: String): AIConversation?

    @Insert
    suspend fun insert(conversation: AIConversation)

    @Insert
    suspend fun insertAll(conversations: List<AIConversation>)

    @Update
    suspend fun update(conversation: AIConversation): Int

    @Query("DELETE FROM AIConversation WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM AIConversation")
    suspend fun deleteAll(): Int
}

/** AI 消息（WO52 9.1）。 */
@Dao
interface AiMessageDao {
    @Query("SELECT * FROM AIMessage ORDER BY conversationId ASC, sequence ASC")
    suspend fun getAll(): List<AIMessage>

    @Query("SELECT * FROM AIMessage WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun getByConversationId(conversationId: String): List<AIMessage>

    @Query("SELECT MAX(sequence) FROM AIMessage WHERE conversationId = :conversationId")
    suspend fun maxSequence(conversationId: String): Int?

    @Insert
    suspend fun insert(message: AIMessage)

    @Insert
    suspend fun insertAll(messages: List<AIMessage>)

    @Update
    suspend fun update(message: AIMessage): Int

    @Query("DELETE FROM AIMessage WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int

    @Query("DELETE FROM AIMessage")
    suspend fun deleteAll(): Int
}
