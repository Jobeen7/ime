package com.jobeen.ime.engine.rime.data.userdict

import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.engine.rime.daemon.RimeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * 用户词典 WebDAV 同步：把词典导出为文本文件后 PUT 上传，或 GET 下载后合并导入。
 *
 * - 同步目录：服务器地址 + 同步路径（如 https://dav.jianguoyun.com/dav/rime_sync）
 * - 上传：本地导出 -> PUT 到同步目录（同名覆盖）
 * - 下载：先 PROPFIND 列出同步目录文件让用户选择 -> GET 到本地 -> 合并导入本地词典
 *   （不删除本地已有词；可跨词典名，如电脑端 wanxiang -> 手机端 wanxiang_lite）
 * - 导入/导出使用热路径（importUserDictLive/exportUserDictLive），直接对运行中
 *   引擎已打开的词库句柄操作，无需停止 Rime；且一律经 [RimeSession] 的 RimeApi
 *   在引擎线程串行执行，不与引擎查词/学词并发访问同一个 LevelDB
 * - 上传前用 MKCOL 确保同步目录存在；报错时会带上服务器返回的正文以便定位原因
 */
object WebDavSync {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 禁止跨协议跳转：HTTPS 同步地址被服务器（被攻破/误配置时）
        // 301 到 HTTP 时，同步口令与词库内容会在那一跳走明文。与其余
        // 固定服务客户端口径一致；https→https 的正常跳转不受影响
        .followSslRedirects(false)
        .build()

    private val textPlain = "text/plain; charset=utf-8".toMediaType()

    /**
     * 同步互斥：此前防并发全靠设置页的 busy 门禁，程序化调用/双入口并发时
     * 两次同步会交错读写同一远端文件与 ETag 基准。upload 内部的合并下载
     * 走 downloadLocked（已持锁变体），避免 Mutex 不可重入自死锁。
     */
    private val syncMutex = Mutex()

    /** 服务器上的文件名：每本词典一个文件，多设备共用 */
    fun remoteFileName(dictName: String): String = "$dictName.userdict.txt"

    private fun remoteUrl(fileName: String): String {
        // 文件名按 path segment 做百分号编码（空格编为 %20 而非 +）
        val encoded = fileName.split("/").joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        return "${UserDictPrefs.syncDirUrl()}/$encoded"
    }

    private fun authHeader(): String =
        Credentials.basic(UserDictPrefs.username, UserDictPrefs.password, Charsets.UTF_8)

    private fun checkConfigured() {
        val server = UserDictPrefs.normalizedServer()
        require(server.startsWith("http://") || server.startsWith("https://")) {
            "服务器地址须以 http:// 或 https:// 开头"
        }
        // 默认强制 HTTPS：明文 HTTP 会让账号密码与词典内容在网络上裸奔，
        // 仅当用户在「高级」里手动开启并确认风险后才放行
        require(!server.startsWith("http://") || UserDictPrefs.allowHttp) {
            "服务器使用明文 HTTP，账号密码与词典内容会被明文传输。" +
                "请改用 HTTPS，或在用户词典页的「高级」中开启「允许明文 HTTP」并确认风险后再同步"
        }
        require(UserDictPrefs.username.isNotBlank()) { "请填写用户名" }
        require(UserDictPrefs.password.isNotEmpty()) { "请填写密码" }
    }

    /** 携带 HTTP 状态码的 WebDAV 错误，便于调用方按状态码分支处理 */
    class WebDavHttpException(val httpCode: Int, message: String) : IllegalStateException(message)

    /**
     * 把错误响应的正文（去空白、截断到 400 字）拼进错误信息，
     * 坚果云等会在正文里写明 403 的具体原因，只看状态码定位不到。
     */
    private fun errorDetail(response: Response): String {
        val body = runCatching { response.body?.string()?.trim().orEmpty() }.getOrDefault("")
        if (body.isEmpty()) return ""
        return body.replace(Regex("\\s+"), " ").take(400)
    }

    private fun httpError(action: String, code: Int, detail: String): WebDavHttpException {
        val msg = if (detail.isEmpty()) "${action}失败：HTTP $code" else "${action}失败：HTTP $code（$detail）"
        return WebDavHttpException(code, msg)
    }

    /**
     * 确保同步目录存在（MKCOL）。同步路径为空（根目录）时跳过。
     * 目录已存在时返回 405 属正常；其他失败不抛错，后续 PUT 会报出真实原因。
     */
    private fun ensureSyncDir() {
        if (UserDictPrefs.normalizedSyncPath().isEmpty()) return
        val request = Request.Builder()
            .url(UserDictPrefs.syncDirUrl())
            .header("Authorization", authHeader())
            .method("MKCOL", "".toRequestBody(null))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 405) {
                    Timber.w("MKCOL ${UserDictPrefs.syncDirUrl()} -> HTTP ${response.code}")
                }
            }
        }.onFailure { Timber.w(it, "MKCOL sync dir failed") }
    }

    /** 远端文件的版本状态：用于上传前判断远端是否被其他设备改动过 */
    private data class RemoteState(
        val etag: String?,
        val lastModifiedMs: Long,
        /** ETag 是否为弱校验形式（W/ 前缀）：弱 ETag 不能用于 If-Match 强比较 */
        val etagWeak: Boolean = false,
    )

    /**
     * 一次成功下载合并的结果：词条数，以及「本次实际下载并成功合并
     * 的那份内容」对应的远端状态。上传路径用它锚定 PUT 前置条件。
     *
     * mergedState 一律是 PROPFIND 口径（合并后重查的状态，且已通过
     * 「GET 前后两次 PROPFIND 夹逼」校验，见 downloadLocked），不用
     * GET 响应头：中间层可能改写 GET 的 ETag 表示（Apache
     * mod_deflate 默认给 GET 响应 ETag 加 "-gzip" 后缀、PROPFIND
     * 的 getetag 不变），GET 头只作日志参考，绝不当基准、绝不参与
     * 校验。远端在合并期间被其他设备删除时 mergedState 为 null
     * （上传路径退化为新建）。
     */
    private data class DownloadOutcome(
        val count: Int,
        val mergedState: RemoteState?,
    )

    /**
     * 远端状态查询的三态结果：必须区分「确实不存在」与「查不到」——
     * 后者（网络错误/5xx）若按不存在处理，上传会无条件 PUT、静默覆盖
     * 其他设备刚改的远端词库。
     */
    private sealed interface RemoteStateResult {
        /** 远端存在且状态已取得 */
        data class Found(val state: RemoteState) : RemoteStateResult

        /** 服务器明确回 404：远端不存在，可安全新建 */
        data object NotFound : RemoteStateResult

        /** 网络错误 / 非 404 的错误码 / 解析异常：不可判定，调用方应中止同步 */
        data class Error(val message: String) : RemoteStateResult
    }

    private val etagRegex =
        Regex("<(?:\\w+:)?getetag>(.*?)</(?:\\w+:)?getetag>", RegexOption.IGNORE_CASE)
    private val lastModifiedRegex =
        Regex(
            "<(?:\\w+:)?getlastmodified>(.*?)</(?:\\w+:)?getlastmodified>",
            RegexOption.IGNORE_CASE,
        )

    /**
     * ETag 规范形：基准值来自 PUT 响应头、比较值来自 PROPFIND XML，两处
     * 的引号与弱校验 `W/` 前缀表示在部分服务器上不一致，混用会让
     * isRemoteChanged 恒为 true（每次上传都多做一次全量下载合并）。
     * 比较与存储统一为去引号、去 W/ 的裸值；发送 If-Match 时再加回引号。
     */
    private fun normalizeETag(raw: String?): String? =
        raw?.trim()?.removePrefix("W/")?.trim()?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() }

    // 上传前置条件（If-Match / If-Unmodified-Since）的构造统一走文件
    // 末尾的 putPrecondition()：弱 ETag 不能用于 If-Match 强比较（RFC
    // 规定弱标签永不强匹配，硬发会让服务器回 412 且同步永远失败），
    // 改用修改时间条件；强 ETag 仍发带引号的 If-Match。

    /**
     * 查询某个远端文件的 ETag / 修改时间（PROPFIND Depth: 0），返回三态结果：
     * 404 → [RemoteStateResult.NotFound]（可安全新建）；2xx →
     * [RemoteStateResult.Found]；其余错误码与任何异常 →
     * [RemoteStateResult.Error]，调用方必须中止本次同步、不得继续上传——
     * 状态不可判定还无条件 PUT，会静默覆盖其他设备的远端词库。
     */
    private fun fetchRemoteState(fileName: String): RemoteStateResult {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getlastmodified/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(remoteUrl(fileName))
            .header("Authorization", authHeader())
            .header("Depth", "0")
            .method("PROPFIND", body)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return RemoteStateResult.NotFound
                if (!response.isSuccessful) {
                    return RemoteStateResult.Error("PROPFIND HTTP ${response.code}")
                }
                val xml = response.body?.string().orEmpty()
                val etagRaw = etagRegex.find(xml)?.groupValues?.get(1)
                // XML 文本里的 ETag 要先还原实体：部分服务器把引号写成
                // &quot;…&quot;，不还原就存下带实体的「假引号」值，发送
                // If-Match 时强比较永远对不上、上传恒 412。仅限此处
                // （响应头来的 ETag 不是 XML，不做实体处理）
                val etag = normalizeETag(etagRaw?.let { unescapeXmlEntities(it) })
                val lmText = lastModifiedRegex.find(xml)?.groupValues?.get(1)?.trim().orEmpty()
                RemoteStateResult.Found(
                    RemoteState(etag, parseHttpDateMs(lmText), isWeakETag(etagRaw)),
                )
            }
        }.getOrElse { RemoteStateResult.Error(it.message ?: "网络错误") }
    }

    /**
     * 判断远端文件自上次同步后是否被其他设备改动过。决策本体是文件
     * 末尾的纯函数 [remoteChangedDecision]（可单测），这里只负责从
     * per-file 基准里取数：只认该文件自己的 ETag 基准，不受其他词典
     * 同步历史的影响；远端不给 ETag 时没有任何可信基准，恒判「已变、
     * 先合并」。本机上传/下载时间仍逐文件记录（展示/日志用），但自
     * 第十六批起不再作为内容身份参与判定（见 remoteChangedDecision）。
     */
    private fun isRemoteChanged(fileName: String, state: RemoteState): Boolean =
        remoteChangedDecision(
            // 历史存量基准可能未规范化，比较前同样过一遍（自愈）
            knownETag = normalizeETag(UserDictPrefs.syncETag(fileName)),
            stateETag = state.etag,
        )

    /**
     * 上传当前词典到 WebDAV 服务器，返回导出的词条数。
     * 必须在后台线程调用。
     */
    suspend fun upload(dictName: String, session: RimeSession): Result<Int> =
        syncMutex.withLock { uploadLocked(dictName, session) }

    private suspend fun uploadLocked(dictName: String, session: RimeSession): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            checkConfigured()
            // 先确保同步目录存在：坚果云对不存在的目录直接返回 403
            ensureSyncDir()
            val fileName = remoteFileName(dictName)
            // 冲突保护：远端若被其他设备改过，先下载合并进本地再上传，
            // 绝不拿本地旧词库静默覆盖远端较新的内容。
            // 状态查不到（网络/服务器错误）时中止本次同步——不可判定还继续
            // PUT 等于拿本地覆盖远端；只有 404 明确不存在才走新建路径。
            var remoteMissing = false
            var baseState = when (val r = fetchRemoteState(fileName)) {
                is RemoteStateResult.Found -> r.state
                RemoteStateResult.NotFound -> {
                    remoteMissing = true
                    null
                }
                is RemoteStateResult.Error ->
                    throw IllegalStateException("查询远端词库状态失败（${r.message}），本次同步已中止")
            }
            if (baseState != null && isRemoteChanged(fileName, baseState)) {
                Timber.i("Remote '$fileName' changed since last sync; merging before upload")
                val merged = downloadLocked(dictName, session).getOrThrow()
                // 夹逼校验在 downloadLocked 内完成：GET 前后两次
                // PROPFIND（同口径）状态一致，才证明合并内容就是当前
                // 远端那一份；不一致时它已直接抛错中止（那批新词不在
                // 合并内容里，锚定上传必然覆盖丢失）。走到这里，
                // mergedState（PROPFIND 口径）即合并内容对应的远端
                // 状态，直接作 PUT 锚点。合并期间远端被删
                // （mergedState 为 null）则按不存在处理退化为新建
                // 上传，下方 PUT 带 If-None-Match 新建限定。
                baseState = merged.mergedState
                if (baseState == null) remoteMissing = true
            }
            val tempFile = File(appContext.cacheDir, "webdav-upload-${dictName}-${System.nanoTime()}.txt")
            try {
                val count = session.runOnReady {
                    exportUserDictLive(dictName, tempFile.absolutePath)
                }
                if (count < 0) throw IllegalStateException("导出用户词典失败")
                val builder = Request.Builder()
                    .url(remoteUrl(fileName))
                    .header("Authorization", authHeader())
                    .put(tempFile.asRequestBody(textPlain))
                // 带上合并基准的前置条件：强 ETag 时合并后到上传之间
                // 远端又被改动，服务器回 412，而不是被我们覆盖掉。
                // 弱 ETag 不能用于 If-Match（见 putPrecondition），
                // 构造降级为时间条件，但覆盖已存在文件时不算保护，
                // 由下方守卫在发请求前中止。
                baseState?.let { st ->
                    val pre = putPrecondition(st.etag, st.etagWeak, st.lastModifiedMs)
                    // 覆盖已存在文件的唯一并发保护是强 ETag 的
                    // If-Match：前置条件双空、或只有秒精度的修改
                    // 时间条件（弱/无 ETag），都察觉不了合并校验
                    // 后他设备刚写入的内容（同秒写入对日期条件
                    // 不可见），裸 PUT / 日期条件 PUT 都会将其覆
                    // 盖——中止本次上传，不冒丢词风险
                    if (isUnprotectedOverwrite(pre, remoteExists = true)) {
                        throw IllegalStateException(unprotectedOverwriteMessage(pre))
                    }
                    pre.ifMatch?.let { builder.header("If-Match", it) }
                    pre.ifUnmodifiedSince?.let {
                        builder.header("If-Unmodified-Since", it)
                    }
                }
                // 远端原本不存在：新建限定——查询后到上传之间若被其他设备
                // 先建了同名文件，服务器回 412 而非被我们覆盖
                if (remoteMissing && baseState == null) {
                    builder.header("If-None-Match", "*")
                }
                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 412) {
                        throw IllegalStateException(
                            "远端词库在同步期间又被其他设备修改，本次上传已取消以避免覆盖。" +
                                "请重新点一次上传（会先自动合并远端最新内容）"
                        )
                    }
                    if (!response.isSuccessful) {
                        throw httpError("上传", response.code, errorDetail(response))
                    }
                    val newETag = normalizeETag(response.header("ETag"))
                    if (newETag != null) {
                        UserDictPrefs.setSyncETag(fileName, newETag)
                    } else {
                        // 响应没带 ETag：补查一次，但补查状态不得无条
                        // 件当基准——PUT 完成到补查之间他设备可能又写
                        // 入了新版本，那份内容本机从未合并，记成基准会
                        // 让下次上传跳过合并、用 If-Match 合法覆盖它。
                        // 只有补查状态能被证明就是本次 PUT 落下的内容
                        // （见 postPutRecheckAdoptable）才采纳；证明不了
                        // 就不写基准，下次上传会先合并，不影响本次已
                        // 成功的上传
                        (fetchRemoteState(fileName) as? RemoteStateResult.Found)
                            ?.state?.let { recheck ->
                                if (postPutRecheckAdoptable(
                                        anchorETag = baseState?.etag,
                                        anchorETagWeak = baseState?.etagWeak ?: false,
                                        recheckETag = recheck.etag,
                                    )
                                ) {
                                    UserDictPrefs.setSyncETag(fileName, recheck.etag)
                                }
                            }
                    }
                }
                UserDictPrefs.setLastUploadTime(fileName, System.currentTimeMillis())
                Timber.i("User dict '$dictName' uploaded: $count entries")
                // 删除词表随词典同步一起走（并集合并，失败不影响词典同步结果）
                runCatching { syncDeletedWords() }
                    .onFailure { Timber.w(it, "Deleted words sync failed") }
                count
            } finally {
                tempFile.delete()
            }
        }
    }

    /** 服务器上的一个条目：文件或子目录 */
    data class RemoteEntry(val name: String, val isDirectory: Boolean)

    /**
     * 对同步目录做 PROPFIND，返回原始 XML。
     * 必须在后台线程调用。
     */
    private suspend fun propfindDirXml(): String = withContext(Dispatchers.IO) {
        checkConfigured()
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("${UserDictPrefs.syncDirUrl()}/")
            .header("Authorization", authHeader())
            .header("Depth", "1")
            .method("PROPFIND", body)
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> response.body?.string().orEmpty()
                response.code == 404 ->
                    throw WebDavHttpException(404, "同步目录不存在，请检查同步路径是否填写正确")
                response.code == 403 ->
                    throw WebDavHttpException(
                        403,
                        "无权访问同步目录（HTTP 403），请检查同步路径是否存在、账号是否有权限" +
                            errorDetail(response).takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty(),
                    )
                else -> throw httpError("列出服务器文件", response.code, errorDetail(response))
            }
        }
    }

    /**
     * 列出同步目录下的备份文件（排除目录自身和子目录）。
     *
     * 用于跨词典名下载：如电脑端万象 base 的词典名为 wanxiang，
     * 手机端 lite/T9 的词典名为 wanxiang_lite，文件名各不相同，
     * 下载时需先列出服务器文件让用户选择。
     * 必须在后台线程调用。
     */
    suspend fun listRemoteFiles(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            parseRemoteEntries(propfindDirXml())
                .filter { !it.isDirectory }
                .map { it.name }
                // 删除词表是同步内部文件，不作为可下载词典展示
                .filter { it != DELETED_WORDS_FILE }
                .distinct()
                .sortedWith(
                    compareBy(
                        {
                            !(
                                it.endsWith(".userdict.txt") ||
                                    it.endsWith(".userdb.txt", ignoreCase = true)
                                )
                        },
                        { it },
                    ),
                )
                .toList()
        }
    }

    private val hrefRegex =
        Regex("<(?:\\w+:)?href>(.*?)</(?:\\w+:)?href>", RegexOption.IGNORE_CASE)

    private val responseRegex =
        Regex(
            "<(?:\\w+:)?response>(.*?)</(?:\\w+:)?response>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    private val collectionRegex =
        Regex("<(?:\\w+:)?collection\\s*/>", RegexOption.IGNORE_CASE)

    /**
     * 解析 PROPFIND 结果，逐个 response 判断是文件还是目录。
     * 目录自身（坚果云有时不带末尾斜杠）和子目录都会被标记为 isDirectory，
     * 避免把它们当成文件去 GET（坚果云会回 403 Can not download collection）。
     */
    private fun parseRemoteEntries(xml: String): List<RemoteEntry> {
        val dirPath = runCatching { URI(UserDictPrefs.syncDirUrl()).path }.getOrDefault("").trimEnd('/')
        return responseRegex.findAll(xml)
            .mapNotNull { match ->
                val content = match.groupValues[1]
                val href = hrefRegex.find(content)?.groupValues?.get(1)?.trim()
                    ?: return@mapNotNull null
                val path = hrefPath(decodeHref(unescapeXmlEntities(href))).trimEnd('/')
                if (path.isEmpty()) return@mapNotNull null
                val isSelf = path == dirPath
                val isCollection = isSelf || collectionRegex.containsMatchIn(content)
                val name = path.substringAfterLast("/")
                if (name.isEmpty()) return@mapNotNull null
                RemoteEntry(name, isCollection)
            }
            .distinct()
            .toList()
    }

    /** 取 href 的 path 部分（兼容绝对 URL 和绝对路径两种形式，去掉 query） */
    private fun hrefPath(href: String): String {
        val noQuery = href.substringBefore("?")
        return if (noQuery.contains("://")) {
            runCatching { URI(noQuery).path }.getOrDefault(noQuery)
        } else {
            noQuery
        }
    }

    /** 百分号解码（按 UTF-8 字节还原，空格保持为 %20 解码，不把 + 当空格） */
    private fun decodeHref(href: String): String = decodeHrefImpl(href)

    /**
     * 把 Rime 原生同步快照（`<词典>.userdb.txt`）转置为导入接口要的列序。
     *
     * Rime 原生导出的列序为「码⇥词⇥权重」（码列末尾还带一个空格，是 Rime 内部 key
     * 格式）；但也存在「词⇥码⇥权重」的文件。这里按内容特征自动检测列序
     * （码列应为纯拼音字母，词列应含 CJK 汉字），避免列错位导致导入成功却查不到词。
     * 导入接口（UserDictManager::Import）要的是「词⇥码⇥权重」。
     * `#` 开头的注释行原样保留，读取时会被跳过。
     */
    private fun convertUserDbSnapshot(bytes: ByteArray): ByteArray {
        var text = bytes.toString(Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        // 先采样投票判定文件列序：多数行第一列像拼音且第二列含汉字 → 「码在前」
        var codeFirstVotes = 0
        var phraseFirstVotes = 0
        text.lineSequence().forEach { raw ->
            if (codeFirstVotes + phraseFirstVotes >= 200) return@forEach
            val line = raw.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val cols = line.split('\t')
            if (cols.size < 2) return@forEach
            val a = cols[0].trim()
            val b = cols[1].trim()
            if (a.isEmpty() || b.isEmpty()) return@forEach
            // 投票前先去声调：电脑端编码可能是带声调的（如 "chen yào"）
            val aNorm = stripPinyinTones(a)
            val bNorm = stripPinyinTones(b)
            if (isPinyinCode(aNorm) && containsCjk(b)) codeFirstVotes++
            else if (containsCjk(a) && isPinyinCode(bNorm)) phraseFirstVotes++
        }
        // 平票或无特征行时默认 Rime 原生「码在前」，与之前行为一致
        val codeFirst = codeFirstVotes >= phraseFirstVotes
        Timber.i("userdb snapshot column order: codeFirst=$codeFirst " +
                "(votes code-first=$codeFirstVotes, phrase-first=$phraseFirstVotes)")
        val out = StringBuilder(text.length)
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) {
                out.append(line).append('\n')
                return@forEach
            }
            val cols = line.split('\t')
            if (cols.size < 2) return@forEach
            val first = cols[0].trim()
            val second = cols[1].trim()
            if (first.isEmpty() || second.isEmpty()) return@forEach
            val phrase = if (codeFirst) second else first
            // 编码去声调后写入，与手机端无声调的查询编码对齐（如 "chen yào" → "chen yao"）
            val code = stripPinyinTones(if (codeFirst) first else second)
            out.append(phrase).append('\t').append(code)
            val weight = cols.getOrNull(2)?.trim()
            if (!weight.isNullOrEmpty()) out.append('\t').append(weight)
            out.append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** 是否像拼音编码：仅含小写字母和空格（如 "chen yao"）。 */
    private fun isPinyinCode(s: String): Boolean =
        s.isNotEmpty() && s.all { it in 'a'..'z' || it == ' ' }

    /** 是否包含 CJK 汉字（基本区 + 扩展 A 区）。 */
    private fun containsCjk(s: String): Boolean =
        s.any { it in '㐀'..'䶿' || it in '一'..'鿿' }

    /**
     * 去掉拼音里的声调符号：`yào` → `yao`。
     *
     * 有些电脑端方案（如万象的某个拼音）的用户词编码是带声调的
     * （`chen yào`），而手机端查询用的是无声调编码（`chen yao`），
     * 不归一化会导致导入的词永远查不到。ü 按 Rime 习惯转为 v。
     */
    private fun stripPinyinTones(s: String): String {
        if (s.isEmpty()) return s
        // 先处理 ü：按 Rime 习惯转为 v（NFD 会把它拆成 u + 分音符，之后就分不清了）
        val pre = s.replace('ü', 'v').replace('Ü', 'V')
            .replace('ǖ', 'v').replace('ǘ', 'v').replace('ǚ', 'v').replace('ǜ', 'v')
        val decomposed = Normalizer.normalize(pre, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            // 去掉组合用变音符号（U+0300–U+036F），即声调符号
            if (ch !in '̀'..'ͯ') sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * 从 WebDAV 服务器下载指定的词典文件并合并导入本地词典，返回导入的词条数。
     *
     * @param dictName 本地词典名（导入目标）
     * @param remoteFile 服务器上的文件名，默认与本地词典同名；跨设备词典名不同
     *   （如电脑端 wanxiang.userdict.txt）时可指定别的文件名
     * 必须在后台线程调用（内部会短暂重启 Rime）。
     *
     * 同时支持 Rime 原生同步快照 `<词典>.userdb.txt`（小狼毫同步目录
     * `<同步路径>/<电脑名>/` 下的文件，列序为「码⇥词⇥权重」）：
     * 下载后先转置为「词⇥码⇥权重」再合并导入，可跨词典名导入。
     */
    suspend fun download(
        dictName: String,
        session: RimeSession,
        remoteFile: String = remoteFileName(dictName),
    ): Result<Int> = syncMutex.withLock {
        downloadLocked(dictName, session, remoteFile).map { it.count }
    }

    private suspend fun downloadLocked(
        dictName: String,
        session: RimeSession,
        remoteFile: String = remoteFileName(dictName),
    ): Result<DownloadOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            checkConfigured()
            val sameName = remoteFile == remoteFileName(dictName)
            // 夹逼前哨：只对同名文件做（同步基准与上传锚点只对它有意
            // 义）。GET 之前先取一次 PROPFIND 状态，GET＋导入之后再
            // 取一次，两次同为 PROPFIND 口径、状态一致，才证明合并
            // 内容就是当前远端那一份。状态不可判定（网络/服务器错
            // 误）直接中止——锚不可信时继续等于拿未验证的基准覆盖
            // 远端。GET 响应头不作此校验的材料：中间层会改写它的
            // ETag 表示（见 DownloadOutcome 注释）
            var preState: RemoteState? = null
            if (sameName) {
                preState = when (val r = fetchRemoteState(remoteFile)) {
                    is RemoteStateResult.Found -> r.state
                    // 文件此刻不存在：GET 会以 404 报错，沿原路径处理
                    RemoteStateResult.NotFound -> null
                    is RemoteStateResult.Error ->
                        throw IllegalStateException("查询远端词库状态失败（${r.message}），本次同步已中止")
                }
            }
            val tempFile = File(appContext.cacheDir, "webdav-download-${dictName}-${System.nanoTime()}.txt")
            try {
                val request = Request.Builder()
                    .url(remoteUrl(remoteFile))
                    .header("Authorization", authHeader())
                    // 显式声明不接受压缩：否则 OkHttp 自动带 gzip，
                    // 服务器（如 Apache mod_deflate）会据此改写响应
                    // 的 ETag/弱强表示，污染一切以 GET 头为准的校验
                    .header("Accept-Encoding", "identity")
                    .get()
                    .build()
                // 本次 GET 到的内容的验证器：只捕获作参考（日志与
                // 跨词典 outcome），绝不写基准、绝不参与校验——中间
                // 层会改写 GET 的 ETag 表示（mod_deflate 的 "-gzip"
                // 后缀等），基准与校验一律走 PROPFIND 口径（见下方
                // 夹逼提交点）
                var mergedETag: String? = null
                var mergedETagWeak = false
                var mergedLastModifiedMs = 0L
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        throw WebDavHttpException(404, "同步目录下没有该备份文件")
                    }
                    if (!response.isSuccessful) {
                        throw httpError("下载", response.code, errorDetail(response))
                    }
                    val etagRaw = response.header("ETag")
                    mergedETag = normalizeETag(etagRaw)
                    mergedETagWeak = isWeakETag(etagRaw)
                    mergedLastModifiedMs = parseHttpDateMs(response.header("Last-Modified"))
                    val body = response.body ?: throw IllegalStateException("下载内容为空")
                    if (body.contentLength() == 0L) {
                        throw IllegalStateException("下载的文件为空")
                    }
                    // 大小上限：服务器配错/被替换成大文件时不能把磁盘和内存撑爆。
                    // 先看声明长度，流式拷贝时再按实际上限截断（防无长度/谎报长度）
                    if (body.contentLength() > UserDictManager.MAX_DICT_FILE_BYTES) {
                        throw IllegalStateException("远端文件过大，拒绝下载")
                    }
                    tempFile.outputStream().use { out ->
                        body.byteStream()
                            .copyToWithLimit(out, UserDictManager.MAX_DICT_FILE_BYTES)
                    }
                }
                // Rime 原生同步快照（*.userdb.txt）列序为「码⇥词⇥权重」，
                // 先转置为「词⇥码⇥权重」再走合并导入
                if (remoteFile.endsWith(".userdb.txt", ignoreCase = true)) {
                    tempFile.writeBytes(convertUserDbSnapshot(tempFile.readBytes()))
                }
                // 导入为合并操作：只增不减，不会删除本地已有词；
                // 热导入直接写运行中引擎的词库，导入后立即生效，无需重启
                // 大文件分批导入，避免整段独占引擎线程导致同步期间按键卡顿
                val count = session.runOnReady {
                    UserDictManager.importUserDictInChunks(tempFile) { chunk ->
                        importUserDictLive(dictName, chunk.absolutePath)
                    }
                }
                if (count < 0) throw IllegalStateException("导入用户词典失败")
                // 任意下载合并成功都记一笔显示时间（含跨词典下载，
                // 它不建 per-file 基准，但「上次下载」应更新；仅显示）
                UserDictPrefs.noteAnyDownload(System.currentTimeMillis())
                // 删除词表随词典同步一起走（并集合并，失败不影响词
                // 典下载结果）。放在夹逼之前：它有自己的一套 GET→
                // 合并→守卫 PUT，不属于夹逼要证明的范围；夹逼中止
                // （含无 ETag 不可验证而中止）不应连带跳过它，否则
                // 无 ETag 服务器上远端删除事件永远同步不下来
                runCatching { syncDeletedWords() }
                    .onFailure { Timber.w(it, "Deleted words sync failed") }
                // 基准/锚点提交点：响应体完整读取、转置与导入全部成功
                // 才到这里，此前的任何失败都不会留下基准。同名文件才
                // 建立基准（跨词典下载合并的是别的文件，不代表本词典
                // 的同步文件已被合并过）。基准取合并后重查的 PROPFIND
                // 状态，且必须先通过夹逼：与 GET 前的 PROPFIND 状态
                // 一致，才证明导入的就是当前远端那一份；GET 期间远
                // 端被他设备改动则中止（导入已在本地生效，重试时会
                // 把新版本再合并进来；服务器无 ETag 时夹逼无从验
                // 证、恒中止，见 mergedStateMatchesCurrent）
                var mergedState: RemoteState? =
                    RemoteState(mergedETag, mergedLastModifiedMs, mergedETagWeak)
                if (sameName) {
                    mergedState = when (val r = fetchRemoteState(remoteFile)) {
                        is RemoteStateResult.Found -> {
                            val post = r.state
                            if (preState == null ||
                                !mergedStateMatchesCurrent(preState.etag, post.etag)
                            ) {
                                throw IllegalStateException(
                                    if (preState != null && preState.etag == null) {
                                        // GET 前状态无 ETag：夹逼无从验证，
                                        // 不是断言已被改动，而是证明不了未
                                        // 被改动——话术按不可验证口径，且
                                        // 重试同样无法通过验证，不作重试邀
                                        // 请。本地已导入的合并内容保留
                                        "该服务器不提供 ETag 强校验，无法确认同步期间远端词库未被其他设备修改，" +
                                            "本次同步已中止（已下载合并的内容已保留在本地）"
                                    } else {
                                        "远端词库在同步期间又被其他设备修改，本次同步已中止以避免覆盖。" +
                                            "请重新点一次同步（会先自动合并远端最新内容）"
                                    }
                                )
                            }
                            post
                        }
                        // 合并期间远端被删：本地合并结果保留，锚点为空，
                        // 上传路径据此退化为新建；基准不写
                        RemoteStateResult.NotFound -> null
                        is RemoteStateResult.Error ->
                            throw IllegalStateException("查询远端词库状态失败（${r.message}），本次同步已中止")
                    }
                    // 夹逼通过才写基准：ETag（PROPFIND 口径）与
                    // per-file 下载时间在同一点写入
                    if (mergedState != null) {
                        mergedState.etag?.let { UserDictPrefs.setSyncETag(remoteFile, it) }
                        UserDictPrefs.setLastDownloadTime(remoteFile, System.currentTimeMillis())
                    }
                }
                Timber.i("User dict '$dictName' downloaded and imported: $count entries")
                DownloadOutcome(count, mergedState)
            } finally {
                tempFile.delete()
            }
        }
    }

    /** 删除词表在服务器上的文件名（全设备共用一份，与具体词典无关） */
    const val DELETED_WORDS_FILE = "jime_deleted_words.txt"

    /** 删除词表大小上限（4MB 约可存数十万条删除词，远超实际用量） */
    private const val DELETED_WORDS_MAX_BYTES = 4L * 1024 * 1024

    /**
     * 同步长按删除词表：逐词按时间戳合并（见 DeletedWordsStore 的记录格式）。
     *
     * 删除与恢复都是带时间戳的事件、逐词取较新状态；旧格式远端文件
     * （每行一个词）按「已删、时间 0」兼容读取，下次 PUT 自动升级为新格式。
     * GET 远端 → 合并应用到本地 → 合并结果与远端不同才 PUT 回。前置
     * 条件与词典上传同一口径（[putPrecondition] 归一构造）；覆盖已
     * 存在的远端文件时，只有强 ETag 的 If-Match 算并发保护——前置
     * 条件双空、或只有秒精度的修改时间条件（弱/无 ETag），都中止
     * 本次同步，不覆盖他设备刚写入的删词事件（见
     * [isUnprotectedOverwrite]）；412 时重走一次合并。
     * 必须在后台线程调用；失败抛异常由调用方决定是否忽略。
     */
    private fun syncDeletedWords(retried: Boolean = false) {
        val store = com.jobeen.ime.data.manager.DeletedWordsStore
        var remoteExists = false
        var remoteETag: String? = null
        var remoteETagWeak = false
        var remoteLastModifiedMs = 0L
        val remote = mutableMapOf<String, com.jobeen.ime.data.manager.DeletedWordEntry>()
        val getRequest = Request.Builder()
            .url(remoteUrl(DELETED_WORDS_FILE))
            .header("Authorization", authHeader())
            // 同 downloadLocked：不接受压缩，防 GET 的 ETag 表示被
            // 中间层改写后拿去发 If-Match 恒 412
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        client.newCall(getRequest).execute().use { response ->
            when {
                response.code == 404 -> Unit // 远端还没有这份文件，按空集处理
                !response.isSuccessful ->
                    throw httpError("下载删除词表", response.code, errorDetail(response))
                else -> {
                    remoteExists = true
                    val etagRaw = response.header("ETag")
                    remoteETag = normalizeETag(etagRaw)
                    remoteETagWeak = isWeakETag(etagRaw)
                    remoteLastModifiedMs =
                        parseHttpDateMs(response.header("Last-Modified"))
                    // 删除词表是每行一条记录的小文件，设上限防止异常内容撑爆内存
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        response.body?.byteStream()
                            ?.copyToWithLimit(out, DELETED_WORDS_MAX_BYTES)
                        out.toByteArray()
                    }
                    String(bytes, Charsets.UTF_8).lineSequence().forEach { line ->
                        com.jobeen.ime.data.manager.decodeDeletedWordEntry(line)
                            ?.let { (word, entry) -> remote[word] = entry }
                    }
                }
            }
        }
        // 合并对着当前本地状态在存储锁内完成：网络往返期间用户的新
        // 增删除/恢复直接参与本次合并，不会被旧快照的合并结果整表
        // 覆盖吞掉（旧实现先取快照、往返后整表回写，窗口内的修改
        // 永久丢失）
        val merged = store.applyRemoteMerge(remote)
        if (merged != remote) {
            val content = merged.entries
                .sortedBy { it.key }
                .mapNotNull { (word, entry) ->
                    com.jobeen.ime.data.manager.encodeDeletedWordEntry(word, entry)
                }
                .joinToString("\n", postfix = "\n")
            val putBuilder = Request.Builder()
                .url(remoteUrl(DELETED_WORDS_FILE))
                .header("Authorization", authHeader())
                .put(content.toRequestBody(textPlain))
            // 与词典上传同一口径：三分支统一先算前置条件，再按同
            // 一守卫判定——覆盖已存在文件时，前置条件双空或只有秒
            // 精度的时间条件（弱/无 ETag）都察觉不了 GET 之后他设
            // 备刚写入的删词事件（同秒写入对日期条件不可见），按
            // 同一守卫中止（异常经调用方 runCatching 仅记日志；本
            // 地合并已落盘，下次同步重走 GET→合并→PUT）
            val pre = putPrecondition(remoteETag, remoteETagWeak, remoteLastModifiedMs)
            if (isUnprotectedOverwrite(pre, remoteExists)) {
                throw IllegalStateException(unprotectedOverwriteMessage(pre))
            }
            pre.ifMatch?.let { putBuilder.header("If-Match", it) }
            pre.ifUnmodifiedSince?.let {
                putBuilder.header("If-Unmodified-Since", it)
            }
            if (!remoteExists) {
                // 远端文件尚不存在（首次同步）：只允许"不存在才创建"，防止两台
                // 设备同时首同步时后写者无条件覆盖先写者的结果
                putBuilder.header("If-None-Match", "*")
            }
            client.newCall(putBuilder.build()).execute().use { response ->
                if (response.code == 412 && !retried) {
                    // 远端刚被其他设备改动（或抢先创建）：重走一次合并即收敛
                    syncDeletedWords(retried = true)
                    return
                }
                if (!response.isSuccessful) {
                    throw httpError("上传删除词表", response.code, errorDetail(response))
                }
                // 不再回写删词文件的 ETag 基准：本链每轮都重新 GET、
                // 从不查存量基准（syncETag 唯一读取点是词典文件的
                // isRemoteChanged），旧回写是无人读的死写，已删除
            }
        }
        Timber.i("Deleted words synced: remote=${remote.size}, merged=${merged.size}")
    }

    /**
     * 测试连接：对同步目录做 PROPFIND，返回目录内的所有条目（文件和子目录）。
     * 必须在后台线程调用。
     */
    suspend fun testConnection(): Result<List<RemoteEntry>> = withContext(Dispatchers.IO) {
        runCatching { parseRemoteEntries(propfindDirXml()) }
    }
}

private val xmlEntityRegex =
    Regex("&(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);")

/**
 * 还原 XML 文本中的实体引用（PROPFIND 的 href 来自原始 XML 文本，
 * 文件名含 & 等字符时服务器会写成 &amp;，不还原会与本地名对不上）。
 * 单趟替换，&amp; 不会被二次展开。
 */
internal fun unescapeXmlEntities(text: String): String =
    xmlEntityRegex.replace(text) { m ->
        val body = m.groupValues[1]
        when (body) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> runCatching {
                val cp = if (body.startsWith("#x")) {
                    body.substring(2).toInt(16)
                } else {
                    body.substring(1).toInt()
                }
                String(Character.toChars(cp))
            }.getOrDefault(m.value)
        }
    }

/** 原始 ETag 文本是否为弱校验形式（W/ 前缀） */
internal fun isWeakETag(raw: String?): Boolean =
    raw?.trimStart()?.startsWith("W/") == true

/** 上传前置条件：If-Match 与 If-Unmodified-Since 至多取其一 */
internal data class PutPrecondition(
    val ifMatch: String?,
    val ifUnmodifiedSince: String?,
)

/**
 * 按基准状态构造 PUT 前置条件：
 * - 强 ETag → If-Match（带引号）；
 * - 弱 ETag → 不能用于 If-Match 强比较（硬发永远 412），改用
 *   If-Unmodified-Since（基准的修改时间）；
 * - 无 ETag → 同样降级为 If-Unmodified-Since（有时间时），与弱
 *   ETag 分支同口径——此前无 ETag 时直接丢弃已有时间、裸 PUT，
 *   保护反而弱于弱 ETag 服务器；
 * - 弱 ETag 且无时间、或 ETag 与时间皆无 → 前置条件双空。
 *   本函数只负责构造；「算不算并发保护」由 [isUnprotectedOverwrite]
 *   判定：覆盖已存在的文件时，只有强 ETag 的 If-Match 算保护——
 *   双空固然无拦截，日期条件也只有秒精度，合并校验之后、PUT
 *   之前他设备在同一秒内的并发写入对它不可见，PUT 照样被服务
 *   器接受并覆盖。两种形态调用方都按 [isUnprotectedOverwrite]
 *   中止本次上传；只有远端文件不存在（首次新建、另有
 *   If-None-Match 限定）时才可放行。（第十四批时日期条件曾被
 *   当作降级保护放行覆盖，主报告 O1 指出其同秒盲区，第十六批
 *   改判；.17 复核 R2 的双空口径一并归入同一守卫。）
 */
internal fun putPrecondition(
    normalizedETag: String?,
    etagWeak: Boolean,
    lastModifiedMs: Long,
): PutPrecondition {
    if (normalizedETag.isNullOrEmpty()) {
        return PutPrecondition(
            null,
            if (lastModifiedMs > 0L) formatHttpDate(lastModifiedMs) else null,
        )
    }
    if (!etagWeak) return PutPrecondition("\"$normalizedETag\"", null)
    return PutPrecondition(
        null,
        if (lastModifiedMs > 0L) formatHttpDate(lastModifiedMs) else null,
    )
}

/**
 * 该 PUT 是否会在无并发保护下覆盖一个已存在的远端文件：远端
 * 文件已存在、且前置条件里没有强 ETag 的 If-Match 时为真——
 * 无论前置条件是双空，还是只有秒精度的 If-Unmodified-Since。
 * 合并校验已在更早的时点做完，其后到 PUT 之间他设备的并发写
 * 入：双空时无从拦截；只有日期条件时，同秒内的写入不改变
 * Last-Modified 字符串、条件必然通过——两种形态下 PUT 都会被
 * 服务器直接接受、覆盖掉本机未合并的内容，调用方必须中止本
 * 次上传。远端文件不存在（首次新建）时不算：新建由
 * If-None-Match 限定，没有可被覆盖的既有内容。（判据沿革：
 * 第十四批只收双空，日期条件算降级保护；第十六批按主报告 O1
 * 把日期-only 并入无保护，判据收敛为「无 If-Match 即无保护」。）
 */
internal fun isUnprotectedOverwrite(
    pre: PutPrecondition,
    remoteExists: Boolean,
): Boolean =
    remoteExists && pre.ifMatch == null

/**
 * 无并发保护中止上传时的用户话术，按前置条件形态取句，两处
 * 调用点（词典上传、删词同步）共用同一口径：
 * - 只有秒精度的日期条件：点明同秒写入不可见这一具体风险；
 * - 双空：沿用第十四批 R2 的原文。
 */
internal fun unprotectedOverwriteMessage(pre: PutPrecondition): String =
    if (pre.ifUnmodifiedSince != null) {
        "该服务器只能提供秒精度的修改时间校验（无可用的 ETag 强校验），" +
            "同一秒内其他设备刚写入的内容无法被察觉，继续上传可能将其覆盖，本次上传已中止"
    } else {
        "该服务器无法提供并发保护（无可用的 ETag / 修改时间校验），" +
            "继续上传可能覆盖其他设备刚写入的内容，本次上传已中止"
    }

/**
 * 「远端是否自本机基准后被改动」的纯决策（基准是 per-file 口径
 * 的 ETag 入参，本函数不读存储）：
 * - 远端有 ETag：从未记录过基准（本机第一次同步该文件）也视为已
 *   变——远端已有内容必须先合并，绝不直接覆盖；
 * - 远端没 ETag 但本机存量基准有：ETag 消失本身保守视为已变；
 * - 两边都无 ETag：恒视为已变，每次都先下载合并。旧口径曾退化
 *   为「远端修改时间 vs 本文件上次成功上传/下载时间（本机时
 *   钟）」的比较，但本机时间证明不了远端内容身份：远端时间是
 *   HTTP 日期的整秒值，本机记录是 PUT 完成时刻的真毫秒，他设
 *   备在 PUT 落盘后、响应返回前同秒追加的新版本必然被判「未
 *   变化」而跳过合并、下轮被覆盖（主报告 O2）。故第十六批删
 *   除时间支：本机上传/下载时间只作展示/日志记录，不再入参。
 */
internal fun remoteChangedDecision(
    knownETag: String?,
    stateETag: String?,
): Boolean {
    if (stateETag != null) {
        return knownETag == null || knownETag != stateETag
    }
    return true
}

/**
 * 两份远端状态是否指向同一版本的纯决策（下载合并的夹逼专
 * 用；两侧入参必须同口径，如同为 PROPFIND 状态。PUT 后补查
 * 走 [postPutRecheckAdoptable]，不共用本函数）：
 * - 前者带 ETag：后者 ETag 必须与之全等（后者无 ETag 亦为不
 *   一致——期间文件被换过或验证器被撤下，锚定上传都会覆盖
 *   未合并的内容）；
 * - 前者无 ETag：无法证明一致，恒判不一致。旧口径曾退化为
 *   修改时间全等比较，但 HTTP 修改时间只有秒精度：他设备
 *   在 GET＋导入期间同秒写入时时间不变，夹逼会被骗过、把
 *   偏旧的合并基准采纳为已验证状态——「时间相同」证明不了
 *   「内容相同」。与 [remoteChangedDecision]、
 *   [postPutRecheckAdoptable] 同一口径：无强 ETag 即无内容
 *   身份（第十七批，闭第十六批审查 (f) 的残余歧义；签名随
 *   之收敛，两个时间入参删除）。上层据此中止本次同步；本
 *   地已导入内容是逐词时间戳并集合并、不丢词，下轮同步仍
 *   会重新下载合并。
 */
internal fun mergedStateMatchesCurrent(
    mergedETag: String?,
    currentETag: String?,
): Boolean {
    if (mergedETag != null) return mergedETag == currentETag
    return false
}

/**
 * PUT 成功但响应没带 ETag 时，补查状态是否可采纳为新基准的纯
 * 决策。采纳的唯一判据：补查 ETag 与 PUT 前锚点强 ETag 全等——
 * 远端仍是已合并的那一份（如内容寻址 ETag 且内容恰好相同）。
 * 其余情形（补查 ETag 已变或缺失、锚点为弱 ETag、PUT 前无锚点）
 * 一律不采纳：不写基准，下次上传会重新 GET 合并，绝不把未合并
 * 内容记成已合并基准。
 * 旧版曾以「补查修改时间落在（锚点时间，PUT 完成时刻]」的时
 * 间窗采纳变化后的 ETag，并注称时钟偏差只会保守拒绝、不会丢
 * 词——该说法不成立：服务器修改时间只有秒精度、且与本机时钟
 * 可不一致，他设备在 PUT 落盘后同秒（乃至响应返回前）追加的
 * 新版本同样落进窗口，时间窗证明不了内容身份。故时间窗支已
 * 删除，修改时间不再参与本判据（.17 复核 R1）。
 */
internal fun postPutRecheckAdoptable(
    anchorETag: String?,
    anchorETagWeak: Boolean,
    recheckETag: String?,
): Boolean {
    if (recheckETag == null) return false
    return anchorETag != null && !anchorETagWeak && recheckETag == anchorETag
}

private fun httpDateFormat(): java.text.SimpleDateFormat =
    java.text.SimpleDateFormat(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US,
    ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }

/** 毫秒时间戳 → HTTP 日期串（GMT） */
internal fun formatHttpDate(ms: Long): String =
    httpDateFormat().format(java.util.Date(ms))

/** HTTP 日期串 → 毫秒时间戳；解析失败返回 0 */
internal fun parseHttpDateMs(text: String?): Long {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return 0L
    return runCatching { httpDateFormat().parse(t)?.time ?: 0L }.getOrDefault(0L)
}

/**
 * 百分号解码 href。按 Unicode 码点迭代：旧实现按 char 逐个转 UTF-8，
 * 星平面字符（代理对）会被拆开各自编码成替换符而损坏文件名。
 */
internal fun decodeHrefImpl(href: String): String {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < href.length) {
        val c = href[i]
        if (c == '%' && i + 2 < href.length) {
            val b = href.substring(i + 1, i + 3).toIntOrNull(16)
            if (b != null) {
                out.write(b)
                i += 3
                continue
            }
        }
        val cp = Character.codePointAt(href, i)
        out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
        i += Character.charCount(cp)
    }
    return out.toString(Charsets.UTF_8.name())
}
