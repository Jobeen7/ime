package com.jobeen.ime.base.webserver

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wi-Fi 传输 HTTP 层（WO52 4.4）：自研极简层（ServerSocket）。
 * 本地镜像与依赖中均无 NanoHTTPD／Ktor，故不引入外部依赖。
 *
 * - 仅支持 Content-Length 请求体（tus 分片与文件上传均带长度）；
 *   Transfer-Encoding: chunked 不支持（400）。
 * - 每连接只处理一个请求后关闭（Connection: close），不做 keep-alive。
 * - 请求体上限 16MB：先鉴权再读体，体分块流式落临时文件（不整块进堆），
 *   超限即 413 断开；TUS 分片与 PUT 写盘直接走临时文件边读边写。
 * - 慢连接 15 秒断开（soTimeout），并发连接上限 16。
 * - 局域网地址取首个非回环 IPv4（无需额外权限）。
 */
class WebServer(
    private val router: WebServerRouter,
    private val portRange: IntRange = 8080..8090,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 每处理一个请求调一次（服务层用它刷新“最后请求时间”，B-W-6 空闲自停）。 */
    private val onRequest: () -> Unit = {},
) {
    @Volatile
    var boundPort: Int? = null
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connectionPermits = Semaphore(MAX_CONNECTIONS)

    /** 依次尝试端口段，返回绑定的端口；全被占返回 null。 */
    fun start(): Int? {
        for (port in portRange) {
            try {
                val socket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket = socket
                // 端口 0 为临时端口：取系统实际分配的端口号。
                val bound = socket.localPort
                boundPort = bound
                acceptJob = scope.launch { acceptLoop(socket) }
                return bound
            } catch (_: IOException) {
                // 端口被占，试下一个。
            }
        }
        return null
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive) {
            val client = withContext(Dispatchers.IO) {
                runCatching { socket.accept() }.getOrNull()
            } ?: break
            runCatching { client.soTimeout = SO_TIMEOUT_MS }
            if (!connectionPermits.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            scope.launch {
                try {
                    handleConnection(client)
                } finally {
                    connectionPermits.release()
                }
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { client ->
            var bodyFile: File? = null
            try {
                onRequest()
                val outcome = withContext(Dispatchers.IO) {
                    parseRequest(
                        client.getInputStream(), client.getOutputStream(),
                        client.inetAddress?.hostAddress.orEmpty(),
                    )
                }
                val request = (outcome as? ParseOutcome.Ok)?.request
                bodyFile = request?.bodyFile
                val response = when (outcome) {
                    is ParseOutcome.Ok -> runCatching { router.route(request!!) }
                        .getOrElse {
                            Timber.w(it, "WebServer route failed")
                            HttpResponse(500, body = "internal error".toByteArray(Charsets.UTF_8))
                        }
                    is ParseOutcome.Reject -> HttpResponse(
                        outcome.status,
                        body = "rejected".toByteArray(Charsets.UTF_8),
                    )
                    ParseOutcome.BadRequest -> HttpResponse(
                        400,
                        body = "bad request".toByteArray(Charsets.UTF_8),
                    )
                }
                withContext(Dispatchers.IO) {
                    writeResponse(client.getOutputStream(), request?.method ?: "GET", response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OOM 等 Error 也只关本连接，不断服务。
                Timber.w(e, "WebServer connection failed")
            } finally {
                bodyFile?.let { runCatching { it.delete() } }
            }
        }
    }

    /** 请求头解析结果（读体前）。 */
    private data class RequestHead(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val contentLength: Int,
    )

    private sealed interface ParseOutcome {
        data class Ok(val request: HttpRequest) : ParseOutcome
        data class Reject(val status: Int) : ParseOutcome
        data object BadRequest : ParseOutcome
    }

    private fun parseRequest(input: InputStream, output: OutputStream, remoteIp: String): ParseOutcome {
        val head = parseHead(input) ?: return ParseOutcome.BadRequest
        // 先鉴权再读体：公开路径放行，其余无有效 Bearer 直接 401，不读体。
        if (!router.preAuth(head.method, head.path, head.headers)) {
            return ParseOutcome.Reject(401)
        }
        if (head.contentLength > MAX_BODY_BYTES) return ParseOutcome.Reject(413)
        if (head.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            // 客户端（curl 大文件上传）等 100 Continue 才发体：
            // 先回 interim 响应再读体，否则死锁。
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
        val bodyFile = readBodyToFile(input, head.contentLength) ?: return ParseOutcome.BadRequest
        val streamBody = head.method == "PATCH" && head.path.startsWith("/api/tus/") ||
            head.method == "PUT" && head.path == "/api/resources"
        return if (streamBody) {
            ParseOutcome.Ok(
                HttpRequest(
                    head.method, head.path, head.query, head.headers,
                    ByteArray(0), bodyFile = bodyFile, remoteAddress = remoteIp,
                ),
            )
        } else {
            val body = runCatching { bodyFile.readBytes() }.getOrNull()
                ?: return ParseOutcome.BadRequest
            runCatching { bodyFile.delete() }
            ParseOutcome.Ok(
                HttpRequest(
                    head.method, head.path, head.query, head.headers,
                    body, remoteAddress = remoteIp,
                ),
            )
        }
    }

    /** 分块读体到临时文件，不整块进堆；读满失败删临时文件返回 null。 */
    private fun readBodyToFile(input: InputStream, contentLength: Int): File? {
        val tmp = File.createTempFile("web-body-", ".tmp")
        try {
            tmp.outputStream().use { out ->
                var remaining = contentLength
                val buf = ByteArray(BUFFER_SIZE)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, remaining))
                    if (n <= 0) {
                        tmp.delete()
                        return null
                    }
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            return tmp
        } catch (_: IOException) {
            tmp.delete()
            return null
        }
    }

    /** 只解析请求头，不读体。 */
    private fun parseHead(input: InputStream): RequestHead? {
        val headerBytes = readUntilDoubleCrlf(input) ?: return null
        val headerText = String(headerBytes, Charsets.UTF_8)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 3) return null
        val method = requestLine[0].uppercase()
        val rawTarget = requestLine[1]
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] =
                line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) return null
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        // 超限不在这里拒：parseRequest 先跑 preAuth（无鉴权 401），
        // 再按 contentLength 返 413——顺序错了 401 会变 400（A-25 回归）。
        if (contentLength < 0) return null
        val (rawPath, rawQuery) = rawTarget.split("?", limit = 2).let {
            it[0] to (it.getOrNull(1).orEmpty())
        }
        val path = decodePath(rawPath)
        val query = parseQuery(rawQuery)
        return RequestHead(method, path, query, headers, contentLength)
    }

    private fun readUntilDoubleCrlf(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val window = ArrayDeque<Byte>()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            total++
            if (total > MAX_HEADER_BYTES) return null
            out.write(b)
            window.addLast(b.toByte())
            if (window.size > 4) window.removeFirst()
            if (window.size == 4 &&
                window[0] == '\r'.code.toByte() && window[1] == '\n'.code.toByte() &&
                window[2] == '\r'.code.toByte() && window[3] == '\n'.code.toByte()
            ) {
                return out.toByteArray()
            }
        }
    }

    private fun writeResponse(output: OutputStream, method: String, response: HttpResponse) {
        val bodyLength = response.bodyFile?.let { response.bodyFileLength } ?: response.body.size.toLong()
        val header = StringBuilder()
        header.append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        for ((key, value) in response.headers) {
            header.append("$key: $value\r\n")
        }
        header.append("Content-Length: $bodyLength\r\n")
        header.append("Connection: close\r\n")
        header.append("\r\n")
        output.write(header.toString().toByteArray(Charsets.UTF_8))
        if (method != "HEAD") {
            val file = response.bodyFile
            if (file != null) {
                file.inputStream().use { it.copyTo(output, BUFFER_SIZE) }
            } else {
                output.write(response.body)
            }
        }
        output.flush()
    }

    private fun decodePath(rawPath: String): String =
        runCatching {
            URLDecoder.decode(rawPath.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(rawPath)

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            map[urlDecode(key)] = urlDecode(value)
        }
        return map
    }

    private fun urlDecode(value: String): String =
        runCatching {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> ""
    }

    companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
        const val SO_TIMEOUT_MS = 15_000
        const val MAX_CONNECTIONS = 16
        private const val BUFFER_SIZE = 8192

        /** 首个非回环 IPv4 局域网地址；无则回落 127.0.0.1。 */
        fun lanAddress(port: Int): String {
            val ip = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.hostAddress
            }.getOrNull()
            return "http://${ip ?: "127.0.0.1"}:$port"
        }
    }
}
