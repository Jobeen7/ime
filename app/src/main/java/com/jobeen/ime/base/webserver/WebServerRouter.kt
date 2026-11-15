package com.jobeen.ime.base.webserver

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** HTTP 请求（Socket 层解析后交给路由；路径已去掉查询串并解码）。 */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    /** TUS 分片／PUT 写盘时体在临时文件里（流式落盘，不进堆）；其余请求为 null。 */
    val bodyFile: File? = null,
    /** 对端 IP（登录限速用；路由单测直接构造时为空）。 */
    val remoteAddress: String = "",
) {
    /** 请求头不区分大小写取（Socket 层不保证键的大小写）。 */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** HTTP 响应；[bodyFile] 非空时 Socket 层流式写盘文件，不经内存。 */
data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val bodyFile: File? = null,
    val bodyFileLength: Long = 0L,
)

/** 路由需调用的应用能力（生产实现见 WebServerActionsImpl；单测用 fake）。 */
interface WebServerActions {
    suspend fun deploy(): Boolean
    suspend fun sync(): Boolean

    /** 校验皮肤并写入皮肤库；失败返回原因（400 文案）。 */
    fun applySkin(name: String, skinJson: String, manifestJson: String): Result<Unit>

    /** 切换方案目录并重新部署。 */
    suspend fun switchUserDataDir(dir: String): SwitchUserDataResult

    /** 静态前端资产（assets/webserver/ 下相对路径）；缺失返回 null。 */
    fun staticAsset(relativePath: String): ByteArray?
}

enum class SwitchUserDataResult { OK, INVALID_DIR, DEPLOY_FAILED }

/**
 * Wi-Fi 传输路由（WO52 4.4）：22 条路由按单子固定顺序匹配。
 *
 * 鉴权：除 /api/login、/api/renew 与静态前端外，全部接口校验
 * Authorization: Bearer（缺失／无效 → 401）；未设密码时登录
 * 返回 403。前端（/、/index.html、/static）不设鉴权（登录表单
 * 就在页面里；数据口仍须 token）。
 *
 * 未定论 §15-12（w91.r 目录列表排序／分页细节）：目录列表按
 * name 降序（sorting:{by:name,asc:false}），不分页；对端
 * 若有分页参数，本实现暂不认（遇上再标）。
 */
class WebServerRouter(
    private val store: WebServerStore,
    private val actions: WebServerActions,
    private val passwordHashProvider: () -> String?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val tusUploads = TusUploads()
    private val loginFailures = ConcurrentHashMap<String, LoginAttempt>()

    /** 登录失败记账（纯内存，按 IP；成功清零）。 */
    private data class LoginAttempt(var failures: Int, var lockedUntilMs: Long)

    /**
     * Socket 层读体前的轻量鉴权预检（A-25）：公开路径放行，其余须有效
     * Bearer；与 [route] 的鉴权段同口径（route 另有“未设密码 → 403”的
     * 区分，此处统一按无有效 Bearer 处理，直接 401 不读体）。
     */
    fun preAuth(method: String, path: String, headers: Map<String, String>): Boolean {
        val m = method.uppercase()
        if (m == "GET" && !path.startsWith("/api/")) return true
        if (m == "POST" && path == "/api/login") return true
        if (m == "POST" && path == "/api/renew") return true
        val passwordHash = passwordHashProvider() ?: return false
        val token = WifiTransferAuth.bearerToken(headers) ?: return false
        return WifiTransferAuth.verifyToken(passwordHash, token, nowMs())
    }

    suspend fun route(request: HttpRequest): HttpResponse {
        val method = request.method.uppercase()
        val path = request.path
        // 静态前端（登录页本身与前端路由回落）不鉴权；登录／续期公开；
        // 其余须 token。非 /api/ 的 GET 一律走静态（含未知路径回落）。
        if (method == "GET" && !path.startsWith("/api/")) {
            return staticResponse(path)
        }
        if (method == "POST" && path == "/api/login") return login(request)
        if (method == "POST" && path == "/api/renew") return renew(request)
        val passwordHash = passwordHashProvider() ?: return jsonError(403, "password_not_set")
        val token = WifiTransferAuth.bearerToken(request.headers)
        if (token == null || !WifiTransferAuth.verifyToken(passwordHash, token, nowMs())) {
            return jsonError(401, "unauthorized")
        }
        return when {
            method == "POST" && path == "/api/skins/capabilities" -> jsonOk("""{"canRunMainJsonnet":false}""")
            method == "POST" && path == "/api/skins/apply" -> applySkin(request)
            method == "POST" && path == "/api/rime-user-data/apply" -> applyUserData(request)
            method == "POST" && path == "/api/rime/deploy" -> deploy()
            method == "POST" && path == "/api/rime/sync" -> sync()
            method == "GET" && path == "/api/usage" -> usage()
            method == "PATCH" && path == "/api/resources" -> moveResource(request)
            method == "PUT" && path == "/api/resources" -> writeResource(request)
            method == "DELETE" && path == "/api/resources" -> deleteResource(request)
            method == "POST" && path == "/api/resources" -> createResource(request)
            method == "GET" && path == "/api/resources/recursive" -> listRecursive(request)
            method == "GET" && path == "/api/resources" -> getResource(request)
            method == "POST" && path == "/api/tus" -> createTusUpload(request)
            (method == "HEAD" || method == "GET") && path.startsWith("/api/tus/") -> tusStatus(request)
            method == "PATCH" && path.startsWith("/api/tus/") -> tusPatch(request)
            method == "GET" && path == "/api/raw" -> rawFile(request)
            path.startsWith("/api/") -> jsonError(404, "not_found")
            method == "GET" -> staticResponse("/index.html")
            else -> jsonError(404, "not_found")
        }
    }

    // ---- 登录／续期 ----

    private fun login(request: HttpRequest): HttpResponse {
        val passwordHash = passwordHashProvider() ?: return jsonError(403, "password_not_set")
        val now = nowMs()
        val ip = request.remoteAddress
        val attempt = loginFailures[ip]
        if (attempt != null && now < attempt.lockedUntilMs) {
            return jsonError(429, "too_many_attempts")
        }
        val body = parseBodyObject(request.body) ?: return jsonError(400, "bad_body")
        val password = body.string("password") ?: return jsonError(400, "missing_password")
        if (!constantTimeEquals(passwordHash, WifiTransferAuth.hashPassword(password))) {
            loginFailures.compute(ip) { _, old ->
                val failures = (old?.failures ?: 0) + 1
                LoginAttempt(
                    failures,
                    if (failures >= MAX_LOGIN_FAILURES) now + LOGIN_LOCKOUT_MS else old?.lockedUntilMs ?: 0L,
                )
            }
            return jsonError(401, "invalid_password")
        }
        loginFailures.remove(ip)
        val token = WifiTransferAuth.issueToken(passwordHash, nowMs())
        return jsonOk(buildJsonObject { put("token", JsonPrimitive(token)) }.toString())
    }

    private fun renew(request: HttpRequest): HttpResponse {
        val passwordHash = passwordHashProvider() ?: return jsonError(403, "password_not_set")
        val token = WifiTransferAuth.bearerToken(request.headers) ?: return jsonError(401, "unauthorized")
        if (!WifiTransferAuth.verifyToken(passwordHash, token, nowMs())) {
            return jsonError(401, "unauthorized")
        }
        val fresh = WifiTransferAuth.issueToken(passwordHash, nowMs())
        return jsonOk(buildJsonObject { put("token", JsonPrimitive(fresh)) }.toString())
    }

    // ---- 皮肤／方案数据／部署／同步 ----

    private fun applySkin(request: HttpRequest): HttpResponse {
        val body = parseBodyObject(request.body) ?: return jsonError(400, "bad_body")
        val name = body.string("name") ?: return jsonError(400, "missing_name")
        if (!isValidSkinName(name)) return jsonError(400, "invalid_name")
        val skin = body.element("skin") ?: return jsonError(400, "missing_skin")
        val manifest = body.element("manifest") ?: return jsonError(400, "missing_manifest")
        val skinText = skinTextOf(skin) ?: return jsonError(400, "bad_skin")
        val manifestText = skinTextOf(manifest) ?: return jsonError(400, "bad_manifest")
        return actions.applySkin(name, skinText, manifestText).fold(
            onSuccess = { jsonOk("""{"ok":true,"name":${jsonString(name)}}""") },
            onFailure = { jsonError(400, it.message ?: "invalid_skin") },
        )
    }

    private fun isValidSkinName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    private suspend fun applyUserData(request: HttpRequest): HttpResponse {
        val body = parseBodyObject(request.body) ?: return jsonError(400, "bad_body")
        val dir = body.string("dir") ?: return jsonError(400, "missing_dir")
        return when (actions.switchUserDataDir(dir)) {
            SwitchUserDataResult.OK ->
                jsonOk("""{"ok":true,"dir":${jsonString(dir)}}""")
            SwitchUserDataResult.INVALID_DIR -> jsonError(400, "invalid_dir")
            SwitchUserDataResult.DEPLOY_FAILED -> jsonError(500, "deploy_failed")
        }
    }

    private suspend fun deploy(): HttpResponse {
        return if (actions.deploy()) {
            jsonOk("""{"message":"部署成功"}""")
        } else {
            jsonError(500, "部署失败")
        }
    }

    private suspend fun sync(): HttpResponse {
        return if (actions.sync()) {
            jsonOk("""{"message":"同步成功"}""")
        } else {
            jsonError(500, "同步失败")
        }
    }

    private fun usage(): HttpResponse {
        val (used, total) = store.usage()
        return jsonOk("""{"used":$used,"total":$total}""")
    }

    // ---- /api/resources ----

    private fun moveResource(request: HttpRequest): HttpResponse {
        val from = request.query["path"] ?: return jsonError(400, "missing_path")
        val to = request.query["to"] ?: return jsonError(400, "missing_to")
        val copy = request.query["copy"] == "true"
        val fromFile = WebServerPaths.resolveWithin(store.root, from)
        val toFile = WebServerPaths.resolveWithin(store.root, to)
        if (fromFile == null || toFile == null) return jsonError(400, "bad_path")
        if (WebServerPaths.isProtected(store.root, fromFile) ||
            WebServerPaths.isProtected(store.root, toFile)
        ) {
            return jsonError(403, "protected_path")
        }
        if (!store.exists(from)) return jsonError(404, "not_found")
        return if (store.move(from, to, copy)) jsonOk("""{"ok":true}""") else jsonError(500, "move_failed")
    }

    private fun writeResource(request: HttpRequest): HttpResponse {
        val path = request.query["path"] ?: return jsonError(400, "missing_path")
        val result = request.bodyFile?.let { store.writeFileStream(path, it, override = true) }
            ?: store.writeFile(path, request.body, override = true)
        return when (result) {
            WriteResult.OK -> jsonOk("""{"ok":true}""")
            WriteResult.CONFLICT -> jsonError(409, "exists")
            WriteResult.FAILED -> jsonError(500, "write_failed")
        }
    }

    private fun deleteResource(request: HttpRequest): HttpResponse {
        val path = request.query["path"] ?: return jsonError(400, "missing_path")
        return when (store.delete(path)) {
            DeleteResult.OK -> jsonOk("""{"ok":true}""")
            DeleteResult.NOT_FOUND -> jsonError(404, "not_found")
            DeleteResult.ROOT -> jsonError(400, "cannot_delete_root")
            DeleteResult.PROTECTED -> jsonError(403, "protected_path")
            DeleteResult.FAILED -> jsonError(500, "delete_failed")
        }
    }

    private fun createResource(request: HttpRequest): HttpResponse {
        val rawPath = request.query["path"] ?: return jsonError(400, "missing_path")
        val override = request.query["override"] == "true"
        if (rawPath.endsWith("/")) {
            val dir = rawPath.trimEnd('/')
            if (dir.isEmpty()) return jsonError(400, "bad_path")
            return if (store.mkdirs(dir)) jsonOk("""{"ok":true}""") else jsonError(500, "mkdir_failed")
        }
        return when (store.writeFile(rawPath, ByteArray(0), override)) {
            WriteResult.OK -> jsonOk("""{"ok":true}""")
            WriteResult.CONFLICT -> jsonError(409, "exists")
            WriteResult.FAILED -> jsonError(500, "create_failed")
        }
    }

    private fun listRecursive(request: HttpRequest): HttpResponse {
        val path = request.query["path"].orEmpty()
        val entries = store.listRecursive(path) ?: return jsonError(500, "not_found")
        return jsonOk(
            buildJsonObject {
                put("path", JsonPrimitive(path))
                put("items", entriesJson(entries))
            }.toString(),
        )
    }

    private fun getResource(request: HttpRequest): HttpResponse {
        val path = request.query["path"].orEmpty()
        val file = store.readFile(path)
        if (file != null) {
            return HttpResponse(
                status = 200,
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Disposition" to "attachment; filename=\"${file.name}\"",
                ),
                bodyFile = file,
                bodyFileLength = file.length(),
            )
        }
        val entries = store.listDir(path) ?: return jsonError(500, "not_found")
        val numDirs = entries.count { it.isDir }
        val numFiles = entries.size - numDirs
        return jsonOk(
            buildJsonObject {
                put("numDirs", JsonPrimitive(numDirs))
                put("numFiles", JsonPrimitive(numFiles))
                put("items", entriesJson(entries))
                put(
                    "sorting",
                    buildJsonObject {
                        put("by", JsonPrimitive("name"))
                        put("asc", JsonPrimitive(false))
                    },
                )
            }.toString(),
        )
    }

    // ---- tus 断点续传 ----

    private fun createTusUpload(request: HttpRequest): HttpResponse {
        val path = request.query["path"] ?: return jsonError(400, "missing_path")
        val override = request.query["override"] == "true"
        if (store.exists(path) && !override) return jsonError(409, "exists")
        if (store.writeFile(path, ByteArray(0), override) != WriteResult.OK) {
            return jsonError(500, "create_failed")
        }
        val id = UUID.randomUUID().toString()
        tusUploads.create(id, path)
        return HttpResponse(
            status = 201,
            headers = mapOf(
                "Location" to "/api/tus/$id",
                "Tus-Resumable" to "1.0.0",
                "Upload-Offset" to "0",
            ),
        )
    }

    private fun tusStatus(request: HttpRequest): HttpResponse {
        val id = request.path.removePrefix("/api/tus/")
        val relPath = tusUploads.getRelPath(id) ?: return jsonError(404, "not_found")
        val offset = store.fileSize(relPath) ?: return jsonError(404, "not_found")
        return HttpResponse(
            status = 200,
            headers = mapOf(
                "Tus-Resumable" to "1.0.0",
                "Upload-Offset" to offset.toString(),
                "Upload-Length" to "-1",
            ),
        )
    }

    private fun tusPatch(request: HttpRequest): HttpResponse {
        val id = request.path.removePrefix("/api/tus/")
        val relPath = tusUploads.getRelPath(id) ?: return jsonError(404, "not_found")
        val contentType = request.header("content-type").orEmpty()
        if (!contentType.startsWith("application/offset+octet-stream")) {
            return jsonError(415, "unsupported_media_type")
        }
        val offsetHeader = request.header("upload-offset") ?: return jsonError(400, "missing_offset")
        val offset = offsetHeader.toLongOrNull() ?: return jsonError(400, "bad_offset")
        val current = store.fileSize(relPath) ?: return jsonError(404, "not_found")
        if (offset != current) return jsonError(409, "offset_mismatch")
        val newLength = request.bodyFile?.let { store.appendFileStream(relPath, it, offset) }
            ?: store.appendFile(relPath, request.body, offset)
        val newLengthValue = newLength ?: return jsonError(500, "append_failed")
        return HttpResponse(
            status = 204,
            headers = mapOf(
                "Tus-Resumable" to "1.0.0",
                "Upload-Offset" to newLengthValue.toString(),
                "Upload-Length" to "-1",
            ),
        )
    }

    // ---- /api/raw ----

    private fun rawFile(request: HttpRequest): HttpResponse {
        val rawPath = request.query["path"] ?: return jsonError(400, "missing_path")
        val paths = rawPath.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) return jsonError(400, "missing_path")
        if (paths.size == 1) {
            val file = store.readFile(paths[0]) ?: return jsonError(404, "not_found")
            return HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to store.mimeType(paths[0])),
                bodyFile = file,
                bodyFileLength = file.length(),
            )
        }
        val files = paths.map { store.readFile(it) ?: return jsonError(404, "not_found") }
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/zip"),
            body = zipBytes,
        )
    }

    // ---- 静态前端 ----

    private fun staticResponse(path: String): HttpResponse {
        val assetPath = when {
            path == "/" || path == "/index.html" -> "index.html"
            path.startsWith("/static/") -> {
                val rel = path.removePrefix("/static/")
                if (rel.contains("..")) return jsonError(400, "bad_path")
                "static/$rel"
            }
            // 前端路由回落：未知非 API 路径一律回登录／浏览页。
            else -> "index.html"
        }
        val bytes = actions.staticAsset(assetPath) ?: return jsonError(404, "not_found")
        val contentType = when {
            assetPath.endsWith(".html") -> "text/html; charset=utf-8"
            assetPath.endsWith(".css") -> "text/css"
            assetPath.endsWith(".js") -> "text/javascript"
            else -> "application/octet-stream"
        }
        return HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to contentType),
            body = bytes,
        )
    }

    // ---- 小工具 ----

    private fun jsonOk(json: String): HttpResponse =
        HttpResponse(200, mapOf("Content-Type" to "application/json"), json.toByteArray(Charsets.UTF_8))

    private fun jsonError(status: Int, error: String): HttpResponse {
        val body = buildJsonObject { put("error", JsonPrimitive(error)) }.toString()
        return HttpResponse(status, mapOf("Content-Type" to "application/json"), body.toByteArray(Charsets.UTF_8))
    }

    private fun parseBodyObject(body: ByteArray): JsonObject? {
        if (body.isEmpty()) return null
        return runCatching {
            Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    /** skin/manifest 字段接受对象或 JSON 字符串两种形态。 */
    private fun skinTextOf(element: kotlinx.serialization.json.JsonElement): String? {
        return when (element) {
            is JsonObject -> element.toString()
            is JsonPrimitive -> element.contentOrNull?.let {
                runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()?.toString()
            }
            else -> null
        }
    }

    private fun entriesJson(entries: List<WebEntry>) = buildJsonArray {
        for (entry in entries) {
            add(
                buildJsonObject {
                    put("name", JsonPrimitive(entry.name))
                    put("type", JsonPrimitive(if (entry.isDir) "dir" else "file"))
                    put("size", JsonPrimitive(entry.size))
                    put("modified", JsonPrimitive(entry.modifiedMs))
                },
            )
        }
    }

    private fun jsonString(value: String): String = JsonPrimitive(value).toString()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.element(key: String) = this[key]

    /** tus 上传登记（内存态；服务重启后未完成的上传失效，需重传）。 */
    private class TusUploads {
        private data class TusUpload(val relPath: String)

        private val uploads = ConcurrentHashMap<String, TusUpload>()

        fun create(id: String, relPath: String) {
            uploads[id] = TusUpload(relPath)
        }

        fun getRelPath(id: String): String? = uploads[id]?.relPath
    }

    companion object {
        /** 登录限速：同一 IP 5 次失败锁 60 秒（A-27；纯内存，成功清零）。 */
        const val MAX_LOGIN_FAILURES = 5
        const val LOGIN_LOCKOUT_MS = 60_000L
    }
}
