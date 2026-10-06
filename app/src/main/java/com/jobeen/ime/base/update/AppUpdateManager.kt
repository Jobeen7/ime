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
        // 资产名来自远端 Release 元数据，只取 basename 拼接，防路径穿越写出更新目录
        File(updateDir(context), File(apkName).name.ifBlank { "jime-update.apk" })

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
        expectedSha256: String = "",
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
                val digest = if (expectedSha256.isNotBlank()) {
                    java.security.MessageDigest.getInstance("SHA-256")
                } else {
                    null
                }
                body.byteStream().use { input ->
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                // 大小对不上（截断/多传）视为下载失败，不进入安装
                if (expectedSize > 0 && downloaded != expectedSize) {
                    Timber.w("App update size mismatch: got %d, want %d", downloaded, expectedSize)
                    partial.delete()
                    return@withContext null
                }
                // 完整性校验：GitHub 提供了资产 SHA-256 时必须逐字节一致，
                // 只验大小挡不住同长度的内容替换/损坏
                if (digest != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        Timber.w("App update SHA-256 mismatch")
                        partial.delete()
                        return@withContext null
                    }
                }
                // 先删旧目标再 rename 的时序窗口：rename 失败时不能把「已删旧
                // 包 + 只有 .part」当成功返回——走 copyTo 兜底并显式校验目标
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                if (!target.exists() || target.length() != downloaded) {
                    Timber.w("App update finalize failed: target missing or size changed")
                    return@withContext null
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

    /**
     * 已下载文件是否可安装：能解析为 APK、包名与本应用一致、且**签名证书与
     * 已安装应用完全一致**。此前只验包名：同包名异签名包要到系统安装器才
     * 报错（用户先看到一次系统级安装失败）；在这里拦下，失败原因可控可解释。
     * 系统覆盖安装本身也强制同签名，这里是把防线前移，不是唯一防线。
     */
    fun isInstallable(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val pm = context.packageManager
        // 签名信息 API 分档：28+ 用 GET_SIGNING_CERTIFICATES/signingInfo，
        // 24-27 用旧 GET_SIGNATURES/signatures（minSdk 24，不能直调新 API）
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val info = runCatching {
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        }.getOrNull() ?: return false
        if (info.packageName != context.packageName) return false
        val newCerts = signerCerts(info) ?: return false
        val current = runCatching {
            pm.getPackageInfo(context.packageName, flags)
        }.getOrNull() ?: return false
        val currentCerts = signerCerts(current) ?: return false
        return newCerts == currentCerts
    }

    /** 提取签名证书字节集合（按系统版本选 API），无签名信息时返回 null */
    @Suppress("DEPRECATION")
    private fun signerCerts(info: android.content.pm.PackageInfo): Set<List<Byte>>? {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return null
        if (signers.isEmpty()) return null
        return signers.map { it.toByteArray().toList() }.toSet()
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
