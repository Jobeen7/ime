package com.jobeen.ime.base.speech.cloud

import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
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

/**
 * 可取消的传输：实现者须保证从任意线程调 [cancelInflight] 能立即
 * 中断在途 HTTP 调用（关闭 socket），使等待线程以失败返回。
 * 不实现者（内存假传输）取消即空操作（调用方以 as? 取，绝不强转）。
 */
interface CancellableTransport {
    fun cancelInflight()
}

class OkHttpNetworkTransport(
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Long = NetworkShim.MAX_RESPONSE_BODY.toLong(),
) : NetworkTransport, CancellableTransport {

    /** 在途 HTTP 调用（供 kill／stop 从调用线程取消，Call.cancel 线程安全）。 */
    private val inFlightCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    override fun httpPost(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(null))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val call = client.newCall(request)
        inFlightCalls.add(call)
        try {
            call.execute().use { response ->
                val responseBody = response.body
                    ?: return HttpResponse(response.code, ByteArray(0))
                // 流式读、逐块计数：超限立即抛弃，不先把整包读进内存。
                // 垫片层的上限检查仍保留（假传输与防御纵深），此处是第一道。
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                responseBody.byteStream().use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxResponseBytes) {
                            throw NetworkException("response_too_large")
                        }
                        out.write(buffer, 0, read)
                    }
                }
                return HttpResponse(response.code, out.toByteArray())
            }
        } finally {
            inFlightCalls.remove(call)
        }
    }

    override fun cancelInflight() {
        val snapshot: List<Call> = synchronized(inFlightCalls) { inFlightCalls.toList() }
        snapshot.forEach { call -> runCatching { call.cancel() } }
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
         * 连接 15s／读取 30s（与导入 URL 限时同口径）、连接时 SSRF
         * 守卫（见 [SsrfGuardDns]，HTTP 与 WebSocket 共用此客户端，
         * 故两路同受守卫）。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .cache(null)
            .dns(SsrfGuardDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 连接时 SSRF 守卫：OkHttp 在建立连接时调 [Dns.lookup]，校验发生在
 * 连接前、解析结果直接用于连接，堵住“先验主机名、后解析 IP”的
 * DNS 重绑定时间差（垫片的白名单只验主机名字符串，见 [NetworkShim]）。
 *
 * 规则：主机本身就是私网 IP 字面量时放行——它已通过垫片白名单
 * （声明＋用户确认），放行即用户本意；否则（DNS 名）过滤掉解析
 * 结果里的私网／回环／链路本地地址，滤完没有就拒（抛
 * UnknownHostException，调用方按网络失败处理）。公网地址原样放行，
 * 混合解析只留公网部分。HTTP 与 WebSocket 共用默认客户端，故两路同受守卫。
 */
class SsrfGuardDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (isPrivateIpLiteral(hostname)) return addresses
        val public = addresses.filterNot { isPrivateOrReservedAddress(it) }
        if (public.isEmpty()) {
            Timber.w("cloudVoice ssrf blocked host=%s", hostname)
            throw UnknownHostException("private_address_blocked: $hostname")
        }
        return public
    }
}

/** 主机串本身就是私网 IP 字面量（已声明＋已确认，放行即用户本意）。 */
private fun isPrivateIpLiteral(host: String): Boolean {
    val v4 = host.split(".")
    if (v4.size == 4) {
        val bytes = IntArray(4)
        for (i in 0..3) {
            val part = v4[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return false
            val value = part.toIntOrNull() ?: return false
            if (value > 255) return false
            bytes[i] = value
        }
        return isPrivateIpv4(bytes[0], bytes[1], bytes[2], bytes[3])
    }
    // 冒号只可能来自 IPv6 字面量（DNS 名不可能含冒号），解析后判定；
    // 解析失败按名字处理，后续过滤仍会拦住私网解析结果（失败关闭）。
    if (':' in host) {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return isPrivateOrReservedAddress(addr)
    }
    return false
}

/** 解析出的地址是否属于私网／回环／链路本地／保留段（应被过滤）。 */
private fun isPrivateOrReservedAddress(address: InetAddress): Boolean {
    val bytes = address.address
    if (bytes.size == 4) {
        return isPrivateIpv4(
            bytes[0].toInt() and 0xFF,
            bytes[1].toInt() and 0xFF,
            bytes[2].toInt() and 0xFF,
            bytes[3].toInt() and 0xFF,
        )
    }
    if (bytes.size == 16) {
        // IPv4 映射的 IPv6（::ffff:a.b.c.d）：按内嵌 IPv4 判定。
        if (bytes.slice(0..9).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        ) {
            return isPrivateIpv4(
                bytes[12].toInt() and 0xFF,
                bytes[13].toInt() and 0xFF,
                bytes[14].toInt() and 0xFF,
                bytes[15].toInt() and 0xFF,
            )
        }
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        if (first == 0xFC || first == 0xFD) return true // fc00::/7 唯一本地
        if (first == 0xFE && (second and 0xC0) == 0xC0) return true // fec0::/10 站本地（已弃用）
        if (bytes.all { it == 0.toByte() }) return true // :: 未指定
        return address.isSiteLocalAddress // 兜底（含实现相关的站本地判定）
    }
    return false
}

/** IPv4 私网／回环／链路本地／未指定段（任务口径＋ 0.0.0.0/8）。 */
private fun isPrivateIpv4(a: Int, b: Int, c: Int, d: Int): Boolean {
    return a == 10 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        a == 127 ||
        (a == 169 && b == 254) ||
        a == 0
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
