package com.jobeen.ime.base.webserver

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WO52 4.4：路由逐项单测（22 条＋鉴权＋tus＋受保护路径）。
 * 存储走真实磁盘（FileWebServerStore＋临时目录），actions 用 fake；
 * 服务层（Socket／前台服务／设置页）不在主机单测覆盖内。
 */
class WebServerRouterTest {

    private class FakeActions : WebServerActions {
        var deployResult = true
        var syncResult = true
        var switchResult = SwitchUserDataResult.OK
        var lastSwitchDir: String? = null
        var lastSkinApply: Triple<String, String, String>? = null
        var skinApplyResult: Result<Unit> = Result.success(Unit)

        override suspend fun deploy(): Boolean = deployResult
        override suspend fun sync(): Boolean = syncResult
        override fun applySkin(name: String, skinJson: String, manifestJson: String): Result<Unit> {
            lastSkinApply = Triple(name, skinJson, manifestJson)
            return skinApplyResult
        }

        override suspend fun switchUserDataDir(dir: String): SwitchUserDataResult {
            lastSwitchDir = dir
            return switchResult
        }

        override fun staticAsset(relativePath: String): ByteArray? =
            if (relativePath == "index.html") "<html>hi</html>".toByteArray(Charsets.UTF_8) else null
    }

    private lateinit var tempRoot: File
    private lateinit var store: FileWebServerStore
    private lateinit var actions: FakeActions
    private lateinit var router: WebServerRouter

    private val password = "secret123"
    private val passwordHash = WifiTransferAuth.hashPassword(password)

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("webserver-test").toFile()
        store = FileWebServerStore(tempRoot) { 10_000L }
        actions = FakeActions()
        router = WebServerRouter(store, actions, { passwordHash })
        // 种子：普通文件、子目录、受保护子树。
        File(tempRoot, "hello.txt").writeText("hello")
        File(tempRoot, "sub").mkdirs()
        File(tempRoot, "sub/inner.txt").writeText("inner")
        File(tempRoot, "user/dir1").mkdirs()
        File(tempRoot, "user/dir1/a.yaml").writeText("x")
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun authed(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): HttpResponse = runBlocking {
        val token = WifiTransferAuth.issueToken(passwordHash)
        router.route(
            HttpRequest(
                method, path, query,
                headers + ("Authorization" to "Bearer $token"),
                body,
            ),
        )
    }

    private fun bodyText(response: HttpResponse): String =
        String(response.body, Charsets.UTF_8)

    private fun bodyJson(response: HttpResponse) =
        Json.parseToJsonElement(bodyText(response)).jsonObject

    // ---- 登录／续期／鉴权 ----

    @Test
    fun `login with correct password issues token`() = runBlocking {
        val response = router.route(
            HttpRequest(
                "POST", "/api/login", emptyMap(), emptyMap(),
                """{"password":"$password"}""".toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals(200, response.status)
        val token = bodyJson(response)["token"]!!.jsonPrimitive.content
        assertTrue(WifiTransferAuth.verifyToken(passwordHash, token))
    }

    @Test
    fun `login with wrong password is rejected`() = runBlocking {
        val response = router.route(
            HttpRequest(
                "POST", "/api/login", emptyMap(), emptyMap(),
                """{"password":"nope"}""".toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals(401, response.status)
    }

    @Test
    fun `login without password field is bad request`() = runBlocking {
        val response = router.route(
            HttpRequest(
                "POST", "/api/login", emptyMap(), emptyMap(),
                """{}""".toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals(400, response.status)
    }

    @Test
    fun `login without password set is forbidden`() = runBlocking {
        val noPasswordRouter = WebServerRouter(store, actions, { null })
        val response = noPasswordRouter.route(
            HttpRequest(
                "POST", "/api/login", emptyMap(), emptyMap(),
                """{"password":"$password"}""".toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals(403, response.status)
    }

    @Test
    fun `renew with valid token issues fresh token`() = runBlocking {
        val token = WifiTransferAuth.issueToken(passwordHash)
        val response = router.route(
            HttpRequest(
                "POST", "/api/renew", emptyMap(),
                mapOf("Authorization" to "Bearer $token"), ByteArray(0),
            ),
        )
        assertEquals(200, response.status)
        assertTrue(bodyJson(response).containsKey("token"))
    }

    @Test
    fun `renew with invalid token is rejected`() = runBlocking {
        val response = router.route(
            HttpRequest(
                "POST", "/api/renew", emptyMap(),
                mapOf("Authorization" to "Bearer bad.token.here"), ByteArray(0),
            ),
        )
        assertEquals(401, response.status)
    }

    @Test
    fun `api without token is unauthorized`() = runBlocking {
        val response = router.route(
            HttpRequest("GET", "/api/usage", emptyMap(), emptyMap(), ByteArray(0)),
        )
        assertEquals(401, response.status)
    }

    @Test
    fun `api with expired token is unauthorized`() = runBlocking {
        val token = WifiTransferAuth.issueToken(passwordHash, nowMs = 1_000L)
        val expiredRouter = WebServerRouter(
            store, actions, { passwordHash },
            nowMs = { 1_000L + 25L * 3600L * 1000L },
        )
        val response = expiredRouter.route(
            HttpRequest(
                "GET", "/api/usage", emptyMap(),
                mapOf("Authorization" to "Bearer $token"), ByteArray(0),
            ),
        )
        assertEquals(401, response.status)
    }

    // ---- 皮肤／方案数据／部署／同步／用量 ----

    @Test
    fun `capabilities reports no jsonnet`() {
        val response = authed("POST", "/api/skins/capabilities")
        assertEquals(200, response.status)
        assertEquals(false, bodyJson(response)["canRunMainJsonnet"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `skins apply passes skin and manifest through`() {
        val body = """{"name":"bin","skin":{"formatVersion":3},"manifest":{"name":"bin"}}"""
            .toByteArray(Charsets.UTF_8)
        val response = authed("POST", "/api/skins/apply", body = body)
        assertEquals(200, response.status)
        assertEquals("bin", actions.lastSkinApply?.first)
        assertTrue(actions.lastSkinApply?.second?.contains("formatVersion") == true)
    }

    @Test
    fun `skins apply rejects bad name`() {
        val body = """{"name":"../x","skin":{},"manifest":{}}""".toByteArray(Charsets.UTF_8)
        assertEquals(400, authed("POST", "/api/skins/apply", body = body).status)
    }

    @Test
    fun `skins apply rejects missing manifest`() {
        val body = """{"name":"bin","skin":{}}""".toByteArray(Charsets.UTF_8)
        assertEquals(400, authed("POST", "/api/skins/apply", body = body).status)
    }

    @Test
    fun `rime user data apply switches dir`() {
        val body = """{"dir":"default"}""".toByteArray(Charsets.UTF_8)
        assertEquals(200, authed("POST", "/api/rime-user-data/apply", body = body).status)
        assertEquals("default", actions.lastSwitchDir)
    }

    @Test
    fun `rime user data apply reports invalid dir`() {
        actions.switchResult = SwitchUserDataResult.INVALID_DIR
        val body = """{"dir":".."}""".toByteArray(Charsets.UTF_8)
        assertEquals(400, authed("POST", "/api/rime-user-data/apply", body = body).status)
    }

    @Test
    fun `rime deploy success and failure`() {
        assertEquals(200, authed("POST", "/api/rime/deploy").status)
        assertTrue(bodyText(authed("POST", "/api/rime/deploy")).contains("部署成功"))
        actions.deployResult = false
        val failed = authed("POST", "/api/rime/deploy")
        assertEquals(500, failed.status)
        assertTrue(bodyText(failed).contains("部署失败"))
    }

    @Test
    fun `rime sync success and failure`() {
        assertEquals(200, authed("POST", "/api/rime/sync").status)
        assertTrue(bodyText(authed("POST", "/api/rime/sync")).contains("同步成功"))
        actions.syncResult = false
        assertEquals(500, authed("POST", "/api/rime/sync").status)
    }

    @Test
    fun `usage returns used and total`() {
        val response = authed("GET", "/api/usage")
        assertEquals(200, response.status)
        val json = bodyJson(response)
        assertTrue(json["used"]!!.jsonPrimitive.content.toLong() > 0)
        assertEquals(10_000L, json["total"]!!.jsonPrimitive.content.toLong())
    }

    // ---- /api/resources ----

    @Test
    fun `resources get dir lists entries with sorting desc`() {
        val response = authed("GET", "/api/resources", query = mapOf("path" to ""))
        assertEquals(200, response.status)
        val json = bodyJson(response)
        assertEquals(2, json["numDirs"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["numFiles"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, json["sorting"]!!.jsonObject["asc"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `resources get file streams octet-stream`() {
        val response = authed("GET", "/api/resources", query = mapOf("path" to "hello.txt"))
        assertEquals(200, response.status)
        assertEquals("application/octet-stream", response.headers["Content-Type"])
        assertEquals("hello", response.bodyFile?.readText())
    }

    @Test
    fun `resources get missing is 500`() {
        assertEquals(500, authed("GET", "/api/resources", query = mapOf("path" to "nope")).status)
    }

    @Test
    fun `resources get traversal is rejected`() {
        assertEquals(
            500,
            authed("GET", "/api/resources", query = mapOf("path" to "../etc")).status,
        )
    }

    @Test
    fun `resources post creates file and conflicts without override`() {
        val query = mapOf("path" to "new.txt")
        assertEquals(200, authed("POST", "/api/resources", query = query).status)
        assertEquals(409, authed("POST", "/api/resources", query = query).status)
        val override = mapOf("path" to "new.txt", "override" to "true")
        assertEquals(200, authed("POST", "/api/resources", query = override).status)
    }

    @Test
    fun `resources post trailing slash creates dir`() {
        assertEquals(
            200,
            authed("POST", "/api/resources", query = mapOf("path" to "brand/")).status,
        )
        assertTrue(File(tempRoot, "brand").isDirectory)
    }

    @Test
    fun `resources put writes bytes`() {
        val query = mapOf("path" to "sub/written.txt")
        val response = authed(
            "PUT", "/api/resources", query = query,
            body = "data".toByteArray(Charsets.UTF_8),
        )
        assertEquals(200, response.status)
        assertEquals("data", File(tempRoot, "sub/written.txt").readText())
    }

    @Test
    fun `resources patch moves and copies`() {
        val move = mapOf("path" to "hello.txt", "to" to "sub/hello.txt")
        assertEquals(200, authed("PATCH", "/api/resources", query = move).status)
        assertTrue(File(tempRoot, "sub/hello.txt").exists())
        assertTrue(!File(tempRoot, "hello.txt").exists())
        val copy = mapOf("path" to "sub/hello.txt", "to" to "copy.txt", "copy" to "true")
        assertEquals(200, authed("PATCH", "/api/resources", query = copy).status)
        assertTrue(File(tempRoot, "sub/hello.txt").exists())
        assertTrue(File(tempRoot, "copy.txt").exists())
    }

    @Test
    fun `resources delete root is 400 protected is 403 normal is 200`() {
        assertEquals(
            400,
            authed("DELETE", "/api/resources", query = mapOf("path" to "/")).status,
        )
        assertEquals(
            403,
            authed("DELETE", "/api/resources", query = mapOf("path" to "user/dir1")).status,
        )
        assertEquals(
            403,
            authed("DELETE", "/api/resources", query = mapOf("path" to "user")).status,
        )
        assertEquals(
            200,
            authed("DELETE", "/api/resources", query = mapOf("path" to "hello.txt")).status,
        )
        assertTrue(!File(tempRoot, "hello.txt").exists())
        assertEquals(
            404,
            authed("DELETE", "/api/resources", query = mapOf("path" to "gone.txt")).status,
        )
    }

    @Test
    fun `resources recursive lists nested files`() {
        val response = authed(
            "GET", "/api/resources/recursive", query = mapOf("path" to "sub"),
        )
        assertEquals(200, response.status)
        assertTrue(bodyText(response).contains("inner.txt"))
    }

    // ---- tus ----

    @Test
    fun `tus full flow creates appends and reports offset`() {
        val create = authed(
            "POST", "/api/tus", query = mapOf("path" to "up.bin"),
        )
        assertEquals(201, create.status)
        val location = create.headers["Location"]!!
        assertTrue(location.startsWith("/api/tus/"))
        assertEquals("1.0.0", create.headers["Tus-Resumable"])
        assertEquals("0", create.headers["Upload-Offset"])

        val patchHeaders = mapOf(
            "Content-Type" to "application/offset+octet-stream",
            "Upload-Offset" to "0",
        )
        val patched = authed(
            "PATCH", location, headers = patchHeaders,
            body = "abcd".toByteArray(Charsets.UTF_8),
        )
        assertEquals(204, patched.status)
        assertEquals("4", patched.headers["Upload-Offset"])

        val status = authed("HEAD", location)
        assertEquals(200, status.status)
        assertEquals("4", status.headers["Upload-Offset"])
        assertEquals("-1", status.headers["Upload-Length"])

        val second = authed(
            "PATCH", location,
            headers = patchHeaders + ("Upload-Offset" to "4"),
            body = "ef".toByteArray(Charsets.UTF_8),
        )
        assertEquals(204, second.status)
        assertEquals("abcdef", File(tempRoot, "up.bin").readText())
    }

    @Test
    fun `tus create existing without override conflicts`() {
        val query = mapOf("path" to "hello.txt")
        assertEquals(409, authed("POST", "/api/tus", query = query).status)
        val override = mapOf("path" to "hello.txt", "override" to "true")
        assertEquals(201, authed("POST", "/api/tus", query = override).status)
        assertEquals(0L, File(tempRoot, "hello.txt").length())
    }

    @Test
    fun `tus patch rejects wrong content type and offset`() {
        val create = authed("POST", "/api/tus", query = mapOf("path" to "up2.bin"))
        val location = create.headers["Location"]!!
        val wrongType = authed(
            "PATCH", location,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Upload-Offset" to "0",
            ),
            body = "x".toByteArray(Charsets.UTF_8),
        )
        assertEquals(415, wrongType.status)
        val noOffset = authed(
            "PATCH", location,
            headers = mapOf("Content-Type" to "application/offset+octet-stream"),
            body = "x".toByteArray(Charsets.UTF_8),
        )
        assertEquals(400, noOffset.status)
        val badOffset = authed(
            "PATCH", location,
            headers = mapOf(
                "Content-Type" to "application/offset+octet-stream",
                "Upload-Offset" to "9",
            ),
            body = "x".toByteArray(Charsets.UTF_8),
        )
        assertEquals(409, badOffset.status)
    }

    @Test
    fun `tus status unknown id is 404`() {
        assertEquals(404, authed("HEAD", "/api/tus/no-such-id").status)
    }

    // ---- /api/raw 与静态 ----

    @Test
    fun `raw single file serves mime and multi files zip`() {
        val single = authed("GET", "/api/raw", query = mapOf("path" to "hello.txt"))
        assertEquals(200, single.status)
        assertEquals("text/plain", single.headers["Content-Type"])
        assertEquals("hello", single.bodyFile?.readText())

        val multi = authed(
            "GET", "/api/raw", query = mapOf("path" to "hello.txt,sub/inner.txt"),
        )
        assertEquals(200, multi.status)
        assertEquals("application/zip", multi.headers["Content-Type"])
        assertTrue(multi.body.size > 4)
        assertEquals(0x50, multi.body[0].toInt())
        assertEquals(0x4B, multi.body[1].toInt())
    }

    @Test
    fun `static root serves index and unknown get falls back to index`() {
        val root = runBlocking {
            router.route(HttpRequest("GET", "/", emptyMap(), emptyMap(), ByteArray(0)))
        }
        assertEquals(200, root.status)
        assertTrue(bodyText(root).contains("<html>"))
        val unknown = runBlocking {
            router.route(HttpRequest("GET", "/whatever", emptyMap(), emptyMap(), ByteArray(0)))
        }
        assertEquals(200, unknown.status)
    }

    @Test
    fun `static traversal is rejected`() {
        val response = runBlocking {
            router.route(HttpRequest("GET", "/static/../secret", emptyMap(), emptyMap(), ByteArray(0)))
        }
        assertEquals(400, response.status)
    }

    // ---- A-27 登录按 IP 限速 ----

    private fun loginAttempt(passwordAttempt: String, ip: String): HttpResponse = runBlocking {
        router.route(
            HttpRequest(
                "POST", "/api/login", emptyMap(), emptyMap(),
                """{"password":"$passwordAttempt"}""".toByteArray(Charsets.UTF_8),
                remoteAddress = ip,
            ),
        )
    }

    @Test
    fun `login five failures then sixth is locked and recovers after 60s`() {
        var fakeNow = System.currentTimeMillis()
        val timedRouter = WebServerRouter(store, actions, { passwordHash }, nowMs = { fakeNow })
        val ip = "10.0.0.9"
        fun attempt(passwordAttempt: String): HttpResponse = runBlocking {
            timedRouter.route(
                HttpRequest(
                    "POST", "/api/login", emptyMap(), emptyMap(),
                    """{"password":"$passwordAttempt"}""".toByteArray(Charsets.UTF_8),
                    remoteAddress = ip,
                ),
            )
        }
        repeat(5) { assertEquals(401, attempt("nope").status) }
        assertEquals(429, attempt("nope").status)
        assertEquals(429, attempt(password).status)
        fakeNow += 61_000L
        assertEquals(200, attempt(password).status)
    }

    @Test
    fun `login success clears failure count`() {
        val ip = "10.0.0.10"
        repeat(4) { assertEquals(401, loginAttempt("nope", ip).status) }
        assertEquals(200, loginAttempt(password, ip).status)
        repeat(4) { assertEquals(401, loginAttempt("nope", ip).status) }
        assertEquals(401, loginAttempt("nope", ip).status)
    }

    // ---- A-25 体走临时文件（bodyFile）----

    @Test
    fun `tus patch from bodyFile appends without heap body`() = runBlocking {
        val created = authed("POST", "/api/tus", query = mapOf("path" to "tus2.bin", "override" to "true"))
        assertEquals(201, created.status)
        val id = created.headers["Location"]!!.removePrefix("/api/tus/")
        val chunk = File.createTempFile("tus-chunk-", ".tmp").also { it.writeBytes(ByteArray(1024) { 7 }) }
        try {
            val token = WifiTransferAuth.issueToken(passwordHash)
            val patched = router.route(
                HttpRequest(
                    "PATCH", "/api/tus/$id", emptyMap(),
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to "application/offset+octet-stream",
                        "Upload-Offset" to "0",
                    ),
                    ByteArray(0), bodyFile = chunk,
                ),
            )
            assertEquals(204, patched.status)
            assertEquals("1024", patched.headers["Upload-Offset"])
            assertEquals(1024L, File(tempRoot, "tus2.bin").length())
            assertEquals(7, File(tempRoot, "tus2.bin").readBytes()[512].toInt())
        } finally {
            chunk.delete()
        }
    }

    @Test
    fun `put from bodyFile writes without heap body`() = runBlocking {
        val source = File.createTempFile("put-src-", ".tmp").also { it.writeText("streamed") }
        try {
            val token = WifiTransferAuth.issueToken(passwordHash)
            val streamed = router.route(
                HttpRequest(
                    "PUT", "/api/resources", mapOf("path" to "put2.txt"),
                    mapOf("Authorization" to "Bearer $token"),
                    ByteArray(0), bodyFile = source,
                ),
            )
            assertEquals(200, streamed.status)
            assertEquals("streamed", File(tempRoot, "put2.txt").readText())
        } finally {
            source.delete()
        }
    }

    // ---- B-W-4 受保护路径只防删不防写 → 写口同口径 ----

    @Test
    fun `protected paths reject put create and tus`() {
        val putUser = authed("PUT", "/api/resources", query = mapOf("path" to "user/evil.yaml"), body = "x".toByteArray())
        assertEquals(403, putUser.status)
        val putShared = authed("PUT", "/api/resources", query = mapOf("path" to "shared/evil.txt"), body = "x".toByteArray())
        assertEquals(403, putShared.status)
        val mkdirSkin = authed("POST", "/api/resources", query = mapOf("path" to "skin_library/newdir/"))
        assertEquals(403, mkdirSkin.status)
        val tusSkin = authed("POST", "/api/tus", query = mapOf("path" to "skin_library/big.bin", "override" to "true"))
        assertEquals(403, tusSkin.status)
        assertTrue(!File(tempRoot, "user/evil.yaml").exists())
        assertTrue(!File(tempRoot, "shared/evil.txt").exists())
    }

    @Test
    fun `unprotected paths still writable`() {
        val put = authed("PUT", "/api/resources", query = mapOf("path" to "plain.txt"), body = "ok".toByteArray())
        assertEquals(200, put.status)
        assertEquals("ok", File(tempRoot, "plain.txt").readText())
    }
}
