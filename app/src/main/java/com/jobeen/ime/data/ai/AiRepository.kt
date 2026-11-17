// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.ai

import android.content.Context
import com.jobeen.ime.base.util.SecretStore
import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.database.AIProvider
import com.jobeen.ime.data.database.AIProviderModel
import com.jobeen.ime.data.database.AISystemPrompt
import com.jobeen.ime.data.database.AIUserPrompt
import com.jobeen.ime.data.database.AiSeeds
import com.jobeen.ime.data.database.AppDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

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
 * 服务商 token 的落盘形态（2.4）：`AIProvider.apiToken` 存密文
 * （见 [AiTokenCodec]），本仓库是明文/密文的唯一换算边界——读侧解密
 * （旧明文直通可读）＋首次读到旧明文时加密回写，写侧一律加密；
 * Keystore 不可用时不写明文、不丢旧配置。`BackupManager` 走 DAO
 * 直读直写（落盘形态原样保留），不经本仓库，故不受换算影响。
 *
 * 种子三重保障（任一层失败都不影响首启）：① 新库 onCreate 回调播种；
 * ② 升级库 MIGRATION_15_16 播种；③ 首次访问时 [ensureSeeded] 仅首次
 * 补种（prefs 记 seeded，删光后不再自动恢复，见 C-L-29）。补种用
 * INSERT OR IGNORE，不盖用户改过的数据。
 */
class AiRepository(
    private val db: AppDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 仅首次补种的 seeded 标记所用 Context；主机单测构造时为 null（走 count 兜底）。 */
    private val appContext: Context? = null,
) : AiDataSource {
    private val providers get() = db.aiProviderDao()
    private val models get() = db.aiProviderModelDao()
    private val systemPrompts get() = db.aiSystemPromptDao()
    private val userPrompts get() = db.aiUserPromptDao()
    private val instructions get() = db.aiInstructionDao()
    private val conversations get() = db.aiConversationDao()
    private val messages get() = db.aiMessageDao()
    private val seedMutex = Mutex()

    /**
     * 仅首次补种（C-L-29／L-30）：Room 先提交、prefs 后同步 commit()
     * 的配对写；无 Context 时（主机单测构造）保留旧的 count 兜底。已升级用户首次调用时表非空→只标
     * seeded 不插入；此前已删光的用户会在升级后首次复活一次（无法
     * 区分“从未播种”与“删光”）。
     */
    suspend fun ensureSeeded() {
        val context = appContext
        if (context == null) {
            if (providers.count() == 0) {
                db.withTransaction { db.openHelper.writableDatabase.let { AiSeeds.insertAll(it) } }
            }
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        seedMutex.withLock {
            if (prefs.getBoolean(KEY_SEEDED, false)) return
            if (providers.count() == 0) {
                db.withTransaction { db.openHelper.writableDatabase.let { AiSeeds.insertAll(it) } }
            }
            if (!prefs.edit().putBoolean(KEY_SEEDED, true).commit()) {
                Timber.w("ai seeded flag commit failed, will retry on next access")
            }
        }
    }

    /** 首启在后台补种一次（设置页打开时不阻塞等库）。 */
    fun ensureSeededAsync() {
        scope.launch { runCatching { ensureSeeded() } }
    }

    suspend fun getProviders(): List<AIProvider> =
        providers.getAll().map { stored ->
            reEncryptLegacyTokenIfNeeded(stored)
            stored.withPlainToken()
        }

    override suspend fun getProviderById(id: String): AIProvider? =
        providers.getById(id)?.let { stored ->
            reEncryptLegacyTokenIfNeeded(stored)
            stored.withPlainToken()
        }

    override suspend fun getDefaultProvider(): AIProvider? =
        providers.getDefault()?.let { stored ->
            reEncryptLegacyTokenIfNeeded(stored)
            stored.withPlainToken()
        }

    suspend fun updateProvider(provider: AIProvider) =
        providers.update(provider.copy(apiToken = encryptForWrite(provider)))

    suspend fun insertProvider(provider: AIProvider) =
        providers.insert(provider.copy(apiToken = encryptForWrite(provider)))

    /**
     * 旧明文一次性转密文（[SecretStore] KDoc 的调用方迁移；MIGRATION_19_20
     * 是空迁移，转换不在迁移里做）：读到无前缀的非空 token 时加密回写；
     * CAS（`WHERE apiToken = 旧值`）防并发保存的新 token 被旧密文覆盖；
     * 加密失败／已被他人改写时原样留待下次访问，绝不丢配置。
     */
    private suspend fun reEncryptLegacyTokenIfNeeded(stored: AIProvider) {
        val legacy = stored.apiToken
        if (legacy.isEmpty() || SecretStore.isEncrypted(legacy)) return
        val encrypted = AiTokenCodec.encryptForStorage(legacy) ?: return
        runCatching {
            providers.updateTokenIfUnchanged(stored.id, legacy, encrypted)
        }.onFailure {
            Timber.w(it, "AI token re-encrypt failed for provider=%s", stored.id)
        }
    }

    /**
     * 写盘前加密：空／已是密文原样；Keystore 不可用时更新走现存落盘
     * token（只丢本次新 token，不丢配置）、新增走 ""，并记日志——
     * 绝不写明文。
     */
    private suspend fun encryptForWrite(provider: AIProvider): String {
        val plain = provider.apiToken
        if (plain.isEmpty() || SecretStore.isEncrypted(plain)) return plain
        AiTokenCodec.encryptForStorage(plain)?.let { return it }
        Timber.w(
            "AI token not saved encrypted for provider=%s: Keystore unavailable",
            provider.id,
        )
        return providers.getById(provider.id)?.apiToken ?: ""
    }

    private fun AIProvider.withPlainToken(): AIProvider =
        copy(apiToken = AiTokenCodec.decryptFromStorage(apiToken))
    suspend fun deleteProvider(id: String) = providers.deleteById(id)

    override suspend fun getModelsByProviderId(providerId: String): List<AIProviderModel> = models.getByProviderId(providerId)
    override suspend fun getModelById(id: String): AIProviderModel? = models.getById(id)
    suspend fun insertModel(model: AIProviderModel) = models.insert(model)
    suspend fun updateModel(model: AIProviderModel) = models.update(model)

    suspend fun getSystemPrompts(): List<AISystemPrompt> = systemPrompts.getAll()
    override suspend fun getSystemPromptById(id: String): AISystemPrompt? = systemPrompts.getById(id)

    suspend fun getUserPrompts(): List<AIUserPrompt> = userPrompts.getAll()
    override suspend fun getUserPromptById(id: String): AIUserPrompt? = userPrompts.getById(id)

    suspend fun getInstructions(): List<AIInstruction> = instructions.getAll()
    override suspend fun getInstructionById(id: String): AIInstruction? = instructions.getById(id)

    /**
     * 重置 AI 指令模板（WO52 9.1）：4 条指令改回初始定义——先全删再
     * 插回种子 4 条（INSERT OR IGNORE 语义下“全删再插”等价于重置）。
     * 只动指令表，不动提示词与服务商。
     */
    suspend fun resetInstructionsToSeed() {
        instructions.deleteAll()
        instructions.insertAll(AiSeeds.instructions)
    }

    companion object {
        private const val PREFS_NAME = "ai_prefs"
        private const val KEY_SEEDED = "seeded"

        @Volatile
        private var instance: AiRepository? = null

        fun get(context: Context): AiRepository =
            instance ?: synchronized(this) {
                instance ?: AiRepository(
                    AppDatabase.getInstance(context),
                    appContext = context.applicationContext,
                ).also { instance = it }
            }
    }
}
