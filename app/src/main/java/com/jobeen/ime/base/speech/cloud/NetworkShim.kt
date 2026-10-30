package com.jobeen.ime.base.speech.cloud

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * 网络传输抽象：生产用 OkHttp，单测用内存假实现。
 * 传输层只负责发收，不做任何授权判定（判定全在 [NetworkShim]）。
 */
interface NetworkTransport {
    fun httpPost(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse
    fun websocket(url: String, headers: Map<String, String>, listener: WsListener): WsHandle

    interface WsListener {
        fun onMessage(text: String?, bytes: ByteArray?)
        fun onClosed()
        fun onFailure()
    }

    interface WsHandle {
        fun send(text: String): Boolean
        fun sendBytes(bytes: ByteArray): Boolean
        fun close()
    }
}

data class HttpResponse(val status: Int, val body: ByteArray)

/** 网络失败（原因码，不含请求体、响应体与任何秘密）。 */
class NetworkException(val reason: String) : RuntimeException()

class OkHttpNetworkTransport(
    private val client: OkHttpClient = defaultClient(),
) : NetworkTransport {

    override fun httpPost(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(null))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return HttpResponse(response.code, bytes)
        }
    }

    override fun websocket(
        url: String,
        headers: Map<String, String>,
        listener: NetworkTransport.WsListener,
    ): NetworkTransport.WsHandle {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        var socket: WebSocket? = null
        val wsListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text, null)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                listener.onMessage(null, bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                listener.onFailure()
            }
        }
        socket = client.newWebSocket(request, wsListener)
        return object : NetworkTransport.WsHandle {
            override fun send(text: String): Boolean = socket?.send(text) ?: false
            override fun sendBytes(bytes: ByteArray): Boolean =
                socket?.send(bytes.toByteString()) ?: false

            override fun close() {
                socket?.close(1000, null)
            }
        }
    }

    companion object {
        /**
         * 默认客户端：无 CookieJar（绝不持久化 Cookie）、无缓存、
         * 连接 15s／读取 30s（与导入 URL 限时同口径）。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 宿主侧网络垫片：脚本不见原始 socket，一切请求必经此口。
 *
 * 校验（任一项不过即拒，绝不发请求）：
 * - scheme 只认 https（POST）／wss（WebSocket）；
 * - URL 不得带 userinfo，不得带非默认端口；
 * - host 必须在插件 hosts 表内精确命中（无通配、无隐含子域）；
 * - 脚本自带头剥离禁转头（含 Authorization、Cookie——凭证只走声明代附）；
 * - 声明代附头由宿主用密钥组装，缺密钥即拒；
 * - 请求体 ≤1MB、响应体 ≤5MB、单条 WS 发送 ≤256KB。
 */
class NetworkShim(
    private val allowedHosts: Set<String>,
    private val authHeaders: List<AuthHeaderDecl>,
    private val secretResolver: (field: String) -> String?,
    private val transport: NetworkTransport,
) {
    /** 脚本自带头禁转表（小写比对）。 */
    private val forbiddenHeaders = setOf(
        "host", "content-length", "content-type", "connection",
        "transfer-encoding", "proxy-authorization", "upgrade",
        "cookie", "set-cookie", "authorization",
    )

    private val contentTypeRegex =
        Regex("^[A-Za-z0-9!#\$&^_.+-]+/[A-Za-z0-9!#\$&^_.+-]+$")

    fun httpPost(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentType: String?,
    ): HttpResponse {
        val checked = checkUrl(url, "https")
        if (body.size > MAX_REQUEST_BODY) throw NetworkException("body_too_large")
        val clean = stripForbidden(headers)
        val withAuth = clean + resolveAuth()
        val finalHeaders = if (contentType != null) {
            if (!contentTypeRegex.matches(contentType)) throw NetworkException("bad_content_type")
            withAuth + ("Content-Type" to contentType)
        } else {
            withAuth
        }
        val response = transport.httpPost(checked, finalHeaders, body)
        if (response.status !in 200..299) throw NetworkException("http_status")
        if (response.body.size > MAX_RESPONSE_BODY) throw NetworkException("response_too_large")
        return response
    }

    fun websocket(
        url: String,
        headers: Map<String, String>,
        listener: NetworkTransport.WsListener,
    ): NetworkTransport.WsHandle {
        val checked = checkUrl(url, "wss")
        val clean = stripForbidden(headers)
        val withAuth = clean + resolveAuth()
        return transport.websocket(checked, withAuth, listener)
    }

    fun wsSend(handle: NetworkTransport.WsHandle, text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_WS_MESSAGE) return false
        return handle.send(text)
    }

    fun wsSendBytes(handle: NetworkTransport.WsHandle, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_WS_MESSAGE) return false
        return handle.sendBytes(bytes)
    }

    private fun checkUrl(url: String, scheme: String): String {
        // 注意：OkHttp 的 HttpUrl 只认 http/https，wss 解析返 null，
        // 此处若沿用它则所有 WebSocket 插件永远连不上（本轮单测捕获）。
        // 改用 java.net.URI 校验，规则不变：方案精确匹配、禁 userinfo、
        // 禁非默认端口、域名精确匹配（小写比对）。
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
            ?: throw NetworkException("bad_url")
        val actualScheme = parsed.scheme?.lowercase()
            ?: throw NetworkException("bad_url")
        if (actualScheme != scheme) throw NetworkException("bad_scheme")
        if (parsed.userInfo != null) throw NetworkException("userinfo_rejected")
        val host = parsed.host?.lowercase()
            ?: throw NetworkException("bad_url")
        val defaultPort = if (scheme == "http") 80 else 443
        val port = if (parsed.port == -1) defaultPort else parsed.port
        if (port != defaultPort) throw NetworkException("port_rejected")
        if (host !in allowedHosts) throw NetworkException("host_not_allowed")
        return url
    }

    private fun stripForbidden(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            if (k.lowercase() in forbiddenHeaders) continue
            if (v.length > MAX_HEADER_VALUE) continue
            out[k] = v
        }
        return out
    }

    private fun resolveAuth(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (decl in authHeaders) {
            val secret = secretResolver(decl.field)
                ?: throw NetworkException("secret_missing")
            out[decl.header] = decl.prefix + secret
        }
        return out
    }

    companion object {
        const val MAX_REQUEST_BODY = 1024 * 1024
        const val MAX_RESPONSE_BODY = 5 * 1024 * 1024
        const val MAX_WS_MESSAGE = 256 * 1024
        const val MAX_HEADER_VALUE = 4096
    }
}
