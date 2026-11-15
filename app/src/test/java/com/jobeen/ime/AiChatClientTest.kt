// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.ai.AiChatMessage
import com.jobeen.ime.data.ai.AiStreamEvent
import com.jobeen.ime.data.ai.OkHttpAiChatClient
import com.jobeen.ime.data.ai.SseParser
import com.jobeen.ime.data.ai.buildChatRequest
import com.jobeen.ime.data.ai.isAllowedBaseUrl
import com.jobeen.ime.data.database.AIProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 流式请求单测（WO52 9.1）：请求构造（纯函数）＋SSE 解析（纯函数）
 * ＋真 OkHttp 客户端对 JDK 内置 HttpServer（mock 服务端，不引
 * MockWebServer——无此依赖）：验 Bearer 头、body 字段、流式增量、
 * usage、错误分支与缺 token 不发请求。
 */
class AiChatClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun provider(
        token: String = "sk-test",
        baseURL: String = "https://api.deepseek.com",
        apiPath: String = "v1",
    ) = AIProvider(
        id = "p1",
        name = "DeepSeek",
        baseURL = baseURL,
        apiPath = apiPath,
        apiToken = token,
        requestTimeout = 5.0,
        contextWindow = 64000,
    )

    @Test
    fun `C-L-15 https allowed, public http rejected, lan http allowed`() {
        assertTrue(isAllowedBaseUrl("https://api.deepseek.com"))
        assertTrue(isAllowedBaseUrl("https://api.example.com:8443/v1"))
        assertFalse(isAllowedBaseUrl("http://api.deepseek.com"))
        assertFalse(isAllowedBaseUrl("http://8.8.8.8"))
        assertFalse(isAllowedBaseUrl("ftp://192.168.1.1"))
        assertFalse(isAllowedBaseUrl("api.deepseek.com"))
        assertFalse(isAllowedBaseUrl(""))
        assertTrue(isAllowedBaseUrl("http://localhost:8080"))
        assertTrue(isAllowedBaseUrl("http://127.0.0.1:8080"))
        assertTrue(isAllowedBaseUrl("http://10.0.0.5"))
        assertTrue(isAllowedBaseUrl("http://192.168.1.100"))
        assertTrue(isAllowedBaseUrl("http://172.16.0.1"))
        assertTrue(isAllowedBaseUrl("http://172.31.255.255"))
        assertFalse(isAllowedBaseUrl("http://172.32.0.1"))
        assertFalse(isAllowedBaseUrl("http://172.15.0.1"))
    }

    @Test
    fun `C-L-15 buildChatRequest rejects public http base url`() {
        try {
            buildChatRequest(provider(baseURL = "http://api.deepseek.com"), "m", emptyList())
            org.junit.Assert.fail("公网 http 应拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("仅支持 https 或局域网 http"))
        }
        // 局域网 http 照常拼 URL。
        val built = buildChatRequest(provider(baseURL = "http://192.168.1.10:8080"), "m", emptyList())
        assertTrue(built.url.startsWith("http://192.168.1.10:8080/"))
    }

    @Test
    fun buildRequest_urlAndHeaders() {
        val built = buildChatRequest(
            provider(),
            "deepseek-v4-flash",
            listOf(AiChatMessage("user", "你好")),
        )
        assertEquals("https://api.deepseek.com/v1/chat/completions", built.url)
        assertEquals("Bearer sk-test", built.authorization)
        val body = json.parseToJsonElement(built.bodyJson).jsonObject
        assertEquals("deepseek-v4-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(
            true,
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean,
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你好", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        // max_tokens 未设（0）时不发该字段
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("temperature"))
    }

    @Test
    fun buildRequest_trailingSlashAndOptionalFields() {
        val built = buildChatRequest(
            provider(baseURL = "https://example.com/", apiPath = "/v1/").copy(
                maxOutputTokens = 1024,
                temperature = 0.7,
            ),
            "m",
            listOf(AiChatMessage("system", "s"), AiChatMessage("user", "u")),
        )
        assertEquals("https://example.com/v1/chat/completions", built.url)
        val body = json.parseToJsonElement(built.bodyJson).jsonObject
        assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.double, 0.0)
        // apiPath 为空时不产生双斜杠
        val noPath = buildChatRequest(provider(apiPath = ""), "m", emptyList())
        assertEquals("https://api.deepseek.com/chat/completions", noPath.url)
    }

    @Test
    fun sseParser_deltaUsageDone() {
        val parser = SseParser()
        assertEquals(null, parser.feed(": ping"))
        assertEquals(null, parser.feed(""))
        assertEquals(
            AiStreamEvent.Delta("你好"),
            parser.feed("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}"),
        )
        assertEquals(
            AiStreamEvent.Delta("世界"),
            parser.feed("data:{\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}"),
        )
        // 空 content 不产事件；脏行跳过不中断
        assertEquals(null, parser.feed("data: {\"choices\":[{\"delta\":{}}]}"))
        assertEquals(null, parser.feed("data: {not json"))
        assertEquals(
            AiStreamEvent.Usage(10, 20, 30),
            parser.feed("data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}"),
        )
        assertEquals(AiStreamEvent.Done, parser.feed("data: [DONE]"))
    }

    @Test
    fun client_streamsAgainstMockServer() = runBlocking {
        var seenAuth: String? = null
        var seenBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            seenAuth = exchange.requestHeaders.getFirst("Authorization")
            seenBody = exchange.requestBody.bufferedReader().readText()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody.bufferedWriter()
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n")
            out.write("data: {\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n")
            out.write("data: [DONE]\n\n")
            out.flush()
            out.close()
        }
        server.start()
        try {
            val port = server.address.port
            val client = OkHttpAiChatClient()
            val events = client.stream(
                provider(baseURL = "http://127.0.0.1:$port"),
                "deepseek-v4-flash",
                listOf(AiChatMessage("user", "你好")),
            ).toList()
            assertEquals("Bearer sk-test", seenAuth)
            val body = json.parseToJsonElement(seenBody).jsonObject
            assertEquals("deepseek-v4-flash", body["model"]!!.jsonPrimitive.content)
            assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
            assertTrue(events.contains(AiStreamEvent.Delta("你")))
            assertTrue(events.contains(AiStreamEvent.Delta("好")))
            assertTrue(events.contains(AiStreamEvent.Usage(3, 2, 5)))
            assertTrue(events.contains(AiStreamEvent.Done))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun client_httpErrorEmitsErrorEvent() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = "{\"error\":{\"message\":\"invalid token\"}}".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val port = server.address.port
            val events = OkHttpAiChatClient().stream(
                provider(baseURL = "http://127.0.0.1:$port"),
                "deepseek-v4-flash",
                listOf(AiChatMessage("user", "hi")),
            ).toList()
            val error = events.filterIsInstance<AiStreamEvent.Error>().single()
            assertEquals("http", error.code)
            assertEquals("invalid token", error.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun client_missingTokenEmitsErrorWithoutRequest() = runBlocking {
        var hits = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits++
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            val events = OkHttpAiChatClient().stream(
                provider(token = "", baseURL = "http://127.0.0.1:$port"),
                "deepseek-v4-flash",
                listOf(AiChatMessage("user", "hi")),
            ).toList()
            val error = events.filterIsInstance<AiStreamEvent.Error>().single()
            assertEquals("missing_token", error.code)
            assertEquals(0, hits)
        } finally {
            server.stop(0)
        }
    }
}
