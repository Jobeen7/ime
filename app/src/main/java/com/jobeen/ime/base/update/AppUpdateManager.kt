package com.jobeen.ime.base.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App 自更新：从 GitHub Release 直链下载正式版 APK 并调起系统安装，
 * 不再跳转浏览器到 Release 页。
 *
 * 下载落 cacheDir/update/（FileProvider 已授权该路径）；安装前校验
 * 文件大小与包名，防止下错/下坏的文件被送进安装器。
 */
object AppUpdateManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun updateDir(context: Context): File =
        File(context.cacheDir, "update").apply { mkdirs() }

    fun targetFile(context: Context, apkName: String): File =
        File(updateDir(context), apkName.ifBlank { "jime-update.apk" })

    fun cancelDownload() {
        activeCall?.cancel()
    }

    /**
     * 下载 APK 到目标文件。[onProgress] 回调 (已下载字节, 总字节)，
     * 总字节未知时为发布资产声明的 [expectedSize]。
     * 成功返回文件；失败/取消返回 null（取消会抛 CancellationException 由协程处理，
     * 这里统一清理半成品后返回 null，调用方按协程取消状态区分）。
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        apkName: String,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val target = targetFile(context, apkName)
        val partial = File(target.parentFile, target.name + ".part")
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Jime-Updater")
                .get()
                .build()
            val call = client.newCall(request)
            activeCall = call
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("App update download HTTP %d", response.code)
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
                var downloaded = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                // 大小对不上（截断/多传）视为下载失败，不进入安装
                if (expectedSize > 0 && downloaded != expectedSize) {
                    Timber.w("App update size mismatch: got %d, want %d", downloaded, expectedSize)
                    return@withContext null
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                target
            }
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            Timber.w(e, "App update download failed")
            partial.delete()
            null
        } finally {
            activeCall = null
        }
    }

    /** 已下载文件是否可安装：能解析为 APK 且包名与本应用一致 */
    fun isInstallable(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return false
        return info.packageName == context.packageName
    }

    /** 是否还缺「安装未知应用」授权（API 26 起按应用单独授权，低版本无此门） */
    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()

    /** 打开本应用的「安装未知应用」系统设置页 */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 调起系统安装器安装已下载的 APK */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
