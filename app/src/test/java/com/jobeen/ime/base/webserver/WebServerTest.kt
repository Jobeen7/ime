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
}
