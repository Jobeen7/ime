// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.ai

import android.content.Context
import com.jobeen.ime.data.database.AIConversation
import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.database.AIMessage
import com.jobeen.ime.data.database.AIProvider
import com.jobeen.ime.data.database.AIProviderModel
import com.jobeen.ime.data.database.AISystemPrompt
import com.jobeen.ime.data.database.AIUserPrompt
import com.jobeen.ime.data.database.AiSeeds
import com.jobeen.ime.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AI 指令执行器读侧口径（AiInstructionRunner 只依赖这 7 个读方法，
 * 便于主机单测用假实现；写侧仍走 [AiRepository]）。
 */
interface AiDataSource {
    suspend fun getInstructionById(id: String): AIInstruction?
    suspend fun getSystemPromptById(id: String): AISystemPrompt?
    suspend fun getUserPromptById(id: String): AIUserPrompt?
    suspend fun getDefaultProvider(): AIProvider?
    suspend fun getProviderById(id: String): AIProvider?
    suspend fun getModelById(id: String): AIProviderModel?
    suspend fun getModelsByProviderId(providerId: String): List<AIProviderModel>
}

/**
 * AI 数据仓库（WO52 9.1）：7 张表的 DAO 门面＋种子兜底＋指令重置.
 *
 * 种子三重保障（任一层失败都不影响首启）：① 新库 onCreate 回调播种；
 * ② 升级库 MIGRATION_15_16 播种；③ 首次访问时 [ensureSeeded] 发现
 * providers 为空即补种。补种用 INSERT OR IGNORE，不盖用户改过的数据。
 */
class AiRepository(
    private val db: AppDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AiDataSource {
    private val providers get() = db.aiProviderDao()
    private val models get() = db.aiProviderModelDao()
    private val systemPrompts get() = db.aiSystemPromptDao()
    private val userPrompts get() = db.aiUserPromptDao()
    private val instructions get() = db.aiInstructionDao()
    private val conversations get() = db.aiConversationDao()
    private val messages get() = db.aiMessageDao()

    suspend fun ensureSeeded() {
        if (providers.count() == 0) {
            db.runInTransaction { db.openHelper.writableDatabase.let { AiSeeds.insertAll(it) } }
        }
    }

    /** 首启在后台补种一次（设置页打开时不阻塞等库）。 */
    fun ensureSeededAsync() {
        scope.launch { runCatching { ensureSeeded() } }
    }

    suspend fun getProviders(): List<AIProvider> = providers.getAll()
    override suspend fun getProviderById(id: String): AIProvider? = providers.getById(id)
    override suspend fun getDefaultProvider(): AIProvider? = providers.getDefault()
    suspend fun updateProvider(provider: AIProvider) = providers.update(provider)
    suspend fun insertProvider(provider: AIProvider) = providers.insert(provider)
    suspend fun deleteProvider(id: String) = providers.deleteById(id)

    override suspend fun getModelsByProviderId(providerId: String): List<AIProviderModel> = models.getByProviderId(providerId)
    override suspend fun getModelById(id: String): AIProviderModel? = models.getById(id)
    suspend fun insertModel(model: AIProviderModel) = models.insert(model)
    suspend fun updateModel(model: AIProviderModel) = models.update(model)
    suspend fun deleteModel(id: String) = models.deleteById(id)

    suspend fun getSystemPrompts(): List<AISystemPrompt> = systemPrompts.getAll()
    override suspend fun getSystemPromptById(id: String): AISystemPrompt? = systemPrompts.getById(id)
    suspend fun insertSystemPrompt(prompt: AISystemPrompt) = systemPrompts.insert(prompt)
    suspend fun updateSystemPrompt(prompt: AISystemPrompt) = systemPrompts.update(prompt)
    suspend fun deleteSystemPrompt(id: String) = systemPrompts.deleteById(id)

    suspend fun getUserPrompts(): List<AIUserPrompt> = userPrompts.getAll()
    override suspend fun getUserPromptById(id: String): AIUserPrompt? = userPrompts.getById(id)
    suspend fun insertUserPrompt(prompt: AIUserPrompt) = userPrompts.insert(prompt)
    suspend fun updateUserPrompt(prompt: AIUserPrompt) = userPrompts.update(prompt)
    suspend fun deleteUserPrompt(id: String) = userPrompts.deleteById(id)

    suspend fun getInstructions(): List<AIInstruction> = instructions.getAll()
    override suspend fun getInstructionById(id: String): AIInstruction? = instructions.getById(id)
    suspend fun insertInstruction(instruction: AIInstruction) = instructions.insert(instruction)
    suspend fun updateInstruction(instruction: AIInstruction) = instructions.update(instruction)
    suspend fun deleteInstruction(id: String) = instructions.deleteById(id)

    /**
     * 重置 AI 指令模板（WO52 9.1）：4 条指令改回初始定义——先全删再
     * 插回种子 4 条（INSERT OR IGNORE 语义下“全删再插”等价于重置）。
     * 只动指令表，不动提示词与服务商。
     */
    suspend fun resetInstructionsToSeed() {
        instructions.deleteAll()
        instructions.insertAll(AiSeeds.instructions)
    }

    suspend fun getConversations(limit: Int): List<AIConversation> = conversations.getAll().take(limit)
    suspend fun getConversationById(id: String): AIConversation? = conversations.getById(id)
    suspend fun insertConversation(conversation: AIConversation) = conversations.insert(conversation)
    suspend fun updateConversation(conversation: AIConversation) = conversations.update(conversation)
    suspend fun deleteConversation(id: String) = conversations.deleteById(id)

    suspend fun getMessages(conversationId: String): List<AIMessage> = messages.getByConversationId(conversationId)
    suspend fun insertMessage(message: AIMessage) = messages.insert(message)
    suspend fun updateMessage(message: AIMessage) = messages.update(message)

    companion object {
        @Volatile
        private var instance: AiRepository? = null

        fun get(context: Context): AiRepository =
            instance ?: synchronized(this) {
                instance ?: AiRepository(AppDatabase.getInstance(context)).also { instance = it }
            }
    }
}
