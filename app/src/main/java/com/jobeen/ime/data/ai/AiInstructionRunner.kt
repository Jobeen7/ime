// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.ai

import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.database.AISystemPrompt
import com.jobeen.ime.data.database.AIUserPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 模板占位替换（纯函数，可单测）：`{{input}}`→输入文本；`{{pasteboardContent}}`→剪贴板；`{{selectedText}}`→选中文本。缺省的上下文一律替换成空串，不留占位原样。 */
fun renderUserPromptTemplate(
    template: String,
    input: String,
    pasteboardContent: String = "",
    selectedText: String = "",
): String = template
    .replace("{{input}}", input)
    .replace("{{pasteboardContent}}", pasteboardContent)
    .replace("{{selectedText}}", selectedText)

/** 指令执行上下文：UI／调用方提供剪贴板与选中文本，执行器本身不碰系统服务。 */
data class AiInstructionContext(
    val pasteboardContent: String = "",
    val selectedText: String = "",
)

/** 指令执行结果：流式事件原样透传；`resolved` 记录实际用到的三方行（供调用方存对话快照）。 */
data class AiInstructionRun(
    val instruction: AIInstruction,
    val systemPrompt: AISystemPrompt?,
    val userPrompt: AIUserPrompt?,
    val events: Flow<AiStreamEvent>,
)

/**
 * AI 指令执行器（WO52 9.1）：4 条内置指令直达剪贴板文本的执行路径。
 * 解析指令 → 系统提示词 body（空＝不发 system 消息）→ 用户提示词模板
 * 渲染（{{input}}＝剪贴板文本）→ 服务商默认模型 → 流式调用。
 * 解析不到服务商／模型时返回单条 Error 事件，不抛异常。
 */
class AiInstructionRunner(
    private val repository: AiDataSource,
    private val client: AiChatClient = OkHttpAiChatClient(),
) {
    suspend fun run(
        instructionId: String,
        input: String,
        context: AiInstructionContext = AiInstructionContext(),
    ): AiInstructionRun? {
        val instruction = repository.getInstructionById(instructionId) ?: return null
        val systemPrompt = instruction.systemPromptID?.let { repository.getSystemPromptById(it) }
        val userPrompt = instruction.userPromptID?.let { repository.getUserPromptById(it) }
        val providerId = instruction.providerID
            ?: repository.getDefaultProvider()?.id
            ?: return AiInstructionRun(instruction, systemPrompt, userPrompt, flow {
                emit(AiStreamEvent.Error("未配置 AI 服务商", "no_provider"))
            })
        val provider = repository.getProviderById(providerId)
            ?: return AiInstructionRun(instruction, systemPrompt, userPrompt, flow {
                emit(AiStreamEvent.Error("未配置 AI 服务商", "no_provider"))
            })
        val modelId = provider.defaultModelId?.let { repository.getModelById(it)?.modelID }
            ?: repository.getModelsByProviderId(provider.id).firstOrNull { it.isEnabled }?.modelID
            ?: return AiInstructionRun(instruction, systemPrompt, userPrompt, flow {
                emit(AiStreamEvent.Error("服务商未配置模型", "no_model"))
            })
        val messages = buildList {
            systemPrompt?.body?.takeIf { it.isNotBlank() }?.let { add(AiChatMessage("system", it)) }
            val userText = renderUserPromptTemplate(
                template = userPrompt?.templateBody ?: "{{input}}",
                input = input,
                pasteboardContent = context.pasteboardContent,
                selectedText = context.selectedText,
            )
            add(AiChatMessage("user", userText))
        }
        return AiInstructionRun(instruction, systemPrompt, userPrompt, client.stream(provider, modelId, messages))
    }
}
