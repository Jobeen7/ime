// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.ai

import com.jobeen.ime.data.database.AIConversation
import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.database.AIMessage
import com.jobeen.ime.data.database.AIProvider
import com.jobeen.ime.data.database.AIProviderModel
import com.jobeen.ime.data.database.AISystemPrompt
import com.jobeen.ime.data.database.AIUserPrompt

/** AI 备份数据包（7 表全量；纯数据类，不碰 org.json，可主机单测）。 */
data class AiBackupData(
    val providers: List<AIProvider> = emptyList(),
    val models: List<AIProviderModel> = emptyList(),
    val systemPrompts: List<AISystemPrompt> = emptyList(),
    val userPrompts: List<AIUserPrompt> = emptyList(),
    val instructions: List<AIInstruction> = emptyList(),
    val conversations: List<AIConversation> = emptyList(),
    val messages: List<AIMessage> = emptyList(),
)

/**
 * AI 备份编解码的 token 规则（WO52 9.1，凭据红线）：
 *
 * - 导出：[forExport] 把全部服务商 `apiToken` 清空；BackupManager
 *   的 JSON 层据此**不写 `apiToken` 键**（备份文件里既无 token 值，
 *   也无该键名）。
 * - 还原：[providerForRestore] 强制 `apiToken = ""`——即使外来
 *   备份文件里带了 token 字段也丢弃，还原后由使用者重新自填。
 * - 备份清单（manifest）不加 AI 计数键（旧备份无这些键会校验失败）。
 *
 * org.json 的读写只在 BackupManager 里做 plumbing（Android 侧，
 * 不进主机单测）；token 规则本身全在这一层，可单测。
 */
object AiBackupCodec {

    /** 导出前脱敏：服务商 token 一律清空。 */
    fun forExport(data: AiBackupData): AiBackupData =
        data.copy(providers = data.providers.map { it.copy(apiToken = "") })

    /** 还原时强制清空 token（外来备份带 token 也丢弃）。 */
    fun providerForRestore(provider: AIProvider): AIProvider =
        provider.copy(apiToken = "")

    /** 导出包里是否含 token（单测断言用：forExport 之后必须为 false）。 */
    fun containsToken(data: AiBackupData): Boolean =
        data.providers.any { it.apiToken.isNotEmpty() }
}
