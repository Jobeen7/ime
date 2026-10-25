package com.jobeen.ime.engine.rime.data.userdict

import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.DataManager
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
     * 两次同步会交错读写同一远端文件。upload 内部的合并下载
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

    private const val MAX_SCHEME_FILE_BYTES = 512L * 1024 * 1024

    private fun schemesUrl(): String = "${UserDictPrefs.syncDirUrl()}/schemes"

    private suspend fun ensureSchemesDir() {
        ensureSyncDir()
        val request = Request.Builder()
            .url(schemesUrl())
            .header("Authorization", authHeader())
            .method("MKCOL", "".toRequestBody(null))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 405) return@use
                if (!response.isSuccessful) Timber.w("创建方案目录失败：${response.code} ${errorDetail(response)}")
            }
        }.onFailure { Timber.w("创建方案目录异常：${it.message}") }
    }

    private fun isProtectedSchemeRel(rel: String): Boolean {
        val segments = rel.split('/')
        for (segment in segments) {
            if (segment == "build" || segment.endsWith(".userdb")) return true
        }
        val fileName = segments.last()
        return fileName.endsWith(".log") || fileName.endsWith(".tmp")
    }

    private fun isValidSchemeRel(rel: String): Boolean {
        if (rel.isEmpty() || rel.startsWith('/')) return false
        val segments = rel.split('/')
        for (segment in segments) {
            if (segment.isEmpty() || segment == "..") return false
        }
        return !isProtectedSchemeRel(rel)
    }

    private fun parseSchemesEntries(xml: String, schemesPath: String): List<Pair<String, Boolean>> {
        val seen = mutableSetOf<Pair<String, Boolean>>()
        val result = mutableListOf<Pair<String, Boolean>>()
        for (match in responseRegex.findAll(xml)) {
            val hrefMatch = hrefRegex.find(match.value)?.destructured?.component1() ?: continue
            val path = unescapeXmlEntities(decodeHref(hrefPath(hrefMatch))).trimEnd('/')
            if (path != schemesPath && !path.startsWith(schemesPath + "/")) continue
            val rel = path.removePrefix(schemesPath).trimStart('/')
            if (rel.isEmpty()) continue
            if (rel.split('/').any { it == ".." }) continue
            val isDirectory = collectionRegex.containsMatchIn(match.value)
            val pair = rel to isDirectory
            if (pair in seen) continue
            seen.add(pair)
            result.add(pair)
        }
        return result
    }

    suspend fun uploadSchemes(): Result<Int> = syncMutex.withLock {
        withContext(Dispatchers.IO) { runCatching {
            checkConfigured()
            ensureSchemesDir()
            val userDir = DataManager.userDataDir
            val root = userDir.canonicalFile
            var count = 0
            root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
                .filter { it.split('/').size <= 6 }
                .filter { isValidSchemeRel(it) }
                .take(1000)
                .forEach { rel ->
                    val file = File(root, rel)
                    val request = Request.Builder()
                        .url(remoteUrl("schemes/$rel"))
                        .header("Authorization", authHeader())
                        .put(file.asRequestBody("application/octet-stream".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw httpError("上传方案文件", response.code, errorDetail(response))
                    }
                    count++
                }
            count
        } }
    }

    suspend fun downloadSchemes(): Result<Int> = syncMutex.withLock {
        withContext(Dispatchers.IO) { runCatching {
            checkConfigured()
            ensureSchemesDir()
            val userDir = DataManager.userDataDir
            val root = userDir.canonicalFile
            val schemesPath = runCatching { URI(schemesUrl()).path }.getOrDefault("").trimEnd('/')
            val files = mutableListOf<String>()
            suspend fun collect(dirRel: String, depth: Int) {
                if (depth > 4 || files.size >= 1000) return
                val dirUrl = if (dirRel.isEmpty()) schemesUrl() + "/" else schemesUrl() + "/" + dirRel + "/"
                val entries = parseSchemesEntries(propfindDirXml(dirUrl), schemesPath)
                for ((rel, isDir) in entries) {
                    if (files.size >= 1000) return
                    if (isDir) {
                        if (depth < 4) collect(rel, depth + 1)
                    } else if (isValidSchemeRel(rel) && rel !in files) {
                        files.add(rel)
                    }
                }
            }
            collect("", 0)
            var count = 0
            for (rel in files) {
                val target = File(root, rel)
                val canonical = target.canonicalFile
                if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                    Timber.w("skip schemes download outside root: $rel")
                    continue
                }
                val parent = target.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                val tmp = File(parent, ".${target.name}.tmp-${System.nanoTime()}")
                try {
                    val request = Request.Builder()
                        .url(remoteUrl("schemes/$rel"))
                        .header("Authorization", authHeader())
                        .header("Accept-Encoding", "identity")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 404) return@use
                        if (!response.isSuccessful) throw httpError("下载方案文件", response.code, errorDetail(response))
                        val body = response.body ?: throw IllegalStateException("下载内容为空")
                        tmp.outputStream().use { out -> body.byteStream().copyToWithLimit(out, MAX_SCHEME_FILE_BYTES) }
                    }
                    if (!tmp.exists()) {
                        Timber.w("skip schemes download vanished remote: $rel")
                        continue
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    count++
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            }
            count
        } }
    }

    // 同步口径（清点 S1 定论施工）：ETag 状态机整套退场——不再
    // PROPFIND 查状态、不再判变、不再记 ETag 基准。下载恒 GET＋合并，
    // 上传恒先下载合并再无条件裸 PUT（第二十批形态），防丢词靠合并
    // 本身。ETag 相关纯函数（captureETag/putPrecondition/
    // remoteChangedDecision/postPutRecheckAdoptable 等）已一并删除。

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
            // 冲突保护：无条件先下载合并进本地再上传，绝不拿本地旧
            // 词库静默覆盖远端较新的内容（清点 S1 定论：不再判变，
            // 恒先合并；原 PROPFIND 状态查询与 Error 中止支一并退
            // 场——S4）。远端文件不存在（GET 404）即首次上传、跳
            // 过合并直接新建；其余下载失败（网络/认证等）中止本
            // 次上传——没合并成就不许覆盖远端。
            downloadLocked(dictName, session).exceptionOrNull()?.let { e ->
                if (!(e is WebDavHttpException && e.httpCode == 404)) throw e
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
                // 无条件裸 PUT，不带任何前置条件（第二十批终局
                // 形态，Jobeen 口径：上传就是要成功）：第十八批
                // 「有什么发什么」的 If-Match、第十九批弱 ETag 置
                // 空后仅剩的日期条件 If-Unmodified-Since，在真机上
                // 都被服务器恒回 412、上传永远失败（两轮真机坐
                // 实）。防丢词靠上方上传前的下载合并本身：远端较
                // 新的词已先并进本地、随这份合并结果一起写回，不
                // 靠前置条件。412 若仍出现（服务器自身原因）走下
                // 方通用失败口径，不再有专属话术。
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw httpError("上传", response.code, errorDetail(response))
                    }
                    // 成功即完成：不再读响应 ETag、不再补查记基准
                    // （清点 S1 定论，ETag 状态机整套退场）
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
    private suspend fun propfindDirXml(dirUrl: String): String = withContext(Dispatchers.IO) {
        checkConfigured()
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(dirUrl)
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
            parseRemoteEntries(propfindDirXml("${UserDictPrefs.syncDirUrl()}/"))
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
        downloadLocked(dictName, session, remoteFile)
    }

    private suspend fun downloadLocked(
        dictName: String,
        session: RimeSession,
        remoteFile: String = remoteFileName(dictName),
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            checkConfigured()
            val sameName = remoteFile == remoteFileName(dictName)
            // 下载成败只由 GET 与合并导入决定（第十八批口径）：
            // 直接 GET，不再有 PROPFIND 前后观察与补查记账（清点
            // S1 定论）；远端不存在、网络/认证错误等真失败由 GET
            // 本身的错误口径报出。
            val tempFile = File(appContext.cacheDir, "webdav-download-${dictName}-${System.nanoTime()}.txt")
            try {
                val request = Request.Builder()
                    .url(remoteUrl(remoteFile))
                    .header("Authorization", authHeader())
                    // 显式声明不接受压缩：与 OkHttp 默认的透明 gzip
                    // 解压口径保持一致的裸字节落盘，避免个别服务器
                    // 对 gzip 请求回不同的内容表示
                    .header("Accept-Encoding", "identity")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        throw WebDavHttpException(404, "同步目录下没有该备份文件")
                    }
                    if (!response.isSuccessful) {
                        throw httpError("下载", response.code, errorDetail(response))
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
                // 典下载结果）：它有自己的一套 GET→合并→PUT，保证
                // 远端删除事件每轮下载都被同步到
                runCatching { syncDeletedWords() }
                    .onFailure { Timber.w(it, "Deleted words sync failed") }
                // 同名下载记一笔该文件的下载时刻（仅显示用）
                if (sameName) {
                    UserDictPrefs.setLastDownloadTime(remoteFile, System.currentTimeMillis())
                }
                Timber.i("User dict '$dictName' downloaded and imported: $count entries")
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
     * 同步长按删除词表：逐词按时间戳合并（见 DeletedWordsStore 的记录格式）。
     *
     * 删除与恢复都是带时间戳的事件、逐词取较新状态；旧格式远端文件
     * （每行一个词）按「已删、时间 0」兼容读取，下次 PUT 自动升级为新格式。
     * GET 远端 → 合并应用到本地 → 合并结果与远端不同才 PUT 回。PUT
     * 无条件裸发、不带前置条件（远端不存在时带 If-None-Match 新建
     * 限定，412 时重走一次合并）。第二十批起与词典上传同口径：原「无并发
     * 保护即中止本次 PUT」的守卫已删除——第十九批把弱 ETag 置空
     * 后，弱 ETag 服务器上只要远端删词文件已存在守卫就恒触发，
     * 删词事件永远同步不出去且无任何提示（清点第 3 路坐实）。删
     * 词失败只经调用方 runCatching 记日志，不影响用户可见的同步
     * 成败。
     * 必须在后台线程调用；失败抛异常由调用方决定是否忽略。
     */
    private fun syncDeletedWords(retried: Boolean = false) {
        val store = com.jobeen.ime.data.manager.DeletedWordsStore
        var remoteExists = false
        val remote = mutableMapOf<String, com.jobeen.ime.data.manager.DeletedWordEntry>()
        val getRequest = Request.Builder()
            .url(remoteUrl(DELETED_WORDS_FILE))
            .header("Authorization", authHeader())
            // 同 downloadLocked：不接受压缩，保证裸字节落盘口径一致
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
                    // 本链 PUT 自第二十批收尾起无条件裸发，不再捕获
                    // GET 的 ETag / 修改时间作前置条件（见下方 PUT 处）
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
            // 无条件裸 PUT，不带任何前置条件（与词典上传同口
            // 径，第二十批收尾）：原「无 If-Match 即中止本次
            // PUT」的守卫已于第二十批删除（见函数头注释），残
            // 留的前置条件附加一并去除——坚果云上弱 ETag 被置
            // 空后只剩日期条件 If-Unmodified-Since，与词典上传
            // 同一 412 风险，且本链失败仅记日志、不可见。逐词
            // 时间戳合并本身即防丢的实际机制，GET 之后的并发
            // 写入至多在下轮同步再合并。
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
            }
        }
        Timber.i("Deleted words synced: remote=${remote.size}, merged=${merged.size}")
    }

    /**
     * 测试连接：对同步目录做 PROPFIND，返回目录内的所有条目（文件和子目录）。
     * 必须在后台线程调用。
     */
    suspend fun testConnection(): Result<List<RemoteEntry>> = withContext(Dispatchers.IO) {
        runCatching { parseRemoteEntries(propfindDirXml("${UserDictPrefs.syncDirUrl()}/")) }
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
