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
 * 3. 下载用 .part 临时文件，成功后原子替换；词库先完整解压到与现用目录
 *    同级的 staging 目录校验，再整目录 rename 切换，不逐文件搬移。
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
    private const val SCHEMA_ZIP_NAME = "rime-wanxiang-lite.zip"

    /** 方案包下载地址模式：只取 lite 版 */
    private fun schemaUrl(tag: String): String =
        "https://github.com/amzxyz/rime-wanxiang/releases/download/$tag/rime-wanxiang-lite.zip"

    /** zip 中只允许更新此前缀下的词库文件，其余一律跳过 */
    private val ALLOWED_PREFIXES = listOf("dicts/")
    private const val ALLOWED_SUFFIX = ".dict.yaml"

    const val PREFS_NAME = "wanxiang_update"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    // 内置随 App 打包的万象 Lite 数据版本（仅作落盘 version.txt 读不到时的
    // 兜底；展示优先读 shared/version.txt，见 readBundledSchemaVersion）
    const val BUILTIN_SCHEMA_VERSION = "v18.1.0"
    private const val KEY_GRAM_PUBLISHED_AT = "gram_published_at"
    // 下载时计算的内容指纹（用于本地/远端比对）
    private const val KEY_DICT_REMOTE_FP = "dict_remote_fp"
    private const val KEY_DICT_REMOTE_FP_VER = "dict_remote_fp_ver"
    private const val KEY_GRAM_REMOTE_FP = "gram_remote_fp"
    private const val KEY_GRAM_REMOTE_FP_PUB = "gram_remote_fp_pub"

    private const val PART_SUFFIX = ".part"

    /** 词库整目录切换的 staging/trash 目录名（与现用 dicts 同级，保证 rename 在同一文件系统内原子） */
    private const val DICTS_STAGING_DIR = "dicts.staging"
    private const val DICTS_TRASH_DIR = "dicts.trash"
    private const val UPDATE_TXN_FILE = "wanxiang-update.pending"
    private const val UPDATE_TXN_COMMITTED_FILE = "wanxiang-update.committed"
    private const val GRAM_BACKUP_FILE = "$GRAM_FILE_NAME.update-backup"
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L

    // 下载/解压体积上限（正常量级：方案包约 32MB、语法模型约 400MB、单个
    // 词库文件远小于 64MB）：留足余量，只拦上游污染/异常流写满存储
    private const val MAX_SCHEMA_BYTES = 128L * 1024 * 1024
    private const val MAX_MODEL_BYTES = 600L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_EXTRACT_TOTAL_BYTES = 256L * 1024 * 1024

    // 这些客户端只访问写死的 HTTPS 地址：禁止 https↔http 重定向，
    // 避免全局放开明文后被降级（明文仅供 WebDAV 在用户显式开启后使用）
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhAsset(
        val name: String = "",
        val browser_download_url: String = "",
        val size: Long = 0L,
        /** GitHub 登记的官方摘要（"sha256:..."），下载后据此校验、拿不到则拒绝更新 */
        val digest: String = "",
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
        /** 方案包在 GitHub 登记的官方 SHA-256（下载前必须取得，缺失即拒绝更新） */
        val schemaZipSha256: String = "",
        /** 语法模型在 GitHub 登记的官方 SHA-256（同上） */
        val gramSha256: String = "",
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
            // 未在线更新过则显示内置版本：优先读解压落盘的 shared/version.txt
            // （随打包换版自动跟随，不依赖手改常量——常量曾停在 v18.0.15 与
            // 实际内置 18.1.0 脱节，方案页本地版本错报）
            schemaVersion = prefs.getString(KEY_SCHEMA_VERSION, null)
                ?: readBundledSchemaVersion()
                ?: BUILTIN_SCHEMA_VERSION,
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

    /**
     * 不跟随符号链接的递归删除：File.deleteRecursively 遇到指向目录的软链
     * 会跟进去删目标内容；解压目录虽由本 App 写入，仍按不跟随实现兜底。
     * 纯 java.io 实现（java.nio.file 要 API 26+，minSdk 24）。
     */
    private fun File.deleteRecursivelyNoFollow() {
        if (!exists()) return
        if (isDirectory && !isSymlink()) {
            listFiles()?.forEach { it.deleteRecursivelyNoFollow() }
        }
        delete()
    }

    /** 软链判定（经典 canonical 对比法，不依赖 java.nio） */
    private fun File.isSymlink(): Boolean {
        val parent = parentFile ?: return false
        val canonicalParent = runCatching { parent.canonicalFile }.getOrNull() ?: return false
        val inCanonicalParent = File(canonicalParent, name)
        val canonical = runCatching { inCanonicalParent.canonicalPath }.getOrNull() ?: return false
        return canonical != inCanonicalParent.path
    }

    /**
     * Recover a cross-resource update before Rime opens its data files. A pending
     * transaction without a committed marker is rolled back; the marker is created
     * only after both the dictionary directory and gram model have been replaced.
     *
     * This runs on the startup path, so it must never throw: a damaged journal
     * or a failed rollback step is logged and quarantined, never allowed to
     * block the keyboard from starting.
     */
    fun recoverInterruptedUpdate() {
        runCatching { recoverInterruptedUpdateInternal() }
            .onFailure { Timber.e(it, "Interrupted-update recovery failed; continuing startup") }
    }

    private fun recoverInterruptedUpdateInternal() {
        val root = DataManager.sharedDataDir
        val txn = File(root, UPDATE_TXN_FILE)
        val committed = File(root, UPDATE_TXN_COMMITTED_FILE)
        val dicts = File(root, "dicts")
        val trash = File(root, DICTS_TRASH_DIR)
        val gram = File(root, GRAM_FILE_NAME)
        val gramBackup = File(root, GRAM_BACKUP_FILE)

        if (!txn.isFile) {
            committed.delete()
            File(root, "$UPDATE_TXN_FILE.tmp").delete()
            // Preserve the original crash-recovery behavior for installations
            // created before the transaction marker was introduced.
            if (!dicts.exists() && trash.exists()) trash.renameTo(dicts)
            else if (dicts.exists() && trash.exists()) trash.deleteRecursivelyNoFollow()
            return
        }

        val journal = runCatching {
            val properties = java.util.Properties()
            txn.inputStream().use(properties::load)
            parseUpdateJournal(properties)
        }.getOrNull()
        if (journal == null) {
            // Unreadable or incomplete journal: quarantine it for diagnosis and
            // fall back to the plain consistency repair. A damaged journal must
            // never wedge every subsequent startup.
            Timber.w("Quarantining unreadable update recovery journal")
            if (!txn.renameTo(File(root, "$UPDATE_TXN_FILE.bad"))) txn.delete()
            committed.delete()
            if (!dicts.exists() && trash.exists()) trash.renameTo(dicts)
            else if (dicts.exists() && trash.exists()) trash.deleteRecursivelyNoFollow()
            gramBackup.delete()
            return
        }

        if (committed.isFile) {
            Timber.i("Completing cleanup of committed data update")
            trash.deleteRecursivelyNoFollow()
            gramBackup.delete()
        } else {
            Timber.w("Rolling back interrupted data update")
            if (journal.changedDicts) {
                when {
                    journal.hadDicts && trash.exists() -> {
                        dicts.deleteRecursivelyNoFollow()
                        if (!trash.renameTo(dicts)) {
                            // Best effort only: leave the trash directory in
                            // place for diagnosis instead of failing startup.
                            Timber.e("Failed to restore previous dictionary directory")
                        }
                    }
                    !journal.hadDicts -> dicts.deleteRecursivelyNoFollow()
                    // If trash is absent, the original directory had not yet been moved.
                }
            }
            if (journal.changedGram) {
                if (journal.hadGram && gramBackup.isFile) {
                    val restore = File(root, "$GRAM_BACKUP_FILE.restore")
                    restore.delete()
                    runCatching {
                        gramBackup.copyTo(restore, overwrite = true)
                        if (!restore.renameTo(gram)) {
                            gram.delete()
                            if (!restore.renameTo(gram)) {
                                Timber.e("Failed to restore previous grammar model")
                            }
                        }
                    }.onFailure { Timber.e(it, "Grammar model rollback failed") }
                } else if (!journal.hadGram) {
                    gram.delete()
                }
            }
            trash.deleteRecursivelyNoFollow()
            gramBackup.delete()
        }
        txn.delete()
        committed.delete()
        File(root, "$GRAM_BACKUP_FILE.tmp").delete()
        File(root, "$GRAM_BACKUP_FILE.restore").delete()
        File(root, DICTS_STAGING_DIR).deleteRecursivelyNoFollow()
    }

    internal data class UpdateJournal(
        val hadDicts: Boolean,
        val hadGram: Boolean,
        val changedDicts: Boolean,
        val changedGram: Boolean,
    )

    /** Parse the update journal; missing fields mean a damaged journal -> null. */
    internal fun parseUpdateJournal(properties: java.util.Properties): UpdateJournal? {
        val keys = listOf("hadDicts", "hadGram", "changedDicts", "changedGram")
        if (!keys.all(properties::containsKey)) return null
        return UpdateJournal(
            hadDicts = properties.getProperty("hadDicts") == "true",
            hadGram = properties.getProperty("hadGram") == "true",
            changedDicts = properties.getProperty("changedDicts") == "true",
            changedGram = properties.getProperty("changedGram") == "true",
        )
    }

    private fun writeUpdateJournal(changedDicts: Boolean, changedGram: Boolean) {
        val root = DataManager.sharedDataDir
        val txn = File(root, UPDATE_TXN_FILE)
        val temp = File(root, "$UPDATE_TXN_FILE.tmp")
        val props = java.util.Properties().apply {
            setProperty("hadDicts", File(root, "dicts").exists().toString())
            setProperty("hadGram", File(root, GRAM_FILE_NAME).isFile.toString())
            setProperty("changedDicts", changedDicts.toString())
            setProperty("changedGram", changedGram.toString())
        }
        temp.outputStream().use { props.store(it, "Jime update recovery journal") }
        check(temp.renameTo(txn)) { "Failed to persist update recovery journal" }
    }

    /** 内置方案版本：读资源解压落盘的 shared/version.txt（如 "18.1.0"），补上 v 前缀与常量口径对齐 */
    private fun readBundledSchemaVersion(): String? = runCatching {
        val f = File(DataManager.sharedDataDir, "version.txt")
        if (f.isFile) {
            f.readText().trim().takeIf { it.isNotEmpty() }?.let { "v$it" }
        } else {
            null
        }
    }.getOrNull()

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
     * 万象变体短名（Base/Pro/Lite/Pure）：按 schema id 派生（上游各变体的
     * id 约定：wanxiang 为 Base、wanxiang_pro 系为 Pro、wanxiang_pure 系为
     * Pure、wanxiang_lite 系与 wanxiang_t9 为 Lite），id 识别不出再按方案名
     * 关键词兜底；都不命中返回 null（调用方回退显示方案名）。不写死某个
     * 变体——用户可自备任意变体的方案包，展示必须跟着实际方案走。
     */
    fun variantNameOf(schemaId: String, schemaName: String): String? {
        val id = schemaId.trim().lowercase()
        if (id.startsWith("wanxiang")) {
            when {
                id.contains("pure") -> return "Pure"
                id.contains("pro") -> return "Pro"
                id.contains("lite") || id == "wanxiang_t9" -> return "Lite"
                id == "wanxiang" || id == "wanxiang_base" -> return "Base"
            }
        }
        val name = schemaName.trim()
        return when {
            name.contains("Pure") -> "Pure"
            name.contains("Pro") -> "Pro"
            name.contains("Lite") -> "Lite"
            name.contains("Base") -> "Base"
            // 上游基础版方案名就是「万象拼音」（无变体后缀）
            name == "万象拼音" -> "Base"
            else -> null
        }
    }

    /** 指纹截断显示，如 "21012108...939392"，与设计稿一致。 */
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
                schemaZipSha256 = com.jobeen.ime.base.ngram.normalizeSha256(
                    schemaRelease.assets.firstOrNull { it.name == SCHEMA_ZIP_NAME }?.digest,
                ),
                gramSha256 = com.jobeen.ime.base.ngram.normalizeSha256(
                    gramRelease.assets.firstOrNull { it.name == GRAM_FILE_NAME }?.digest,
                ),
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
        recoverInterruptedUpdate()
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
                // fail-closed：没有 GitHub 登记的官方摘要就不下载、不更新
                if (info.schemaZipSha256.isBlank()) {
                    Timber.w("方案包缺少官方 SHA-256，拒绝更新")
                    return@withContext false
                }
                val zipFile = File(workDir, "rime-wanxiang-lite.zip$PART_SUFFIX")
                zipFile.delete()
                val ok = downloadFile(schemaUrl(info.schemaRemoteVersion), zipFile, MAX_SCHEMA_BYTES) { d, t ->
                    onProgress(UpdateProgress(UpdateProgress.Stage.SCHEMA_DOWNLOAD, d, t))
                }
                if (!ok) return@withContext false
                val finalZip = File(workDir, "rime-wanxiang-lite.zip")
                finalZip.delete()
                check(zipFile.renameTo(finalZip)) { "方案包落盘失败" }
                if (!sha256File(finalZip).equals(info.schemaZipSha256, ignoreCase = true)) {
                    Timber.w("方案包 SHA-256 与 GitHub 登记不一致，放弃更新")
                    finalZip.delete()
                    return@withContext false
                }

                // 2. 解压词库到 staging 目录并校验（暂不切换，等模型也下载
                // 成功后一起应用）。staging 必须与现用 dicts 同级（同在
                // sharedDataDir 下）：应用时整目录 rename 切换，跨文件系统
                // （如 cacheDir 在内部存储）rename 会失败
                extractDir = File(DataManager.sharedDataDir, DICTS_STAGING_DIR).apply {
                    deleteRecursivelyNoFollow()
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
                // fail-closed：没有 GitHub 登记的官方摘要就不下载、不更新
                if (info.gramSha256.isBlank()) {
                    Timber.w("语法模型缺少官方 SHA-256，拒绝更新")
                    return@withContext false
                }
                val target = File(DataManager.sharedDataDir, GRAM_FILE_NAME)
                val partial = File(target.parentFile, target.name + PART_SUFFIX)
                partial.delete()
                val ok = downloadFile(GRAM_URL, partial, MAX_MODEL_BYTES) { d, t ->
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
                // 计算下载模型的内容指纹（流式，供更新后比对），同时即为
                // 与 GitHub 登记摘要的校验结果：算不出或对不上都放弃更新
                newGramFp = try {
                    sha256File(partial)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "下载模型指纹计算失败")
                    null
                }
                if (newGramFp == null ||
                    !newGramFp.equals(info.gramSha256, ignoreCase = true)
                ) {
                    Timber.w("语法模型 SHA-256 与 GitHub 登记不一致，放弃更新")
                    partial.delete()
                    return@withContext false
                }
                gramPartial = partial
                gramTarget = target
            }

            // 4. Journal and retain both previous resources until the pair is installed.
            // 不再逐文件搬移+清残留：逐文件落位期间现用目录是新旧混合态，
            // 中途被杀 Rime 会加载到半套词库；整目录 rename 后现用目录
            // 要么是旧版完整词库、要么是新版完整词库。上游删掉/改名的词库
            // 文件随旧目录整份进 trash，不再需要单独清残留
            val dictsDir = File(DataManager.sharedDataDir, "dicts")
            val trashDir = File(DataManager.sharedDataDir, DICTS_TRASH_DIR)
            val changedDicts = extractDir != null
            val changedGram = gramPartial != null && gramTarget != null
            if (changedDicts || changedGram) writeUpdateJournal(changedDicts, changedGram)

            if (changedGram && gramTarget!!.isFile) {
                val backup = File(DataManager.sharedDataDir, GRAM_BACKUP_FILE)
                val backupTemp = File(DataManager.sharedDataDir, "$GRAM_BACKUP_FILE.tmp")
                backup.delete()
                backupTemp.delete()
                gramTarget!!.copyTo(backupTemp, overwrite = true)
                check(backupTemp.renameTo(backup)) { "语法模型旧版本备份失败" }
            }
            extractDir?.let { staging ->
                if (dictsDir.exists()) check(dictsDir.renameTo(trashDir)) { "词库旧目录移开失败" }
                check(staging.renameTo(dictsDir)) { "词库目录切换失败" }
            }
            if (gramPartial != null && gramTarget != null) {
                // partial 与 target 同目录，rename 本身即原子替换目标文件；
                // 不要先 delete——那会打开"旧模型已删、新模型未就位"的窗口，
                // 中途被杀将留下无语法模型而 prefs 仍记旧版本的状态
                check(gramPartial.renameTo(gramTarget)) { "语法模型落盘失败" }
            }

            if (changedDicts || changedGram) {
                check(File(DataManager.sharedDataDir, UPDATE_TXN_COMMITTED_FILE).createNewFile()) {
                    "无法标记数据更新事务已提交"
                }
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
            // Committed resources remain a valid pair even if preference metadata
            // cannot be persisted immediately; the next check may simply re-fetch.
            if (changedDicts || changedGram) recoverInterruptedUpdate()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "方案更新失败")
            runCatching { recoverInterruptedUpdate() }
            false
        } finally {
            // Cancellation may arrive after the first resource was switched.
            // Resolve any still-pending transaction before cleaning temporary data.
            runCatching { recoverInterruptedUpdate() }
            // 清理临时文件，避免占用存储空间
            runCatching { File(workDir, "rime-wanxiang-lite.zip$PART_SUFFIX").delete() }
            runCatching { File(workDir, "rime-wanxiang-lite.zip").delete() }
            // staging 未切换（失败/取消）时清掉；切换成功后该路径已 rename
            // 为现用 dicts、不存在，此处是空操作。trash 不在此清：切换中断
            // 时它可能是旧词库的唯一副本，留下次应用前的自愈处理
            runCatching {
                File(DataManager.sharedDataDir, DICTS_STAGING_DIR).deleteRecursivelyNoFollow()
            }
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
        var extractedBytes = 0L
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
                // 剥掉 dicts/ 前缀：解压目录直接代表现用 dicts 目录本身。
                // 这样解压目录与现用目录的相对路径完全同构——内容指纹可直接比对，
                // 应用时也不会再多套一层 dicts/dicts/（旧实现把文件写进了
                // shared/dicts/dicts/，Rime 根本读不到，在线更新的词库从未真正生效）
                val relName = name.removePrefix("dicts/")
                val destFile = File(destDir, relName).canonicalFile
                // 路径穿越防护（必须带分隔符：无分隔符前缀比较可被同前缀兄弟目录绕过）
                if (destFile.path.startsWith(destCanonical + File.separator)) {
                    destFile.parentFile?.mkdirs()
                    // 解压体积上限：防 zip 炸弹（小压缩包解出数 GB 伪词库写满存储）
                    var entryBytes = 0L
                    zf.getInputStream(entry).use { input ->
                        destFile.outputStream().use { out ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                entryBytes += n
                                extractedBytes += n
                                if (entryBytes > MAX_ENTRY_BYTES || extractedBytes > MAX_EXTRACT_TOTAL_BYTES) {
                                    throw IllegalStateException("词库解压体积超上限")
                                }
                                out.write(buf, 0, n)
                            }
                        }
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
        maxBytes: Long,
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
                // 体积上限：上游被污染或异常无限流响应可写满设备存储；声明
                // 长度超限直接拒，chunked 无长度时靠下面累计字节数兜底
                if (total > maxBytes) {
                    Timber.w("下载体积超上限：声明 %d 字节，上限 %d", total, maxBytes)
                    return false
                }
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
                            if (downloaded > maxBytes) {
                                Timber.w("下载体积超上限：已下载 %d 字节，上限 %d", downloaded, maxBytes)
                                return false
                            }
                            if (downloaded - reported >= REPORT_STEP ||
                                (total > 0 && downloaded >= total)
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
