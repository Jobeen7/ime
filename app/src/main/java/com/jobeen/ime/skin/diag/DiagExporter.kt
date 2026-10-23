package com.jobeen.ime.skin.diag

import com.jobeen.ime.base.CrashTrap
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志导出（详设十三.5）：把近期诊断日志与最近崩溃文件打成
 * 单个 .zip，供设置页经系统分享/保存直接发送。内容只含 [DiagLog]
 * 的元数据日志与 CrashTrap 的崩溃堆栈，不含任何用户数据文件。
 */
object DiagExporter {
    private val STAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * @param logsDir [DiagLog] 的日志目录
     * @param crashDir CrashTrap 的崩溃目录（filesDir/crash），可为 null
     * @param outDir zip 落点（须在 FileProvider 已声明的共享路径内）
     */
    fun buildExportZip(
        logsDir: File,
        crashDir: File?,
        outDir: File,
        clock: () -> Long = { System.currentTimeMillis() },
    ): File {
        outDir.mkdirs()
        val zipFile = File(outDir, "jime-diag-${STAMP_FORMAT.format(Date(clock()))}.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            collectLogs(logsDir).forEach { file ->
                addEntry(zip, "logs/${file.name}", file)
            }
            collectCrashes(crashDir).forEach { file ->
                addEntry(zip, "crash/${file.name}", file)
            }
        }
        return zipFile
    }

    private fun collectLogs(logsDir: File): List<File> =
        logsDir.listFiles { f -> f.isFile && DiagLogCore.isLogFileName(f.name) }
            ?.sortedBy { it.name } ?: emptyList()

    private fun collectCrashes(crashDir: File?): List<File> =
        crashDir?.listFiles { f -> f.isFile && CrashTrap.isCrashFileName(f.name) }
            ?.sortedBy { it.name } ?: emptyList()

    private fun addEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
