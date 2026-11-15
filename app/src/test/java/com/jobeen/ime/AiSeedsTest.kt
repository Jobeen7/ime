// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.database.AiSeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 种子数据单测（WO52 9.1）：数量／名称／关键值／引用闭合。
 * 种子措辞为 Jime 自写（B-C-1）；本单测只断关键字段与引用关系，
 * 不全文比对正文。
 */
class AiSeedsTest {

    @Test
    fun counts() {
        assertEquals(1, AiSeeds.providers.size)
        assertEquals(1, AiSeeds.models.size)
        assertEquals(5, AiSeeds.systemPrompts.size)
        assertEquals(3, AiSeeds.userPrompts.size)
        assertEquals(4, AiSeeds.instructions.size)
    }

    @Test
    fun deepSeekProvider() {
        val provider = AiSeeds.providers.single()
        assertEquals("DeepSeek", provider.name)
        assertEquals("https://api.deepseek.com", provider.baseURL)
        assertEquals("v1", provider.apiPath)
        assertEquals(64000, provider.contextWindow)
        assertEquals(60.0, provider.requestTimeout, 0.0)
        assertEquals("", provider.apiToken)
        assertTrue(provider.isDefault)
        assertTrue(provider.isBuiltIn)
        assertEquals(AiSeeds.DEEPSEEK_MODEL_ROW_ID, provider.defaultModelId)
    }

    @Test
    fun deepSeekModel() {
        val model = AiSeeds.models.single()
        assertEquals(AiSeeds.DEEPSEEK_PROVIDER_ID, model.providerId)
        assertEquals("deepseek-v4-flash", model.modelID)
        assertEquals("deepseek-v4-flash", model.displayName)
    }

    @Test
    fun systemPromptNamesAndDefaults() {
        val names = AiSeeds.systemPrompts.map { it.name }
        assertEquals(listOf("无 System Prompt", "翻译", "润色", "改语气", "回复"), names)
        val defaults = AiSeeds.systemPrompts.filter { it.isDefault }
        assertEquals(listOf("无 System Prompt"), defaults.map { it.name })
        assertTrue(AiSeeds.systemPrompts.first { it.name == "无 System Prompt" }.body.isEmpty())
        val translate = AiSeeds.systemPrompts.first { it.name == "翻译" }
        assertTrue(translate.body.startsWith("你来负责翻译"))
        assertTrue(translate.purpose.contains("互译"))
    }

    @Test
    fun userPromptTemplates() {
        val names = AiSeeds.userPrompts.map { it.name }
        assertEquals(listOf("无 User Prompt", "系统剪贴板", "选中文本"), names)
        val defaults = AiSeeds.userPrompts.filter { it.isDefault }
        assertEquals(listOf("无 User Prompt"), defaults.map { it.name })
        assertEquals("{{input}}", AiSeeds.userPrompts.first { it.name == "无 User Prompt" }.templateBody)
        assertEquals(
            "{{input}}\n\n{{pasteboardContent}}",
            AiSeeds.userPrompts.first { it.name == "系统剪贴板" }.templateBody,
        )
        assertEquals(
            "{{input}}\n\n{{selectedText}}",
            AiSeeds.userPrompts.first { it.name == "选中文本" }.templateBody,
        )
    }

    @Test
    fun instructionReferencesResolve() {
        val sysIds = AiSeeds.systemPrompts.map { it.id }.toSet()
        val userIds = AiSeeds.userPrompts.map { it.id }.toSet()
        val providerIds = AiSeeds.providers.map { it.id }.toSet()
        assertEquals(listOf("翻译", "润色", "改写语气", "回复"), AiSeeds.instructions.map { it.name })
        for (ins in AiSeeds.instructions) {
            assertTrue("指令 ${ins.name} 的 systemPromptID 须指向已播种的系统提示词",
                ins.systemPromptID in sysIds)
            assertTrue("指令 ${ins.name} 的 userPromptID 须指向已播种的用户提示词",
                ins.userPromptID in userIds)
            assertTrue("指令 ${ins.name} 的 providerID 须指向已播种的服务商",
                ins.providerID in providerIds)
        }
        assertEquals(
            "翻译剪贴板里的文字",
            AiSeeds.instructions.first { it.name == "翻译" }.detail,
        )
        assertEquals(
            "替剪贴板里的消息拟一条回复",
            AiSeeds.instructions.first { it.name == "回复" }.detail,
        )
    }

    @Test
    fun sortOrderIsSeedOrder() {
        assertEquals(
            AiSeeds.systemPrompts.map { it.sort },
            AiSeeds.systemPrompts.map { it.sort }.sorted(),
        )
        assertEquals(
            AiSeeds.instructions.map { it.sort },
            AiSeeds.instructions.map { it.sort }.sorted(),
        )
    }
}
