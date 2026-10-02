package com.jobeen.ime.base.update

import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 万象输入方案在线更新管理器。
 *
 * 下载地址（Jobeen 指定，只用 lite 版，不用 Base/Pro/Pure）：
 * - 方案包：https://github.com/amzxyz/rime-wanxiang/releases/download/{tag}/rime-wanxiang-lite.zip
 * - 语法模型：https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram
 *
 * 安全策略（修改时必须遵守）：
 * 1. 只更新纯数据文件：dicts/ 下的词库 + 语法模型 .gram。
 *    App 内置的 *.schema.yaml 经过深度定制（UI 元数据 layout/kind、能力开关 options、
 *    自定义 filter 链），绝不能被上游覆盖；lua/ 与定制 schema 深度集成，
 *    opencc/ App 未使用，均不更新。
 * 2. 只写 sharedDataDir，绝不碰 userDataDir（用户词库、自定义配置不受影响）。
 * 3. 下载用 .part 临时文件，成功后原子替换；词库先解压到临时目录校验后再搬移。
 * 4. 全部网络与文件 IO 在 Dispatchers.IO，绝不阻塞主线程；下载为流式，
 *    不把整个文件载入内存（语法模型约 400MB）。
 * 5. 词库与语法模型紧耦合（上游 RIME-LMDG 说明），必须配套更新，不单独更新其中之一。
 */
object WanxiangUpdateManager {

    private const val SCHEMA_REPO = "amzxyz/rime-wanxiang"
    private const val GRAM_REPO = "amzxyz/RIME-LMDG"
    private const val GRAM_TAG = "LTS"

    /** 语法模型固定下载地址（LTS 标签恒指向最新） */
    private const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"
    private const val GRAM_FILE_NAME = "wanxiang-lts-zh-hans.gram"

    /** 方案包下载地址模式：只取 lite 版 */
    private fun schemaUrl(tag: String): String =
        "https://github.com/amzxyz/rime-wanxiang/releases/download/$tag/rime-wanxiang-lite.zip"

    /** zip 中只允许更新此前缀下的词库文件，其余一律跳过 */
    private val ALLOWED_PREFIXES = listOf("dicts/")
    private const val ALLOWED_SUFFIX = ".dict.yaml"

    const val PREFS_NAME = "wanxiang_update"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    // 内置随 App 打包的万象 Lite 数据版本：resource.zip 内 shared/dicts/*.dict.yaml
    // 取自官方 Release v18.0.15 的 rime-wanxiang-lite.zip（2026-09-29 打包）
    const val BUILTIN_SCHEMA_VERSION = "v18.0.15"
    private const val KEY_GRAM_PUBLISHED_AT = "gram_published_at"
    // 下载时计算的内容指纹（用于本地/远端比对）
    private const val KEY_DICT_REMOTE_FP = "dict_remote_fp"
    private const val KEY_DICT_REMOTE_FP_VER = "dict_remote_fp_ver"
    private const val KEY_GRAM_REMOTE_FP = "gram_remote_fp"
    private const val KEY_GRAM_REMOTE_FP_PUB = "gram_remote_fp_pub"

    private const val PART_SUFFIX = ".part"
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhAsset(
        val name: String = "",
        val browser_download_url: String = "",
        val size: Long = 0L,
    )

    @Serializable
    private data class GhRelease(
        val tag_name: String = "",
        val published_at: String = "",
        val assets: List<GhAsset> = emptyList(),
    )

    data class UpdateInfo(
        /** 远端方案版本，如 v18.0.14 */
        val schemaRemoteVersion: String,
        /** 本地已下载版本，null=未更新过 */
        val schemaLocalVersion: String?,
        /** 远端模型发布时间 */
        val gramRemotePublishedAt: String,
        /** 本地已下载模型的发布时间，null=未下载过 */
        val gramLocalPublishedAt: String?,
        /** 远端词库内容指纹（下载时计算并存储；null=尚未下载该版本） */
        val dictRemoteFingerprint: String?,
        /** 远端模型内容指纹（下载时计算并存储；null=尚未下载该版本） */
        val gramRemoteFingerprint: String?,
    ) {
        // 只在远端确实更新时才算"有更新"：本地版本高于远端（内置更新包等）时
        // 用 != 判断会误报并把用户降级式覆盖
        val schemaUpdateAvailable: Boolean
            get() {
                val local = schemaLocalVersion ?: return true
                return compareVersions(schemaRemoteVersion, local) > 0
            }
        val gramUpdateAvailable: Boolean
            get() {
                val local = gramLocalPublishedAt ?: return true
                return gramRemotePublishedAt > local
            }
        val hasUpdate: Boolean get() = schemaUpdateAvailable || gramUpdateAvailable
    }

    /** "v18.0.15" 风格版本号逐段数字比较。 */
    private fun compareVersions(left: String, right: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val r = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (r != 0) return r
        }
        return 0
    }

    data class LocalInfo(
        /** 本地方案版本，未更新过则为内置版本 */
        val schemaVersion: String,
        /** 本地词库指纹（dicts 文件内容 SHA-256 派生） */
        val dictFingerprint: String,
        /** 本地模型指纹（文件内容 SHA-256），null=无模型文件 */
        val gramFingerprint: String?,
    )

    data class UpdateProgress(
        val stage: Stage,
        val downloaded: Long = 0L,
        val total: Long = 0L,
    ) {
        enum class Stage { SCHEMA_DOWNLOAD, DICTS_EXTRACT, GRAM_DOWNLOAD }
    }

    /**
     * 获取本地信息（词库指纹、模型指纹），纯本地计算，用于"本地信息"展示。
     * 必须在协程中调用（内部已切换到 IO）。
     */
    suspend fun getLocalInfo(
        prefs: android.content.SharedPreferences,
    ): LocalInfo = withContext(Dispatchers.IO) {
        val dictsDir = File(DataManager.sharedDataDir, "dicts")
        val gramFile = File(DataManager.sharedDataDir, GRAM_FILE_NAME)
        LocalInfo(
            // 未更新过则显示内置版本（v18.0.15，resource.zip 内置）
            schemaVersion = prefs.getString(KEY_SCHEMA_VERSION, null) ?: BUILTIN_SCHEMA_VERSION,
            dictFingerprint = try {
                cachedDictsFingerprint(prefs, dictsDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "词库指纹计算失败")
                ""
            },
            gramFingerprint = if (gramFile.isFile) {
                try {
                    cachedFileFingerprint(prefs, gramFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "模型指纹计算失败")
                    null
                }
            } else {
                null
            },
        )
    }

    // 指纹缓存：文件未变（长度+修改时间一致）时不要每次打开页面都全量哈希
    // （语法模型约 400MB，词库也要逐文件读完）。缓存只存"文件状态→指纹"映射，
    // 文件一变键就对不上，自动重算，不会显示过期指纹。
    private const val KEY_FP_GRAM = "fp_cache_gram"
    private const val KEY_FP_GRAM_STATE = "fp_cache_gram_state"
    private const val KEY_FP_DICT = "fp_cache_dict"
    private const val KEY_FP_DICT_STATE = "fp_cache_dict_state"

    private suspend fun cachedFileFingerprint(
        prefs: android.content.SharedPreferences,
        file: File,
    ): String {
        val state = "${file.length()}:${file.lastModified()}"
        val cached = prefs.getString(KEY_FP_GRAM, null)
        if (cached != null && prefs.getString(KEY_FP_GRAM_STATE, null) == state) return cached
        val fp = sha256File(file)
        prefs.edit().putString(KEY_FP_GRAM, fp).putString(KEY_FP_GRAM_STATE, state).apply()
        return fp
    }

    private suspend fun cachedDictsFingerprint(
        prefs: android.content.SharedPreferences,
        dictsDir: File,
    ): String {
        // 词库状态键只用各文件的路径+长度+修改时间（stat 级别，开销可忽略）
        val stateParts = mutableListOf<String>()
        dictsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(ALLOWED_SUFFIX) }
            .forEach { stateParts.add("${it.relativeTo(dictsDir).path}|${it.length()}|${it.lastModified()}") }
        stateParts.sort()
        val state = fingerprint(*stateParts.toTypedArray())
        val cached = prefs.getString(KEY_FP_DICT, null)
        if (cached != null && prefs.getString(KEY_FP_DICT_STATE, null) == state) return cached
        val fp = dictsContentFingerprint(dictsDir)
        prefs.edit().putString(KEY_FP_DICT, fp).putString(KEY_FP_DICT_STATE, state).apply()
        return fp
    }

    /**
     * 指纹截断显示，如 "21012108...939392"，与设计稿一致。
     */
    fun shortFingerprint(full: String): String =
        if (full.length > 14) "${full.take(8)}...${full.takeLast(6)}" else full

    private fun fingerprint(vararg parts: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算文件内容的 SHA-256（流式读取，适用于大文件如 396MB 语法模型）。
     * 每 1MB 检查一次协程取消，避免页面退出后继续空转。
     */
    private suspend fun sha256File(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            var chunks = 0
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
                if (++chunks % 32 == 0) currentCoroutineContext().ensureActive()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 词库内容指纹：对每个 dict 文件计算内容 SHA-256，
     * 按"相对路径|内容哈希"排序后拼接再取 SHA-256。
     * 本地与下载时用同一算法，更新成功后两者一致。
     */
    private suspend fun dictsContentFingerprint(dictsDir: File): String {
        val parts = mutableListOf<String>()
        dictsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(ALLOWED_SUFFIX) }
            .forEach {
                currentCoroutineContext().ensureActive()
                parts.add("${it.relativeTo(dictsDir).path}|${sha256File(it)}")
            }
        parts.sort()
        return fingerprint(*parts.toTypedArray())
    }

    /**
     * 检查远端更新：查询 GitHub Releases 获取最新版本号，与本地记录比对。
     * 必须在协程中调用（内部已切换到 IO）。
     * @return Result 包装的 UpdateInfo，网络失败时携带异常
     */
    suspend fun checkForUpdates(
        prefs: android.content.SharedPreferences,
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val schemaRelease = fetchRelease("$SCHEMA_REPO/releases/latest")
            val gramRelease = fetchRelease("$GRAM_REPO/releases/tags/$GRAM_TAG")
            if (schemaRelease.tag_name.isBlank()) error("远端方案版本无效")
            if (gramRelease.published_at.isBlank()) error("远端模型版本无效")

            // 远端词库指纹：仅当已下载过该版本时才有（下载时计算的内容指纹）；
            // 新版本尚未下载时为 null，界面显示"—"
            val dictRemoteFp =
                prefs.getString(KEY_DICT_REMOTE_FP, null)
                    .takeIf { prefs.getString(KEY_DICT_REMOTE_FP_VER, null) == schemaRelease.tag_name }
            // 远端模型指纹：同理
            val gramRemoteFp =
                prefs.getString(KEY_GRAM_REMOTE_FP, null)
                    .takeIf { prefs.getString(KEY_GRAM_REMOTE_FP_PUB, null) == gramRelease.published_at }

            UpdateInfo(
                schemaRemoteVersion = schemaRelease.tag_name,
                schemaLocalVersion = prefs.getString(KEY_SCHEMA_VERSION, null),
                gramRemotePublishedAt = gramRelease.published_at,
                gramLocalPublishedAt = prefs.getString(KEY_GRAM_PUBLISHED_AT, null),
                dictRemoteFingerprint = dictRemoteFp,
                gramRemoteFingerprint = gramRemoteFp,
            )
        }.onFailure {
            Timber.e(it, "检查方案更新失败")
        }
    }

    private fun fetchRelease(apiPath: String): GhRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$apiPath")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("版本检查失败：HTTP ${response.code}")
            val body = response.body?.string() ?: error("版本检查返回为空")
            return json.decodeFromString<GhRelease>(body)
        }
    }

    /**
     * 下载并应用更新（词库 + 语法模型配套）。
     * 全部在 IO 线程执行；支持协程取消，取消后临时文件会被清理。
     * @return true=全部成功并已记录版本号；false=失败（旧文件不受影响）
     */
    // 全流程互斥：临时目录与 .part 路径都是固定名，并发更新会互相覆盖/删除
    private val updateMutex = Mutex()

    suspend fun downloadAndApply(
        prefs: android.content.SharedPreferences,
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (UpdateProgress) -> Unit = {},
    ): Boolean = updateMutex.withLock {
        downloadAndApplyLocked(prefs, info, cacheDir, onProgress)
    }

    private suspend fun downloadAndApplyLocked(
        prefs: android.content.SharedPreferences,
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (UpdateProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val workDir = File(cacheDir, "wanxiang_update").apply { mkdirs() }
        // 本次下载的内容指纹（下载成功后存入 prefs，供本地/远端比对）
        var newDictFp: String? = null
        var newGramFp: String? = null
        // 先全部下载到临时位置、校验通过后才动现用文件：
        // 避免"词库已换新、模型下载失败"形成新词库+旧模型的混合状态
        var extractDir: File? = null
        var gramPartial: File? = null
        var gramTarget: File? = null
        try {
            // 1. 下载方案包（只取其中 dicts/ 词库）
            if (info.schemaUpdateAvailable) {
                val zipFile = File(workDir, "rime-wanxiang-lite.zip$PART_SUFFIX")
                zipFile.delete()
                val ok = downloadFile(schemaUrl(info.schemaRemoteVersion), zipFile) { d, t ->
                    onProgress(UpdateProgress(UpdateProgress.Stage.SCHEMA_DOWNLOAD, d, t))
                }
                if (!ok) return@withContext false
                val finalZip = File(workDir, "rime-wanxiang-lite.zip")
                finalZip.delete()
                check(zipFile.renameTo(finalZip)) { "方案包落盘失败" }

                // 2. 解压词库到临时目录并校验（暂不搬移，等模型也下载成功后一起应用）
                extractDir = File(workDir, "dicts_new").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val count = extractDicts(finalZip, extractDir) { done, total ->
                    onProgress(UpdateProgress(UpdateProgress.Stage.DICTS_EXTRACT, done.toLong(), total.toLong()))
                }
                if (count <= 0) {
                    Timber.w("方案包中未找到词库文件，放弃更新")
                    return@withContext false
                }
                // 计算下载词库的内容指纹（与本地同一算法，供更新后比对）
                newDictFp = try {
                    dictsContentFingerprint(extractDir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "下载词库指纹计算失败")
                    null
                }
            }

            // 3. 下载语法模型到 .part（只下载校验，先不替换现用文件）
            if (info.gramUpdateAvailable) {
                val target = File(DataManager.sharedDataDir, GRAM_FILE_NAME)
                val partial = File(target.parentFile, target.name + PART_SUFFIX)
                partial.delete()
                val ok = downloadFile(GRAM_URL, partial) { d, t ->
                    onProgress(UpdateProgress(UpdateProgress.Stage.GRAM_DOWNLOAD, d, t))
                }
                if (!ok) {
                    partial.delete()
                    return@withContext false
                }
                if (partial.length() <= 0L) {
                    partial.delete()
                    Timber.w("语法模型下载为空，放弃更新")
                    return@withContext false
                }
                // 计算下载模型的内容指纹（流式，供更新后比对）
                newGramFp = try {
                    sha256File(partial)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "下载模型指纹计算失败")
                    null
                }
                gramPartial = partial
                gramTarget = target
            }

            // 4. 两边都就绪后才应用：先搬词库，再原子替换语法模型
            extractDir?.let { dir ->
                val dictsDir = File(DataManager.sharedDataDir, "dicts").apply { mkdirs() }
                dir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { src ->
                        val rel = src.relativeTo(dir).path
                        val dest = File(dictsDir, rel).canonicalFile
                        // 二次路径穿越检查
                        check(dest.path.startsWith(dictsDir.canonicalPath + File.separator)) {
                            "非法路径：$rel"
                        }
                        dest.parentFile?.mkdirs()
                        src.copyTo(dest, overwrite = true)
                    }
            }
            if (gramPartial != null && gramTarget != null) {
                gramTarget.delete()
                check(gramPartial.renameTo(gramTarget)) { "语法模型落盘失败" }
            }

            // 5. 全部成功后才记录版本号与内容指纹
            prefs.edit().apply {
                putString(KEY_SCHEMA_VERSION, info.schemaRemoteVersion)
                putString(KEY_GRAM_PUBLISHED_AT, info.gramRemotePublishedAt)
                // 词库指纹仅在本次下载了方案包时更新
                if (info.schemaUpdateAvailable) {
                    newDictFp?.let {
                        putString(KEY_DICT_REMOTE_FP, it)
                        putString(KEY_DICT_REMOTE_FP_VER, info.schemaRemoteVersion)
                    }
                }
                // 模型指纹仅在本次下载了模型时更新
                if (info.gramUpdateAvailable) {
                    newGramFp?.let {
                        putString(KEY_GRAM_REMOTE_FP, it)
                        putString(KEY_GRAM_REMOTE_FP_PUB, info.gramRemotePublishedAt)
                    }
                }
            }.apply()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "方案更新失败")
            false
        } finally {
            // 清理临时文件，避免占用存储空间
            runCatching { File(workDir, "rime-wanxiang-lite.zip$PART_SUFFIX").delete() }
            runCatching { File(workDir, "rime-wanxiang-lite.zip").delete() }
            runCatching { File(workDir, "dicts_new").deleteRecursively() }
            // gram 成功时已 rename 走；失败/取消时清掉 .part 残留
            runCatching { gramPartial?.delete() }
        }
    }

    /**
     * 从方案包中只解压 dicts/ 下的词库文件到 destDir。
     * @return 成功解压的文件数
     */
    private suspend fun extractDicts(
        zipFile: File,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        var done = 0
        val destCanonical = destDir.canonicalPath
        // 用 ZipFile 读中央目录：条目数直接可得，不必像 ZipInputStream 那样
        // 先把整个包流式过一遍（等于完整解压两遍）
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().asSequence()
                .filter { !it.isDirectory && isAllowedEntry(it.name.trimEnd('/')) }
                .toList()
            val total = entries.size
            for (entry in entries) {
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("解压已取消")
                }
                val name = entry.name.trimEnd('/')
                val destFile = File(destDir, name).canonicalFile
                // 路径穿越防护（必须带分隔符：无分隔符前缀比较可被同前缀兄弟目录绕过）
                if (destFile.path.startsWith(destCanonical + File.separator)) {
                    destFile.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        destFile.outputStream().use { out -> input.copyTo(out, BUFFER_SIZE) }
                    }
                    done++
                    onProgress(done, total)
                } else {
                    Timber.w("跳过非法路径：%s", name)
                }
            }
        }
        done
    }

    private fun isAllowedEntry(name: String): Boolean =
        ALLOWED_PREFIXES.any { name.startsWith(it) } && name.endsWith(ALLOWED_SUFFIX)

    private suspend fun downloadFile(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("方案更新下载失败：HTTP %d", response.code)
                    return false
                }
                val body = response.body ?: return false
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var reported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return false
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - reported >= REPORT_STEP ||
                                (total in 1 downTo downloaded)
                            ) {
                                onProgress(downloaded, total)
                                reported = downloaded
                            }
                        }
                        onProgress(downloaded, total)
                    }
                }
                // 完整性校验：服务端声明了长度时，实际字节数必须一致，
                // 否则截断的坏文件会被当成功落盘（语法模型坏文件会让旧模型也找不回来）
                if (total > 0 && target.length() != total) {
                    Timber.w("下载不完整：期望 %d 字节，实际 %d 字节", total, target.length())
                    return false
                }
                true
            }
        }.onFailure {
            if (it !is kotlinx.coroutines.CancellationException) {
                Timber.e(it, "方案更新下载失败")
            }
        }.getOrDefault(false)
    }
}
