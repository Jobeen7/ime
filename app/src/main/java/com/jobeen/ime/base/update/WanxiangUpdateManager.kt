package com.jobeen.ime.base.update

import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

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
    private const val KEY_GRAM_PUBLISHED_AT = "gram_published_at"

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
        /** 远端词库指纹（release 元数据派生，用于展示比对） */
        val dictRemoteFingerprint: String,
        /** 远端模型指纹（LTS 元数据派生，用于展示比对） */
        val gramRemoteFingerprint: String,
    ) {
        val schemaUpdateAvailable: Boolean get() = schemaLocalVersion != schemaRemoteVersion
        val gramUpdateAvailable: Boolean get() = gramLocalPublishedAt != gramRemotePublishedAt
        val hasUpdate: Boolean get() = schemaUpdateAvailable || gramUpdateAvailable
    }

    data class LocalInfo(
        /** 本地方案版本，null=内置未更新过 */
        val schemaVersion: String?,
        /** 本地词库指纹（dicts 文件元数据派生） */
        val dictFingerprint: String,
        /** 本地模型指纹，null=无模型文件 */
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
        val dictParts = dictsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(ALLOWED_SUFFIX) }
            .map { "${it.relativeTo(dictsDir).path}|${it.length()}|${it.lastModified()}" }
            .sorted()
            .toList()
        val gramFile = File(DataManager.sharedDataDir, GRAM_FILE_NAME)
        LocalInfo(
            schemaVersion = prefs.getString(KEY_SCHEMA_VERSION, null),
            dictFingerprint = fingerprint(*dictParts.toTypedArray()),
            gramFingerprint = if (gramFile.isFile) {
                fingerprint("${gramFile.length()}|${gramFile.lastModified()}")
            } else {
                null
            },
        )
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

            // 远端词库指纹：取 lite 包资产元数据派生
            val liteAsset = schemaRelease.assets.firstOrNull {
                it.name == "rime-wanxiang-lite.zip"
            }
            val dictRemoteFp = fingerprint(
                schemaRelease.tag_name,
                liteAsset?.name ?: "",
                "${liteAsset?.size ?: 0L}",
            )
            // 远端模型指纹：取 LTS 发布时间派生
            val gramRemoteFp = fingerprint(
                gramRelease.published_at,
                gramRelease.assets.firstOrNull()?.name ?: "",
            )

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
    suspend fun downloadAndApply(
        prefs: android.content.SharedPreferences,
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (UpdateProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val workDir = File(cacheDir, "wanxiang_update").apply { mkdirs() }
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

                // 2. 解压词库到临时目录并校验
                val extractDir = File(workDir, "dicts_new").apply {
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

                // 3. 搬移到 shared/dicts（逐文件覆盖，不删除其他文件）
                val dictsDir = File(DataManager.sharedDataDir, "dicts").apply { mkdirs() }
                extractDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { src ->
                        val rel = src.relativeTo(extractDir).path
                        val dest = File(dictsDir, rel).canonicalFile
                        // 二次路径穿越检查
                        check(dest.path.startsWith(dictsDir.canonicalPath)) { "非法路径：$rel" }
                        dest.parentFile?.mkdirs()
                        src.copyTo(dest, overwrite = true)
                    }
            }

            // 4. 下载语法模型（.part + 原子重命名，与 GramModelDownloader 一致）
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
                target.delete()
                check(partial.renameTo(target)) { "语法模型落盘失败" }
            }

            // 5. 全部成功后才记录版本号
            prefs.edit()
                .putString(KEY_SCHEMA_VERSION, info.schemaRemoteVersion)
                .putString(KEY_GRAM_PUBLISHED_AT, info.gramRemotePublishedAt)
                .apply()
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
            // gram 的 .part 文件在失败分支已处理；成功后无残留
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
        // 先数出总数用于进度显示（zip 条目数很小，开销可忽略）
        val total = ZipInputStream(zipFile.inputStream()).use { zin ->
            var n = 0
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory && isAllowedEntry(e.name)) n++
                zin.closeEntry()
                e = zin.nextEntry
            }
            n
        }
        var done = 0
        val destCanonical = destDir.canonicalPath
        ZipInputStream(zipFile.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("解压已取消")
                }
                val name = entry.name.trimEnd('/')
                if (!entry.isDirectory && isAllowedEntry(name)) {
                    val destFile = File(destDir, name).canonicalFile
                    // 路径穿越防护
                    if (destFile.path.startsWith(destCanonical)) {
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { out -> zin.copyTo(out, BUFFER_SIZE) }
                        done++
                        onProgress(done, total)
                    } else {
                        Timber.w("跳过非法路径：%s", name)
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
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
                true
            }
        }.onFailure {
            if (it !is kotlinx.coroutines.CancellationException) {
                Timber.e(it, "方案更新下载失败")
            }
        }.getOrDefault(false)
    }
}
