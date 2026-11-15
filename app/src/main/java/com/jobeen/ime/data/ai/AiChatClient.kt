// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.ai

import com.jobeen.ime.data.database.AIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Chat Completions 的一条消息（role：system／user／assistant）。 */
data class AiChatMessage(val role: String, val content: String)

/** 流式事件。`Error.code` 供 UI 映射文案：`missing_token`／`http`／`network`；`messageRes` 非空时 UI 优先用它解析文案（L-44）。 */
sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : AiStreamEvent
    data object Done : AiStreamEvent
    data class Error(
        val message: String = "",
        val code: String = "network",
        @androidx.annotation.StringRes val messageRes: Int? = null,
    ) : AiStreamEvent
}

/**
 * OpenAI 兼容 Chat Completions 流式客户端（WO52 9.1）。
 *
 * 请求（单子原文）：`baseURL`＋`apiPath` 追加 `chat`／`completions`；
 * 请求头 `Content-Type: application/json` 与 `Authorization: Bearer <apiToken>`；
 * body：`model`、`messages: [{role, content}]`、`stream: true`、
 * `stream_options: {include_usage: true}`，另有 maxOutputTokens／温度
 * 等可选字段（只在 provider 设了值时发）。
 *
 * 走现有 OkHttp 4.12.1，不新增网络库。`buildRequest` 与 [SseParser] 是
 * 纯函数，可主机单测；只有 [OkHttpAiChatClient] 碰网络。
 */
interface AiChatClient {
    fun stream(provider: AIProvider, modelId: String, messages: List<AiChatMessage>): Flow<AiStreamEvent>
}

/** [AiChatClient.stream] 的纯请求构造结果（不发请求，可单测）。 */
data class BuiltChatRequest(val url: String, val authorization: String, val bodyJson: String)

/**
 * C-L-15：baseURL 准入——https 一律放行；http 只放行局域网
 * （A-29 口径：localhost／127.0.0.1／::1、10.*、192.168.*、
 * 172.16–31.*），其余 scheme／无 scheme／公网 http 一律拒绝。
 * 保存时（AiSettingsScreen）与拼 URL 前（[buildChatRequest]）
 * 各校验一次。
 */
fun isAllowedBaseUrl(raw: String): Boolean {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "https") return true
    if (scheme != "http") return false
    val host = uri.host?.lowercase() ?: return false
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it < 0 || it > 255 }) return false
    return when {
        octets[0] == 10 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        else -> false
    }
}

/** 拼 Chat Completions 请求：URL、Bearer 头、body JSON。 */
fun buildChatRequest(provider: AIProvider, modelId: String, messages: List<AiChatMessage>): BuiltChatRequest {
    // C-L-15：拼 URL 前同样校验（备份还原的老配置也可能带公网 http）。
    require(isAllowedBaseUrl(provider.baseURL)) { "仅支持 https 或局域网 http" }
    val base = provider.baseURL.trimEnd('/')
    val path = provider.apiPath.trim('/').let { if (it.isEmpty()) "" else "/$it" }
    val url = "$base$path/chat/completions"
    val body = buildJsonObject {
        // customParametersJSON 先合入，再写死 model/messages/stream 等
        // 关键字段——自定义参数改不了这几个（防把 stream 关掉）。
        provider.customParametersJSON?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }
                .getOrNull()?.forEach { (k, v) -> put(k, v) }
        }
        put("model", modelId)
        putJsonArray("messages") {
            for (m in messages) {
                add(buildJsonObject { put("role", m.role); put("content", m.content) })
            }
        }
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        if (provider.maxOutputTokens > 0) put("max_tokens", provider.maxOutputTokens)
        provider.temperature?.let { put("temperature", it) }
        provider.presencePenalty?.let { put("presence_penalty", it) }
        provider.frequencyPenalty?.let { put("frequency_penalty", it) }
    }
    return BuiltChatRequest(
        url = url,
        authorization = "Bearer ${provider.apiToken}",
        bodyJson = Json.encodeToString(JsonObject.serializer(), body),
    )
}

/**
 * SSE 行解析器（纯函数，可单测）：每次喂一行，返回事件或 null。
 * 处理 `data: {...}`（choices[0].delta.content → Delta；
 * usage → Usage）、`data: [DONE]` → Done；其余行忽略；
 * 解析失败的 chunk 跳过不中断流（服务端偶发脏行不应断掉整段回复）。
 */
class SseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun feed(line: String): AiStreamEvent? {
        val data = line.removePrefix("data:").trimStart().takeIf { line.startsWith("data:") } ?: return null
        if (data == "[DONE]") return AiStreamEvent.Done
        if (data.isEmpty()) return null
        val element = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        element["usage"]?.takeIf { it !is JsonNull }?.jsonObject?.let { usage ->
            val prompt = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val completion = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val total = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: prompt + completion
            return AiStreamEvent.Usage(prompt, completion, total)
        }
        val delta = element["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
        return delta?.takeIf { it.isNotEmpty() }?.let { AiStreamEvent.Delta(it) }
    }
}

/** OkHttp 实现：流式读 body，行行喂 [SseParser]；取消时关 call。 */
class OkHttpAiChatClient(
    private val okHttp: OkHttpClient = OkHttpClient(),
) : AiChatClient {

    override fun stream(provider: AIProvider, modelId: String, messages: List<AiChatMessage>): Flow<AiStreamEvent> = flow {
        // C-L-15：地址不合规则不发请求（与保存时校验同口径）。
        if (!isAllowedBaseUrl(provider.baseURL)) {
            emit(AiStreamEvent.Error("", "bad_base_url", com.jobeen.ime.R.string.ai_base_url_scheme_error))
            return@flow
        }
        if (provider.apiToken.isBlank()) {
            // 未填 token：不发请求。message 是兜底文案，UI 按 code
            // `missing_token` 映射 strings.xml 的正式文案。
            emit(AiStreamEvent.Error("请先在服务商设置里填写 API Token", "missing_token"))
            return@flow
        }
        val built = buildChatRequest(provider, modelId, messages)
        val timeoutSeconds = provider.requestTimeout.takeIf { it > 0 }?.toLong() ?: 60L
        val client = okHttp.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            // C-L-16：不跟随重定向——重定向会把 Bearer 头带去别站，
            // 且能把 https 请求降级到 http；要换站由用户改 baseURL。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(built.url)
            .header("Content-Type", "application/json")
            .header("Authorization", built.authorization)
            .post(built.bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val detail = runCatching {
                        Json.parseToJsonElement(body).jsonObject["error"]
                            ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: body.take(200)
                    emit(AiStreamEvent.Error(detail?.ifBlank { null } ?: "HTTP ${resp.code}", "http"))
                    return@flow
                }
                val parser = SseParser()
                val source = resp.body?.source() ?: return@flow
                while (currentCoroutineContext().isActive) {
                    val line = try {
                        source.readUtf8Line() ?: break
                    } catch (e: IOException) {
                        emit(AiStreamEvent.Error(e.message ?: "network error", "network"))
                        return@flow
                    }
                    parser.feed(line)?.let { emit(it) }
                }
                emit(AiStreamEvent.Done)
            }
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                emit(AiStreamEvent.Error(e.message ?: "network error", "network"))
            }
        }
    }.flowOn(Dispatchers.IO)
}
