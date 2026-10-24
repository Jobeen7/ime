// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.log

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志导出纯逻辑（不碰 Android 框架，可单测）。
 *
 * 落盘位置约束：zip 必须落在 FileProvider 已配置的根内，否则
 * getUriForFile 会抛 IllegalArgumentException（真机曾因此 100%
 * 导出失败：旧实现写 cacheDir 根目录，而 file_paths.xml 只配了
 * cache-path shared/ 与 update/ 两个子目录）。这里固定写
 * cacheDir/shared/，与 file_paths.xml 的 shared 根对应。
 */
object DiagExport {

    const val ZIP_ENTRY_NAME = "diagnostic.txt"

    /** 诊断 zip 的落盘路径：cacheDir/shared/jime-diag-<now>.zip（只算路径，不建目录）。 */
    fun diagZipFile(cacheDir: File, now: String): File =
        File(File(cacheDir, "shared"), "jime-diag-$now.zip")

    /** 诊断文本：应用/设备头 + 缓冲日志行（格式与旧实现一致）。 */
    fun buildDiagnosticText(
        versionName: String,
        packageName: String,
        androidRelease: String,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        now: String,
        entries: List<AppLogEntry>,
    ): String = buildString {
        appendLine("Jime 诊断日志")
        appendLine("应用版本：$versionName")
        appendLine("包名：$packageName")
        appendLine("Android：$androidRelease（SDK $sdkInt）")
        appendLine("设备：$manufacturer $model")
        appendLine("导出时间：$now")
        appendLine("说明：仅本进程 Timber 日志")
        appendLine("----")
        entries.forEach { e ->
            val p = when (e.priority) {
                2 -> "V"
                3 -> "D"
                4 -> "I"
                5 -> "W"
                6 -> "E"
                else -> "?"
            }
            appendLine("${e.time} $p/${e.tag} ${e.message}")
        }
    }

    /** 写 zip（自动建父目录），返回落盘文件。 */
    fun writeDiagZip(zipFile: File, txt: String): File {
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
            zos.write(txt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return zipFile
    }
}
