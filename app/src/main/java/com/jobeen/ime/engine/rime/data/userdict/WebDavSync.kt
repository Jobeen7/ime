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
     * mergedState 取合并后重查的 PROPFIND 状态，不用 GET 响应头
     * 当基准：中间层可能改写 GET 的 ETag 表示（Apache mod_deflate
     * 默认给 GET 响应 ETag 加 "-gzip" 后缀、PROPFIND 的 getetag
     * 不变）。第十八批起下载不再做合并前后的一致性校验（成功就
     * 是成功，见 downloadLocked），补查只为基准记账与上传锚点；
     * 补查状态须与合并前观察到的状态 ETag 全等才被采纳，未采
     * 纳（期间被改动、无从比对或补查失败）时 mergedState 退化
     * 为 GET 响应头口径、仍非空。远端在合并期间被其他设备删
     * 除时 mergedState 为 null（上传路径退化为新建）。
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

    // PUT 前置条件（If-Match / If-Unmodified-Since）的构造走文件
    // 末尾的 putPrecondition()：词典上传自第二十批起无条件裸 PUT，
    // 删词同步自第二十批收尾（v1.1.13.5）起同改裸 PUT——该函数自
    // 此已无生产调用方，本体与既有单测暂留、清点在册待裁。

    /**
     * 查询某个远端文件的 ETag / 修改时间（PROPFIND Depth: 0），返回三态结果：
     * 404 → [RemoteStateResult.NotFound]（可安全新建）；2xx →
     * [RemoteStateResult.Found]；其余错误码与任何异常 →
     * [RemoteStateResult.Error]。上传路径在 PUT 之前查到 Error 时
     * 中止本次同步（状态不可判定就不进入合并/上传流程）；下载路径
     * 合并后的补查只作记账与锚点，Error 不影响已成功的下载。
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
                // 弱 ETag 在捕获点即置空（captureETag），全路径按无
                // ETag 处理；保留下来的 ETag 必为强校验形式，故
                // etagWeak 恒为 false
                val etag = captureETag(etagRaw?.let { unescapeXmlEntities(it) })
                val lmText = lastModifiedRegex.find(xml)?.groupValues?.get(1)?.trim().orEmpty()
                RemoteStateResult.Found(
                    RemoteState(etag, parseHttpDateMs(lmText), etagWeak = false),
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
            var baseState = when (val r = fetchRemoteState(fileName)) {
                is RemoteStateResult.Found -> r.state
                RemoteStateResult.NotFound -> null
                is RemoteStateResult.Error ->
                    throw IllegalStateException("查询远端词库状态失败（${r.message}），本次同步已中止")
            }
            if (baseState != null && isRemoteChanged(fileName, baseState)) {
                Timber.i("Remote '$fileName' changed since last sync; merging before upload")
                val merged = downloadLocked(dictName, session).getOrThrow()
                // downloadLocked 不做合并前后的状态一致性校验
                // （第十八批回退：同步以可用为先）；mergedState 是
                // 合并后重查的 PROPFIND 状态，直接作 PUT 锚点。
                // 合并期间远端被删（mergedState 为 null）则按不
                // 存在处理，下方 PUT 直接新建。
                baseState = merged.mergedState
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
                    val newETag = captureETag(response.header("ETag"))
                    if (newETag != null) {
                        UserDictPrefs.setSyncETag(fileName, newETag)
                    } else {
                        // 响应没带 ETag：补查一次，但补查状态不得无条
                        // 件当基准——PUT 完成到补查之间他设备可能又写
                        // 入了新版本，那份内容本机从未合并，记成基准会
                        // 让下次上传跳过合并、直接覆盖它。
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
            // 下载成败只由 GET 与合并导入决定：不再以合并前后的
            // 状态一致性作成功闸门（第十八批回退强校验语义，
            // Jobeen 决定：同步以可用为先、成功就是成功）。同名
            // 文件仍在 GET 前观察一次 PROPFIND 状态——仅用于下
            // 方提交点判断补查状态能否记基准/作锚点，查询失败或
            // 不存在都不中止下载；远端不存在、网络/认证错误等
            // 真失败仍由 GET 本身的错误口径报出。
            var preState: RemoteState? = null
            if (sameName) {
                preState = (fetchRemoteState(remoteFile) as? RemoteStateResult.Found)?.state
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
                // 本次 GET 到的内容的验证器：只捕获作参考（日志、
                // 跨词典 outcome、以及合并后 PROPFIND 补查失败时
                // 的锚点兜底），绝不据它写基准——中间层会改写 GET
                // 的 ETag 表示（mod_deflate 的 "-gzip" 后缀等），
                // 基准记账一律走 PROPFIND 口径（见下方提交点）
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
                    mergedETag = captureETag(etagRaw)
                    // 弱 ETag 已被 captureETag 置空（视为不存在），
                    // 保留下来的必为强校验形式，弱标记恒为 false
                    mergedETagWeak = false
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
                // 典下载结果）。位置维持第十七批的前置形态：它有
                // 自己的一套 GET→合并→守卫 PUT，与词典文件的基
                // 准记账无关，放在记账之前，保证远端删除事件每轮
                // 下载都被同步到、不被后续步骤的异常连带跳过
                runCatching { syncDeletedWords() }
                    .onFailure { Timber.w(it, "Deleted words sync failed") }
                // 基准/锚点提交点：响应体完整读取、转置与导入全部成功
                // 才到这里，此前的任何失败都不会留下基准。同名文件才
                // 建立基准（跨词典下载合并的是别的文件，不代表本词典
                // 的同步文件已被合并过）。合并后补查的 PROPFIND 状态
                // 只有与 GET 前观察到的状态 ETag 全等时，才证明它就
                // 是合并内容对应的那一版——此时才记 ETag 基准、才作
                // 上传锚点；期间被他设备改动（ETag 已变或消失）、或
                // 两侧任一无 ETag 无从比对时，不记基准，锚点退回
                // GET 响应头口径（实际合并的正是那一版）。此判断只
                // 决定记账与锚点取哪份状态，任何结果都不影响下载
                // 成败（第十八批：闸门已删，此处不是闸门）。合并期
                // 间远端被删则锚点为空、基准不写；补查失败（网络/
                // 服务器错误）同样退回 GET 头口径
                var mergedState: RemoteState? =
                    RemoteState(mergedETag, mergedLastModifiedMs, mergedETagWeak)
                if (sameName) {
                    mergedState = when (val r = fetchRemoteState(remoteFile)) {
                        is RemoteStateResult.Found -> {
                            val post = r.state
                            val pre = preState
                            if (pre != null && pre.etag != null && post.etag == pre.etag) {
                                UserDictPrefs.setSyncETag(remoteFile, post.etag)
                                post
                            } else {
                                mergedState
                            }
                        }
                        RemoteStateResult.NotFound -> null
                        is RemoteStateResult.Error -> mergedState
                    }
                    UserDictPrefs.setLastDownloadTime(remoteFile, System.currentTimeMillis())
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
            // 写入至多在下轮同步再合并。putPrecondition 自此
            // 已无生产调用方，本体与单测暂留、清点在册待裁。
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

/**
 * ETag 规范形：基准值来自 PUT 响应头、比较值来自 PROPFIND XML，两处
 * 的引号与弱校验 `W/` 前缀表示在部分服务器上不一致，混用会让
 * isRemoteChanged 恒为 true（每次上传都多做一次全量下载合并）。
 * 比较与存储统一为去引号、去 W/ 的裸值；发送 If-Match 时再加回引号。
 * （第十九批由 WebDavSync 成员函数移为顶层纯函数，函数体逐字未动，
 * 以便捕获归一 [captureETag] 与单测直接引用。）
 */
internal fun normalizeETag(raw: String?): String? =
    raw?.trim()?.removePrefix("W/")?.trim()?.removeSurrounding("\"")
        ?.takeIf { it.isNotEmpty() }

/**
 * ETag 捕获归一（第十九批，Jobeen 口径：弱 ETag 没用就不要管它，
 * 不要让它影响 App 使用）：凡从服务器捕获 ETag 的入口（PROPFIND
 * XML、GET/PUT 响应头）统一过此函数——弱校验形式一律视为不存
 * 在、返回 null，使远端判变、锚点/基准记账与前置条件构造在全
 * 路径落到无 ETag 的既有口径（判变恒先合并、PUT 走日期条件或
 * 裸发）；强 ETag 原样规范化，既有行为逐字不变。弱标签按 RFC
 * 不得用于强比较与内容身份判定，其规范化裸值留在链路里只会派
 * 生出服务器恒拒绝的前置条件（412 且重试永不收敛，见 fix19）。
 */
internal fun captureETag(raw: String?): String? =
    if (isWeakETag(raw)) null else normalizeETag(raw)

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
 *   本函数只负责构造。词典上传（第二十批）与删词同步（第二
 *   十批收尾，v1.1.13.5）先后改无条件裸 PUT 后，本函数已无生
 *   产调用方；本体与既有单测暂留、清点在册待裁。（沿革：本函数的构造结果曾另有「算不
 *   算并发保护」的判据 isUnprotectedOverwrite，词典上传与删
 *   词链先后据此中止 PUT；第二十批按 Jobeen 终局口径——上传
 *   就是要成功、防丢靠合并本身——判据与守卫连同话术函数一
 *   并删除。）
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
