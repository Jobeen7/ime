package com.jobeen.ime.base.update

import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 万象输入方案在线更新管理器。
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

    /** 方案包固定下载地址（Jobeen 指定，只用 lite 版） */
    const val SCHEMA_URL =
        "https://github.com/amzxyz/rime-wanxiang/releases/download/v18.0.14/rime-wanxiang-lite.zip"
    const val SCHEMA_VERSION = "v18.0.14"

    /** 语法模型固定下载地址（Jobeen 指定，LTS 版） */
    const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"
    const val GRAM_FILE_NAME = "wanxiang-lts-zh-hans.gram"

    /** zip 中只允许更新此前缀下的词库文件，其余一律跳过 */
    private val ALLOWED_PREFIXES = listOf("dicts/")
    private const val ALLOWED_SUFFIX = ".dict.yaml"

    const val PREFS_NAME = "wanxiang_update"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_GRAM_DOWNLOADED = "gram_downloaded"

    private const val PART_SUFFIX = ".part"
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val schemaLocalVersion: String?,
        val gramDownloaded: Boolean,
    ) {
        val schemaUpdateAvailable: Boolean get() = schemaLocalVersion != SCHEMA_VERSION
        val gramUpdateAvailable: Boolean get() = !gramDownloaded
        val hasUpdate: Boolean get() = schemaUpdateAvailable || gramUpdateAvailable
    }

    data class UpdateProgress(
        val stage: Stage,
        val downloaded: Long = 0L,
        val total: Long = 0L,
    ) {
        enum class Stage { SCHEMA_DOWNLOAD, DICTS_EXTRACT, GRAM_DOWNLOAD }
    }

    fun getLocalVersions(prefs: android.content.SharedPreferences): Pair<String?, Boolean> =
        prefs.getString(KEY_SCHEMA_VERSION, null) to
            prefs.getBoolean(KEY_GRAM_DOWNLOADED, false)

    /**
     * 检查更新：比对本地已记录版本与固定地址版本，无需网络请求。
     */
    fun checkForUpdates(
        prefs: android.content.SharedPreferences,
    ): UpdateInfo {
        val (localSchema, gramDownloaded) = getLocalVersions(prefs)
        return UpdateInfo(
            schemaLocalVersion = localSchema,
            gramDownloaded = gramDownloaded,
        )
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
                val ok = downloadFile(SCHEMA_URL, zipFile) { d, t ->
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
                .putString(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putBoolean(KEY_GRAM_DOWNLOADED, true)
                .apply()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "万象更新失败")
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
                    Timber.w("万象更新下载失败：HTTP %d", response.code)
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
                Timber.e(it, "万象更新下载失败")
            }
        }.getOrDefault(false)
    }
}
