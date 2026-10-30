// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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
}
