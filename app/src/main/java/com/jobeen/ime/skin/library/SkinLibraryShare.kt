package com.jobeen.ime.skin.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.export.JimeExporter
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 卡片级“分享皮肤”：经 [JimeExporter] 导出完整 .jime 到私有缓存
 * （cacheDir/shared/，FileProvider 的 cache-path 已覆盖该目录），
 * 再以 ACTION_SEND 送系统分享面板。分享的是与“导出”同一份包，
 * 不是配色短码；失败只记元数据（皮肤 id＋异常类名），绝不记文件内容。
 */
sealed interface SkinShareResult {
    data object Shared : SkinShareResult
    data class Failed(val reason: String) : SkinShareResult
}

suspend fun shareSkin(context: Context, skinId: String): SkinShareResult =
    withContext(Dispatchers.IO) {
        runCatching {
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(sharedDir, "$skinId.jime")
            var exported = false
            file.outputStream().use { out ->
                when (JimeExporter(SkinLibrary.layout, SkinLibrary.store).export(skinId, out)) {
                    is JimeExporter.Result.Exported -> exported = true
                    is JimeExporter.Result.Failed -> exported = false
                }
            }
            if (!exported) return@runCatching SkinShareResult.Failed("export_failed")
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享皮肤"))
            SkinShareResult.Shared
        }.getOrElse { error ->
            // 导出失败本身已由 JimeExporter 记日志；此处只补分享侧异常。
            DiagLog.w(
                "library", "share_failed",
                mapOf(
                    "skinId" to skinId,
                    "error" to (error::class.simpleName ?: "Unknown"),
                ),
            )
            SkinShareResult.Failed("share_failed")
        }
    }
