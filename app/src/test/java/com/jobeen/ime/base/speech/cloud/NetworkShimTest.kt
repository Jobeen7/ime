// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** 内存传输（单测替身，生产用 OkHttpNetworkTransport）。 */
class FakeTransport : NetworkTransport {
    data class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    val requests = mutableListOf<RecordedRequest>()
    var httpResponse = HttpResponse(200, "ok".toByteArray())

    override fun httpPost(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): HttpResponse {
        requests.add(RecordedRequest(url, headers, body))
        return httpResponse
    }

    override fun websocket(
        url: String,
        headers: Map<String, String>,
        listener: NetworkTransport.WsListener,
    ): NetworkTransport.WsHandle {
        requests.add(RecordedRequest(url, headers, ByteArray(0)))
        return object : NetworkTransport.WsHandle {
            override fun send(text: String): Boolean = true
            override fun sendBytes(bytes: ByteArray): Boolean = true
            override fun close() = Unit
        }
    }
}

/** 网络垫片：scheme／域名／禁转头／代附凭证／体上限。 */
class NetworkShimTest {

    private fun shim(
        hosts: Set<String> = setOf("asr.example.com"),
        secrets: Map<String, String> = mapOf("api_key" to "fixture-secret"),
    ): NetworkShim = NetworkShim(
        allowedHosts = hosts,
        authHeaders = listOf(AuthHeaderDecl("Authorization", "api_key", "Bearer ")),
        secretResolver = { field -> secrets[field] },
        transport = FakeTransport(),
    )

    @Test
    fun httpPost_allowedHost_attachesDeclaredAuth() {
        val transport = FakeTransport()
        val shim = NetworkShim(
            allowedHosts = setOf("asr.example.com"),
            authHeaders = listOf(AuthHeaderDecl("Authorization", "api_key", "Bearer ")),
            secretResolver = { "fixture-secret" },
            transport = transport,
        )
        val response = shim.httpPost(
            "https://asr.example.com/v1/asr",
            mapOf("X-Custom" to "1"),
            "body".toByteArray(),
            "application/json",
        )
        assertEquals(200, response.status)
        val sent = transport.requests.single()
        assertEquals("Bearer fixture-secret", sent.headers["Authorization"])
        assertEquals("1", sent.headers["X-Custom"])
        assertEquals("application/json", sent.headers["Content-Type"])
    }

    @Test
    fun httpPost_stripsScriptCredentials() {
        val transport = FakeTransport()
        val shim = NetworkShim(
            allowedHosts = setOf("asr.example.com"),
            authHeaders = emptyList(),
            secretResolver = { null },
            transport = transport,
        )
        shim.httpPost(
            "https://asr.example.com/v1/asr",
            mapOf("Authorization" to "Bearer evil", "Cookie" to "a=b", "X-Ok" to "1"),
            ByteArray(0),
            null,
        )
        val sent = transport.requests.single()
        assertFalse(sent.headers.containsKey("Authorization"))
        assertFalse(sent.headers.containsKey("Cookie"))
        assertEquals("1", sent.headers["X-Ok"])
    }

    @Test
    fun urlValidation_rejectsBadUrls() {
        val s = shim()
        fun expectRejection(block: () -> Unit) {
            try {
                block()
                fail("expected rejection")
            } catch (e: NetworkException) {
                assertTrue(e.reason.isNotEmpty())
            }
        }
        expectRejection {
            s.httpPost("http://asr.example.com/x", emptyMap(), ByteArray(0), null)
        }
        expectRejection {
            s.httpPost("https://user:pass@asr.example.com/x", emptyMap(), ByteArray(0), null)
        }
        expectRejection {
            s.httpPost("https://asr.example.com:8443/x", emptyMap(), ByteArray(0), null)
        }
        expectRejection {
            s.httpPost("https://evil.example.com/x", emptyMap(), ByteArray(0), null)
        }
        expectRejection {
            s.httpPost("https://sub.asr.example.com/x", emptyMap(), ByteArray(0), null)
        }
        try {
            s.websocket("https://asr.example.com/ws", emptyMap(), object : NetworkTransport.WsListener {
                override fun onMessage(text: String?, bytes: ByteArray?) = Unit
                override fun onClosed() = Unit
                override fun onFailure() = Unit
            })
            fail("https websocket must be rejected")
        } catch (e: NetworkException) {
            assertEquals("bad_scheme", e.reason)
        }
    }

    @Test
    fun websocket_wssUrl_reachesTransport() {
        // 回归：OkHttp HttpUrl 不认 wss，曾致所有 WebSocket 插件在垫片层
        // 被拒（bad_url）；校验改用 URI 后 wss 必须直达传输层。
        val transport = FakeTransport()
        val s = NetworkShim(
            allowedHosts = setOf("asr.example.com"),
            authHeaders = listOf(AuthHeaderDecl("Authorization", "api_key", "Bearer ")),
            secretResolver = { "fixture-secret" },
            transport = transport,
        )
        s.websocket(
            "wss://asr.example.com/ws",
            emptyMap(),
            object : NetworkTransport.WsListener {
                override fun onMessage(text: String?, bytes: ByteArray?) = Unit
                override fun onClosed() = Unit
                override fun onFailure() = Unit
            },
        )
        val sent = transport.requests.single()
        assertEquals("wss://asr.example.com/ws", sent.url)
        assertEquals("Bearer fixture-secret", sent.headers["Authorization"])
    }

    @Test
    fun missingSecret_rejectedBeforeSend() {
        val transport = FakeTransport()
        val s = NetworkShim(
            allowedHosts = setOf("asr.example.com"),
            authHeaders = listOf(AuthHeaderDecl("Authorization", "api_key", "Bearer ")),
            secretResolver = { null },
            transport = transport,
        )
        try {
            s.httpPost("https://asr.example.com/x", emptyMap(), ByteArray(0), null)
            fail("missing secret must be rejected")
        } catch (e: NetworkException) {
            assertEquals("secret_missing", e.reason)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun bodyCaps_enforced() {
        val s = shim()
        try {
            s.httpPost(
                "https://asr.example.com/x",
                emptyMap(),
                ByteArray(NetworkShim.MAX_REQUEST_BODY + 1),
                null,
            )
            fail("oversize request must be rejected")
        } catch (e: NetworkException) {
            assertEquals("body_too_large", e.reason)
        }
        val transport = FakeTransport()
        transport.httpResponse = HttpResponse(200, ByteArray(NetworkShim.MAX_RESPONSE_BODY + 1))
        val s2 = NetworkShim(
            allowedHosts = setOf("asr.example.com"),
            authHeaders = emptyList(),
            secretResolver = { null },
            transport = transport,
        )
        try {
            s2.httpPost("https://asr.example.com/x", emptyMap(), ByteArray(0), null)
            fail("oversize response must be rejected")
        } catch (e: NetworkException) {
            assertEquals("response_too_large", e.reason)
        }
    }

    @Test
    fun badContentType_rejected() {
        val s = shim()
        try {
            s.httpPost(
                "https://asr.example.com/x",
                emptyMap(),
                ByteArray(0),
                "text/plain; charset=utf-8",
            )
            fail("parameterized content type must be rejected")
        } catch (e: NetworkException) {
            assertEquals("bad_content_type", e.reason)
        }
    }

    // ---- 本批新增：流式响应上限（Interceptor 假响应＋计数体，不依赖 MockWebServer）----

    /** 计数响应体：source() 直接返内部 Buffer，读多少、剩多少一目了然。 */
    private class CountingBody(private val data: ByteArray) : ResponseBody() {
        private val buffer = Buffer().write(data)
        val bytesRead: Long get() = data.size.toLong() - buffer.size
        override fun contentType(): okhttp3.MediaType? = null
        override fun contentLength(): Long = data.size.toLong()
        override fun source(): okio.BufferedSource = buffer
    }

    private fun okHttpTransportWithBody(body: CountingBody, maxBytes: Long): OkHttpNetworkTransport {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()
        return OkHttpNetworkTransport(client, maxBytes)
    }

    @Test
    fun okHttpTransport_streamsAndStopsAtCap() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val body = CountingBody(data)
        val transport = okHttpTransportWithBody(body, 1024)
        try {
            transport.httpPost("https://asr.example.com/x", emptyMap(), ByteArray(0))
            fail("oversize streamed response must be rejected")
        } catch (e: NetworkException) {
            assertEquals("response_too_large", e.reason)
        }
        // 流式证据：只读了一个 8KB 块就停，远未读完 100KB 整包。
        assertTrue("early stop expected, read=${body.bytesRead}", body.bytesRead <= 1024 + 8192)
        assertTrue("must have read past cap to detect it, read=${body.bytesRead}", body.bytesRead > 1024)
        assertTrue("must not have read whole body, read=${body.bytesRead}", body.bytesRead < data.size)
    }

    @Test
    fun okHttpTransport_underCapReturnsFullBody() {
        val data = ByteArray(100) { it.toByte() }
        val body = CountingBody(data)
        val transport = okHttpTransportWithBody(body, 1024)
        val response = transport.httpPost("https://asr.example.com/x", emptyMap(), ByteArray(0))
        assertEquals(200, response.status)
        assertTrue(response.body.contentEquals(data))
        assertEquals(100L, body.bytesRead)
    }

    @Test
    fun okHttpTransport_capBoundary() {
        val atCap = CountingBody(ByteArray(1024) { 1 })
        val atCapTransport = okHttpTransportWithBody(atCap, 1024)
        val okResponse = atCapTransport.httpPost("https://asr.example.com/x", emptyMap(), ByteArray(0))
        assertEquals(1024, okResponse.body.size)
        val overCap = CountingBody(ByteArray(1025) { 1 })
        val overCapTransport = okHttpTransportWithBody(overCap, 1024)
        try {
            overCapTransport.httpPost("https://asr.example.com/x", emptyMap(), ByteArray(0))
            fail("cap+1 must be rejected")
        } catch (e: NetworkException) {
            assertEquals("response_too_large", e.reason)
        }
    }

    // ---- 本批新增：连接时 SSRF 守卫（假 Dns，不依赖真实解析）----

    private fun staticDns(addresses: List<InetAddress>): Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = addresses
        }

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun ssrfDns_blocksPrivateOnlyResolution() {
        val guard = SsrfGuardDns(staticDns(listOf(addr("192.168.1.1"))))
        try {
            guard.lookup("asr.example.com")
            fail("private-only resolution must be blocked")
        } catch (e: UnknownHostException) {
            // 预期：滤完没有公网地址，拒绝。
        }
    }

    @Test
    fun ssrfDns_filtersMixedKeepsPublic() {
        val guard = SsrfGuardDns(staticDns(listOf(addr("10.0.0.5"), addr("8.8.8.8"))))
        assertEquals(listOf(addr("8.8.8.8")), guard.lookup("asr.example.com"))
    }

    @Test
    fun ssrfDns_allowsPrivateIpLiteral() {
        val guard = SsrfGuardDns(staticDns(listOf(addr("192.168.1.1"))))
        assertEquals(listOf(addr("192.168.1.1")), guard.lookup("192.168.1.1"))
        val guardV6 = SsrfGuardDns(staticDns(listOf(addr("::1"))))
        assertEquals(listOf(addr("::1")), guardV6.lookup("::1"))
        val guardLoopback = SsrfGuardDns(staticDns(listOf(addr("127.0.0.1"))))
        assertEquals(listOf(addr("127.0.0.1")), guardLoopback.lookup("127.0.0.1"))
    }

    @Test
    fun ssrfDns_allowsPublicAddresses() {
        val guard = SsrfGuardDns(staticDns(listOf(addr("93.184.216.34"))))
        assertEquals(listOf(addr("93.184.216.34")), guard.lookup("example.com"))
        val guardLiteral = SsrfGuardDns(staticDns(listOf(addr("8.8.8.8"))))
        assertEquals(listOf(addr("8.8.8.8")), guardLiteral.lookup("8.8.8.8"))
        val guardV6 = SsrfGuardDns(staticDns(listOf(addr("2606:4700:4700::1111"))))
        assertEquals(listOf(addr("2606:4700:4700::1111")), guardV6.lookup("2606:4700:4700::1111"))
    }

    @Test
    fun ssrfDns_blocksIpv6PrivateResolutions() {
        // 域名解析到私网 v6 地址（字面量例外只认主机串本身，不认解析结果）。
        val cases = listOf("::1", "fe80::1", "fd00::1", "fec0::1", "::ffff:192.168.1.1", "::")
        for (literal in cases) {
            val guard = SsrfGuardDns(staticDns(listOf(addr(literal))))
            try {
                guard.lookup("asr.example.com")
                fail("private v6 resolution must be blocked: $literal")
            } catch (e: UnknownHostException) {
                // 预期。
            }
        }
    }

    @Test
    fun defaultClient_usesSsrfGuardDns() {
        assertTrue(OkHttpNetworkTransport.defaultClient().dns is SsrfGuardDns)
    }
}
