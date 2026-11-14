// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.ai.AiBackupCodec
import com.jobeen.ime.data.ai.AiBackupData
import com.jobeen.ime.data.database.AIProvider
import com.jobeen.ime.data.database.AiSeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 备份 token 规则单测（WO52 9.1，凭据红线）：
 * 导出脱敏（token 清空）＋还原强制清空（外来备份带 token 也丢弃）。
 * org.json 的读写在 BackupManager（Android 侧）做 plumbing，本单测
 * 只断规则层——备份文件里既无 token 值，也无 apiToken 键（导出侧
 * 不写该键，见 BackupManager.collectPayload）。
 */
class AiBackupCodecTest {

    private fun seededWithToken(): AiBackupData = AiBackupData(
        providers = AiSeeds.providers.map { it.copy(apiToken = "sk-live-secret") },
        models = AiSeeds.models,
        systemPrompts = AiSeeds.systemPrompts,
        userPrompts = AiSeeds.userPrompts,
        instructions = AiSeeds.instructions,
    )

    @Test
    fun exportBlanksTokens() {
        val exported = AiBackupCodec.forExport(seededWithToken())
        assertFalse(AiBackupCodec.containsToken(exported))
        assertEquals("", exported.providers.single().apiToken)
        // 其余字段一字不动（脱敏只动 token）
        assertEquals("DeepSeek", exported.providers.single().name)
        assertEquals("https://api.deepseek.com", exported.providers.single().baseURL)
        assertEquals(AiSeeds.models, exported.models)
        assertEquals(AiSeeds.systemPrompts, exported.systemPrompts)
        assertEquals(AiSeeds.instructions, exported.instructions)
    }

    @Test
    fun exportWithoutTokenIsNoop() {
        val data = AiBackupData(providers = AiSeeds.providers)
        assertEquals(data, AiBackupCodec.forExport(data))
    }

    @Test
    fun restoreStripsForeignToken() {
        val foreign = AIProvider(
            id = "x",
            name = "Evil",
            baseURL = "https://example.com",
            apiPath = "v1",
            apiToken = "sk-stolen",
        )
        val restored = AiBackupCodec.providerForRestore(foreign)
        assertEquals("", restored.apiToken)
        assertEquals("Evil", restored.name)
        assertEquals("https://example.com", restored.baseURL)
    }

    @Test
    fun containsTokenDetects() {
        assertTrue(AiBackupCodec.containsToken(seededWithToken()))
        assertFalse(AiBackupCodec.containsToken(AiBackupData(providers = AiSeeds.providers)))
    }
}
