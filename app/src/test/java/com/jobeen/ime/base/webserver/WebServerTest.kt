package com.jobeen.ime.base.webserver

import java.io.File
import java.net.Socket
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WO52 4.4：Socket 层回环实测（真实 ServerSocket＋真实路由，
 * 127.0.0.1 临时端口）：请求解析、登录、鉴权、静态页、文件流。
 */
class WebServerTest {

    private lateinit var tempRoot: File
    private lateinit var server: WebServer
    private var port: Int = -1

    private val passwordHash = WifiTransferAuth.hashPassword("secret123")

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("webserver-socket-test").toFile()
        File(tempRoot, "hello.txt").writeText("hello")
        val store = FileWebServerStore(tempRoot) { 10_000L }
        val actions = object : WebServerActions {
            override suspend fun deploy(): Boolean = true
            override suspend fun sync(): Boolean = true
            override fun applySkin(name: String, skinJson: String, manifestJson: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun switchUserDataDir(dir: String): SwitchUserDataResult =
                SwitchUserDataResult.OK

            override fun staticAsset(relativePath: String): ByteArray? =
                if (relativePath == "index.html") "<html>hi</html>".toByteArray(Charsets.UTF_8) else null
        }
        val router = WebServerRouter(store, actions, { passwordHash })
        server = WebServer(router, portRange = 0..0)
        port = server.start() ?: error("server did not start")
    }

    @After
    fun tearDown() {
        server.close()
        tempRoot.deleteRecursively()
    }

    private fun rawHttp(request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun loginToken(): String {
        val body = """{"password":"secret123"}"""
        val response = rawHttp(
            "POST /api/login HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n$body",
        )
        assertTrue(response.startsWith("HTTP/1.1 200"))
        val tokenJson = response.substringAfter("\r\n\r\n")
        return tokenJson.substringAfter("\"token\":\"").substringBefore("\"")
    }

    @Test
    fun `get root serves index over socket`() {
        val response = rawHttp("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("<html>hi</html>"))
    }

    @Test
    fun `login over socket issues token`() {
        assertTrue(loginToken().count { it == '.' } == 2)
    }

    @Test
    fun `authed usage over socket returns json`() {
        val token = loginToken()
        val response = rawHttp(
            "GET /api/usage HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n" +
                "Connection: close\r\n\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("\"used\""))
        assertTrue(response.contains("\"total\":10000"))
    }

    @Test
    fun `unauthed api over socket is 401`() {
        val response = rawHttp("GET /api/usage HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 401"))
    }

    @Test
    fun `file download streams bytes over socket`() {
        val token = loginToken()
        val response = rawHttp(
            "GET /api/resources?path=hello.txt HTTP/1.1\r\nHost: x\r\n" +
                "Authorization: Bearer $token\r\nConnection: close\r\n\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("application/octet-stream"))
        assertEquals("hello", response.substringAfter("\r\n\r\n"))
    }

    @Test
    fun `lan address formats http url`() {
        val address = WebServer.lanAddress(8080)
        assertTrue(address.startsWith("http://"))
        assertTrue(address.endsWith(":8080"))
    }

    // ---- A-25 先鉴权再读体、上限 16MB、流式落盘 ----

    @Test
    fun `unauthed oversize body is rejected 401 without reading body`() {
        val response = rawHttp(
            "POST /api/resources?path=big.bin HTTP/1.1\r\nHost: x\r\n" +
                "Content-Length: ${17 * 1024 * 1024}\r\nConnection: close\r\n\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 401"))
    }

    @Test
    fun `authed oversize body is rejected 413`() {
        val token = loginToken()
        val response = rawHttp(
            "PUT /api/resources?path=big.bin HTTP/1.1\r\nHost: x\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Content-Length: ${17 * 1024 * 1024}\r\nConnection: close\r\n\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 413"))
    }

    @Test
    fun `tus patch streams chunk to disk over socket`() {
        val token = loginToken()
        val created = rawHttp(
            "POST /api/tus?path=tus.bin&override=true HTTP/1.1\r\nHost: x\r\n" +
                "Authorization: Bearer $token\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )
        assertTrue(created.startsWith("HTTP/1.1 201"))
        val location = created.lines().first { it.startsWith("Location:") }.substringAfter("Location:").trim()
        val chunk = ByteArray(100 * 1024) { 9 }
        val patched = rawHttpBytes(
            "PATCH $location HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n" +
                "Content-Type: application/offset+octet-stream\r\nUpload-Offset: 0\r\n" +
                "Content-Length: ${chunk.size}\r\nConnection: close\r\n\r\n",
            chunk,
        ).toString(Charsets.UTF_8)
        assertTrue(patched.startsWith("HTTP/1.1 204"))
        assertTrue(patched.contains("Upload-Offset: 102400"))
        assertEquals(102400L, File(tempRoot, "tus.bin").length())
        assertEquals(9, File(tempRoot, "tus.bin").readBytes()[512].toInt())
    }

    @Test
    fun `put streams file to disk over socket`() {
        val token = loginToken()
        val body = ByteArray(64 * 1024) { 5 }
        val response = rawHttpBytes(
            "PUT /api/resources?path=put.bin HTTP/1.1\r\nHost: x\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n",
            body,
        ).toString(Charsets.UTF_8)
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertEquals(65536L, File(tempRoot, "put.bin").length())
        assertEquals(5, File(tempRoot, "put.bin").readBytes()[1000].toInt())
    }

    // ---- A-26 并发上限与慢连接断开 ----

    @Test
    fun `seventeenth concurrent connection is refused`() {
        val held = mutableListOf<Socket>()
        try {
            repeat(16) {
                Socket("127.0.0.1", port).also { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n".toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    held.add(socket)
                }
            }
            Socket("127.0.0.1", port).use { extra ->
                extra.soTimeout = 5_000
                extra.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(Charsets.UTF_8))
                extra.getOutputStream().flush()
                assertEquals(-1, extra.getInputStream().read())
            }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `slow header connection is closed after timeout`() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 20_000
            val start = System.currentTimeMillis()
            assertEquals(-1, socket.getInputStream().read())
            val elapsed = System.currentTimeMillis() - start
            assertTrue(elapsed >= 14_000L)
            assertTrue(elapsed < 20_000L)
        }
    }

    private fun rawHttpBytes(headers: String, body: ByteArray): ByteArray {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(headers.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes()
        }
    }
}
