// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.ai.AiChatClient
import com.jobeen.ime.data.ai.AiChatMessage
import com.jobeen.ime.data.ai.AiDataSource
import com.jobeen.ime.data.ai.AiInstructionContext
import com.jobeen.ime.data.ai.AiInstructionRunner
import com.jobeen.ime.data.ai.AiStreamEvent
import com.jobeen.ime.data.ai.renderUserPromptTemplate
import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.database.AIProvider
import com.jobeen.ime.data.database.AIProviderModel
import com.jobeen.ime.data.database.AISystemPrompt
import com.jobeen.ime.data.database.AIUserPrompt
import com.jobeen.ime.data.database.AiSeeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 指令执行单测（WO52 9.1）：4 条种子指令各调一遍（mock 客户端，
 * 不碰真网络）——验系统消息＝指令绑定的系统提示词 body、用户消息＝
 * 模板渲染（{{input}}＝剪贴板文本）、模型＝服务商默认模型。
 */
class AiInstructionRunnerTest {

    /** 以种子数据为底的假数据源＋记录请求参数的假客户端。 */
    private class FakeSource : AiDataSource {
        override suspend fun getInstructionById(id: String): AIInstruction? =
            AiSeeds.instructions.firstOrNull { it.id == id }

        override suspend fun getSystemPromptById(id: String): AISystemPrompt? =
            AiSeeds.systemPrompts.firstOrNull { it.id == id }

        override suspend fun getUserPromptById(id: String): AIUserPrompt? =
            AiSeeds.userPrompts.firstOrNull { it.id == id }

        override suspend fun getDefaultProvider(): AIProvider? = AiSeeds.providers.single()

        override suspend fun getProviderById(id: String): AIProvider? =
            AiSeeds.providers.firstOrNull { it.id == id }

        override suspend fun getModelById(id: String): AIProviderModel? =
            AiSeeds.models.firstOrNull { it.id == id }

        override suspend fun getModelsByProviderId(providerId: String): List<AIProviderModel> =
            AiSeeds.models.filter { it.providerId == providerId }
    }

    private class CapturingClient : AiChatClient {
        var lastProvider: AIProvider? = null
        var lastModelId: String? = null
        var lastMessages: List<AiChatMessage>? = null

        override fun stream(
            provider: AIProvider,
            modelId: String,
            messages: List<AiChatMessage>,
        ): Flow<AiStreamEvent> {
            lastProvider = provider
            lastModelId = modelId
            lastMessages = messages
            return flowOf(AiStreamEvent.Delta("ok"), AiStreamEvent.Done)
        }
    }

    @Test
    fun templateRender() {
        assertEquals(
            "译\n\nHello world",
            renderUserPromptTemplate("{{input}}\n\n{{pasteboardContent}}", "译", pasteboardContent = "Hello world"),
        )
        assertEquals("只输入", renderUserPromptTemplate("{{input}}", "只输入"))
        // 缺省的上下文替换成空串，不留占位原样
        assertEquals("a\n\n", renderUserPromptTemplate("{{input}}\n\n{{pasteboardContent}}", "a"))
        assertEquals(
            "x\n\ny",
            renderUserPromptTemplate("{{input}}\n\n{{selectedText}}", "x", selectedText = "y"),
        )
    }

    @Test
    fun allFourSeedInstructionsRunOnce() = runBlocking {
        val source = FakeSource()
        val client = CapturingClient()
        val runner = AiInstructionRunner(source, client)
        val clipboard = "Hello world"
        val expectedSystem = mapOf(
            "翻译" to AiSeeds.systemPrompts.first { it.name == "翻译" }.body,
            "润色" to AiSeeds.systemPrompts.first { it.name == "润色" }.body,
            "改写语气" to AiSeeds.systemPrompts.first { it.name == "改语气" }.body,
            "回复" to AiSeeds.systemPrompts.first { it.name == "回复" }.body,
        )
        for (instruction in AiSeeds.instructions) {
            val run = runner.run(
                instructionId = instruction.id,
                input = clipboard,
                context = AiInstructionContext(pasteboardContent = clipboard),
            )
            checkNotNull(run) { "指令 ${instruction.name} 应能解析" }
            val events = run.events.toList()
            assertEquals(
                "指令 ${instruction.name} 应收到 mock 的 Delta＋Done",
                listOf(AiStreamEvent.Delta("ok"), AiStreamEvent.Done), events,
            )
            assertEquals("DeepSeek", client.lastProvider?.name)
            assertEquals("deepseek-v4-flash", client.lastModelId)
            val messages = client.lastMessages!!
            assertEquals("指令 ${instruction.name} 应发 system＋user 两条消息", 2, messages.size)
            assertEquals("system", messages[0].role)
            assertEquals(expectedSystem[instruction.name], messages[0].content)
            assertEquals("user", messages[1].role)
            assertEquals("$clipboard\n\n$clipboard", messages[1].content)
        }
    }

    @Test
    fun unknownInstructionReturnsNull() = runBlocking {
        val runner = AiInstructionRunner(FakeSource(), CapturingClient())
        assertEquals(null, runner.run("no-such-id", "x"))
    }

    @Test
    fun emptySystemPromptSendsNoSystemMessage() = runBlocking {
        val source = object : AiDataSource by FakeSource() {
            override suspend fun getInstructionById(id: String): AIInstruction? =
                AiSeeds.instructions.first { it.name == "翻译" }
                    .copy(systemPromptID = AiSeeds.systemPrompts.first { it.name == "无 System Prompt" }.id)
        }
        val client = CapturingClient()
        val runner = AiInstructionRunner(source, client)
        val run = runner.run(
            instructionId = AiSeeds.instructions.first { it.name == "翻译" }.id,
            input = "hi",
            context = AiInstructionContext(pasteboardContent = "hi"),
        )
        checkNotNull(run)
        run.events.toList()
        // “无 System Prompt” body 为空，不发 system 消息，只剩 user 一条
        assertEquals(1, client.lastMessages!!.size)
        assertEquals("user", client.lastMessages!!.single().role)
        assertEquals("hi\n\nhi", client.lastMessages!!.single().content)
    }

    @Test
    fun missingProviderEmitsNoProviderError() = runBlocking {
        val source = object : AiDataSource by FakeSource() {
            override suspend fun getDefaultProvider(): AIProvider? = null
            override suspend fun getProviderById(id: String): AIProvider? = null
        }
        val runner = AiInstructionRunner(source, CapturingClient())
        val run = runner.run(
            instructionId = AiSeeds.instructions.first { it.name == "翻译" }.id,
            input = "hi",
        )
        checkNotNull(run)
        val events = run.events.toList()
        assertEquals(1, events.size)
        val error = events.single() as AiStreamEvent.Error
        assertEquals("no_provider", error.code)
        assertTrue(events.none { it is AiStreamEvent.Delta })
    }
}
