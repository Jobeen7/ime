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
        Credentials.basic(UserDictPrefs.username, UserDictPrefs.password)

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
    private data class RemoteState(val etag: String?, val lastModifiedMs: Long)

    private val etagRegex =
        Regex("<(?:\\w+:)?getetag>(.*?)</(?:\\w+:)?getetag>", RegexOption.IGNORE_CASE)
    private val lastModifiedRegex =
        Regex(
            "<(?:\\w+:)?getlastmodified>(.*?)</(?:\\w+:)?getlastmodified>",
            RegexOption.IGNORE_CASE,
        )

    /**
     * 查询某个远端文件的 ETag / 修改时间（PROPFIND Depth: 0）。
     * 文件不存在返回 null；服务器不支持 PROPFIND 等异常同样返回 null（调用方退化为
     * "无状态可比"，保持旧行为直接上传，但仍会带上已知的 If-Match 时才做强校验）。
     */
    private fun fetchRemoteState(fileName: String): RemoteState? {
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
                if (response.code == 404) return null
                if (!response.isSuccessful) return null
                val xml = response.body?.string().orEmpty()
                val etag = etagRegex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                val lmText = lastModifiedRegex.find(xml)?.groupValues?.get(1)?.trim().orEmpty()
                val lmMs = runCatching {
                    val fmt = java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US
                    )
                    fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
                    fmt.parse(lmText)?.time ?: 0L
                }.getOrDefault(0L)
                RemoteState(etag, lmMs)
            }
        }.getOrNull()
    }

    /**
     * 判断远端文件自上次同步后是否被其他设备改动过。
     * 优先比 ETag（上次同步记录的基准值）；无 ETag 时退化为修改时间与
     * 上次同步时间（上传/下载取较晚者）的比较。
     */
    private fun isRemoteChanged(fileName: String, state: RemoteState): Boolean {
        val knownETag = UserDictPrefs.syncETag(fileName)
        if (state.etag != null) {
            // 从未记录过基准（本机第一次同步）但远端已有文件：视为已被改动，先合并再说
            return knownETag == null || knownETag != state.etag
        }
        if (knownETag != null) return true // 远端曾有 ETag 现在没了，保守视为已变
        val lastSync = maxOf(UserDictPrefs.lastUploadTime, UserDictPrefs.lastDownloadTime)
        // 无任何同步基准（本机首次同步、lastSync==0）时不能断言远端未变：
        // 旧实现恒返回 false，首次上传会跳过合并直接覆盖其他设备的词库。
        // 无基准一律视为已变，先下载合并再上传。
        return lastSync == 0L || state.lastModifiedMs > lastSync
    }

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
            // 绝不拿本地旧词库静默覆盖远端较新的内容
            var baseState = fetchRemoteState(fileName)
            if (baseState != null && isRemoteChanged(fileName, baseState)) {
                Timber.i("Remote '$fileName' changed since last sync; merging before upload")
                downloadLocked(dictName, session).getOrThrow()
                baseState = fetchRemoteState(fileName) ?: baseState
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
                // 带上合并基准的 ETag：合并后到上传之间远端又被改动时服务器回 412，
                // 而不是被我们覆盖掉
                baseState?.etag?.let { builder.header("If-Match", it) }
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
                    val newETag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    if (newETag != null) {
                        UserDictPrefs.setSyncETag(fileName, newETag)
                    } else {
                        // 响应没带 ETag：重新查询一次作为下次同步的基准
                        fetchRemoteState(fileName)?.etag?.let {
                            UserDictPrefs.setSyncETag(fileName, it)
                        }
                    }
                }
                UserDictPrefs.lastUploadTime = System.currentTimeMillis()
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
                val path = hrefPath(decodeHref(href)).trimEnd('/')
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
    private fun decodeHref(href: String): String {
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
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

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
    ): Result<Int> = syncMutex.withLock { downloadLocked(dictName, session, remoteFile) }

    private suspend fun downloadLocked(
        dictName: String,
        session: RimeSession,
        remoteFile: String = remoteFileName(dictName),
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            checkConfigured()
            val tempFile = File(appContext.cacheDir, "webdav-download-${dictName}-${System.nanoTime()}.txt")
            try {
                val request = Request.Builder()
                    .url(remoteUrl(remoteFile))
                    .header("Authorization", authHeader())
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        throw WebDavHttpException(404, "同步目录下没有该备份文件")
                    }
                    if (!response.isSuccessful) {
                        throw httpError("下载", response.code, errorDetail(response))
                    }
                    // 下载的就是本词典的同步文件时，记下它的 ETag 作为下次上传的比对基准
                    if (remoteFile == remoteFileName(dictName)) {
                        response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            UserDictPrefs.setSyncETag(remoteFile, it)
                        }
                    }
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
                val count = session.runOnReady {
                    importUserDictLive(dictName, tempFile.absolutePath)
                }
                if (count < 0) throw IllegalStateException("导入用户词典失败")
                UserDictPrefs.lastDownloadTime = System.currentTimeMillis()
                Timber.i("User dict '$dictName' downloaded and imported: $count entries")
                // 删除词表随词典同步一起走（并集合并，失败不影响词典下载结果）
                runCatching { syncDeletedWords() }
                    .onFailure { Timber.w(it, "Deleted words sync failed") }
                count
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
     * 同步长按删除词表：与远端取并集合并。
     *
     * 删除是单调操作（只加不减），并集即正确合并、天然无冲突：
     * 任一设备删过的词，同步后在所有设备上都保持删除，换机也不会复活。
     * GET 远端 → 本地并入 → 并集与远端不同才 PUT 回（带 If-Match 防并发覆盖）。
     * 必须在后台线程调用；失败抛异常由调用方决定是否忽略。
     */
    private fun syncDeletedWords() {
        val local = com.jobeen.ime.data.manager.DeletedWordsStore.all()
        var remoteETag: String? = null
        val remote = mutableSetOf<String>()
        val getRequest = Request.Builder()
            .url(remoteUrl(DELETED_WORDS_FILE))
            .header("Authorization", authHeader())
            .get()
            .build()
        client.newCall(getRequest).execute().use { response ->
            when {
                response.code == 404 -> Unit // 远端还没有这份文件，按空集处理
                !response.isSuccessful ->
                    throw httpError("下载删除词表", response.code, errorDetail(response))
                else -> {
                    remoteETag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    // 删除词表是每行一个词的小文件，设上限防止异常内容撑爆内存
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        response.body?.byteStream()
                            ?.copyToWithLimit(out, DELETED_WORDS_MAX_BYTES)
                        out.toByteArray()
                    }
                    String(bytes, Charsets.UTF_8).lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { remote.add(it) }
                }
            }
        }
        val merged = local union remote
        if (merged.size != local.size) {
            com.jobeen.ime.data.manager.DeletedWordsStore.addAll(merged)
        }
        if (merged != remote) {
            val content = merged.sorted().joinToString("\n", postfix = "\n")
            val putBuilder = Request.Builder()
                .url(remoteUrl(DELETED_WORDS_FILE))
                .header("Authorization", authHeader())
                .put(content.toRequestBody(textPlain))
            remoteETag?.let { putBuilder.header("If-Match", it) }
            client.newCall(putBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw httpError("上传删除词表", response.code, errorDetail(response))
                }
                response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    UserDictPrefs.setSyncETag(DELETED_WORDS_FILE, it)
                }
            }
        }
        Timber.i("Deleted words synced: local=${local.size}, remote=${remote.size}, merged=${merged.size}")
    }

    /**
     * 测试连接：对同步目录做 PROPFIND，返回目录内的所有条目（文件和子目录）。
     * 必须在后台线程调用。
     */
    suspend fun testConnection(): Result<List<RemoteEntry>> = withContext(Dispatchers.IO) {
        runCatching { parseRemoteEntries(propfindDirXml()) }
    }
}
