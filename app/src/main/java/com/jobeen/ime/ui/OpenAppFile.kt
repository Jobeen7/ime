package com.jobeen.ime.ui

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import com.jobeen.ime.BuildConfig
import java.io.File

/**
 * openAppFile 分发（WO52 8.3）：对端以参数表
 * {"action":"openAppFile","fileURL":...} 触发打开应用内文件。
 * Jime 无应用内文件浏览器（FolderBrowser），等效落点是经
 * [AppFilesDocumentsProvider] 的 document URI 以 ACTION_VIEW
 * 打开该文件（系统按 MIME 选应用查看）。
 *
 * 校验口径（Jime 自定，且更严）：
 * - action 必须恰好等于 "openAppFile"，否则丢弃；
 * - fileURL 必须存在且非空；
 * - 解析后文件必须落在应用 external files 根目录内
 *   （规范化路径约束，越界/符号链接逃逸均拒绝）且为普通文件。
 *
 * 目前尚无应用内生产方产生该参数表（AI 指令面板／脚本输出为
 * 未来生产方）；分发与打开器先行落地并由单测覆盖。
 */
object OpenAppFileDispatcher {
    const val ACTION_OPEN_APP_FILE = "openAppFile"
    const val PARAM_FILE_URL = "fileURL"

    data class Resolved(val fileUrl: String)

    /** 纯校验：action 不符或 fileURL 缺失/为空 → null（丢弃）。 */
    fun resolve(params: Map<String, String?>): Resolved? {
        if (params["action"] != ACTION_OPEN_APP_FILE) return null
        val fileUrl = params[PARAM_FILE_URL]?.takeIf { it.isNotEmpty() } ?: return null
        return Resolved(fileUrl)
    }
}

object AppFilesOpener {
    private const val TAG = "AppFilesOpener"

    /**
     * 校验并打开应用内文件。返回 true 表示已发起打开意图，
     * false 表示参数/路径校验未过或无法打开（静默丢弃＋记日志）。
     */
    fun openAppFile(context: Context, params: Map<String, String?>): Boolean {
        val resolved = OpenAppFileDispatcher.resolve(params) ?: return false
        val file = resolveFile(context, resolved.fileUrl)
        if (file == null) {
            Log.w(TAG, "openAppFile: file outside app files: ${resolved.fileUrl}")
            return false
        }
        if (!file.isFile) {
            Log.w(TAG, "openAppFile: not a file: ${resolved.fileUrl}")
            return false
        }
        val intent = documentViewIntent(context, file) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "openAppFile: cannot open ${file.name}: $e")
            false
        }
    }

    /**
     * 把 fileURL 解析为根目录内的文件。接受绝对路径或相对根的
     * 相对路径；规范化后必须仍在根内，否则返回 null。
     */
    internal fun resolveFile(context: Context, fileUrl: String): File? {
        val root = context.getExternalFilesDir(null)?.canonicalFile ?: return null
        val candidate = if (File(fileUrl).isAbsolute) File(fileUrl) else File(root, fileUrl)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!isWithinRoot(root, canonical)) return null
        return canonical
    }

    /** 纯判定：file 本身是根，或位于根之下（已规范化路径）。 */
    internal fun isWithinRoot(root: File, file: File): Boolean {
        return file == root || file.path.startsWith(root.path + File.separator)
    }

    /**
     * 按 [AppFilesDocumentsProvider] 的 documentId 口径
     * （绝对路径去掉根父目录前缀）构造 document URI 的查看意图。
     */
    internal fun documentViewIntent(context: Context, file: File): Intent? {
        val root = context.getExternalFilesDir(null) ?: return null
        val documentId = file.absolutePath.removePrefix("${root.parent}${File.separator}")
        val uri = DocumentsContract.buildDocumentUri(
            "${BuildConfig.APPLICATION_ID}.files.documents",
            documentId,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun mimeTypeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
