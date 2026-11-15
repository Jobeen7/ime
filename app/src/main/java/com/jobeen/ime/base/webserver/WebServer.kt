package com.jobeen.ime.base.webserver

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wi-Fi 传输 HTTP 层（WO52 4.4）：自研极简层（ServerSocket）。
 * 本地镜像与依赖中均无 NanoHTTPD／Ktor，故不引入外部依赖。
 *
 * - 仅支持 Content-Length 请求体（tus 分片与文件上传均带长度）；
 *   Transfer-Encoding: chunked 不支持（400）。
 * - 每连接只处理一个请求后关闭（Connection: close），不做 keep-alive。
 * - 请求体上限 512MB；响应文件走流式写盘，不经内存。
 * - 局域网地址取首个非回环 IPv4（无需额外权限）。
 */
class WebServer(
    private val router: WebServerRouter,
    private val portRange: IntRange = 8080..8090,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile
    var boundPort: Int? = null
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

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
            scope.launch { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { client ->
            try {
                val request = withContext(Dispatchers.IO) {
                    parseRequest(client.getInputStream(), client.getOutputStream())
                }
                val response = if (request == null) {
                    HttpResponse(400, body = "bad request".toByteArray(Charsets.UTF_8))
                } else {
                    runCatching { router.route(request) }
                        .getOrElse {
                            Timber.w(it, "WebServer route failed")
                            HttpResponse(500, body = "internal error".toByteArray(Charsets.UTF_8))
                        }
                }
                withContext(Dispatchers.IO) {
                    writeResponse(client.getOutputStream(), request?.method ?: "GET", response)
                }
            } catch (e: Exception) {
                Timber.w(e, "WebServer connection failed")
            }
        }
    }

    private fun parseRequest(input: InputStream, output: OutputStream): HttpRequest? {
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
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            // 客户端（curl 大文件上传）等 100 Continue 才发体：
            // 先回 interim 响应再读体，否则死锁。
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n <= 0) return null
                read += n
            }
            bytes
        } else {
            ByteArray(0)
        }
        val (rawPath, rawQuery) = rawTarget.split("?", limit = 2).let {
            it[0] to (it.getOrNull(1).orEmpty())
        }
        val path = decodePath(rawPath)
        val query = parseQuery(rawQuery)
        return HttpRequest(method, path, query, headers, body)
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
        415 -> "Unsupported Media Type"
        500 -> "Internal Server Error"
        else -> ""
    }

    companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_BODY_BYTES = 512 * 1024 * 1024
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
