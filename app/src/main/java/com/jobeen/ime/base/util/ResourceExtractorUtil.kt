package com.jobeen.ime.base.util

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ResourceExtractorUtil {

    private val SKIP_PATTERNS = listOf(
        "__MACOSX",
        ".DS_Store",
        "PaxHeader",
    )

    /**
     * 解压资源 ZIP。
     *
     * 合同：任一单文件落盘失败（二次 rename 仍失败）即整体失败，
     * 返回 false 并在失败条目处立即中止，不再继续解压后续条目；
     * 调用方（AppStartup）收到 false 时不得写版本标记，下次启动
     * 重走解压。跳过条目（白名单外的系统文件、非法路径）不算失败。
     * 流读写本身抛出的 IOException 同样是整体失败信号（异常上抛，
     * 调用方按失败处理），与返回 false 语义一致。
     */
    fun extract(context: Context, assetName: String, destDir: File): Boolean {
        Timber.d("Extracting %s to: %s", assetName, destDir.absolutePath)
        return context.assets.open(assetName).use { input ->
            extractZip(input, destDir)
        }
    }

    /**
     * 无 Context 的解压入口（与 [extract] 同合同）：供主机单测
     * 直接以内存 ZIP 流验证失败语义，无需 Android AssetManager。
     */
    internal fun extract(input: InputStream, destDir: File): Boolean =
        extractZip(input, destDir)

    private fun extractZip(input: InputStream, destDir: File): Boolean {
        return ZipInputStream(input).use { zip ->
            var fileCount = 0
            val destCanonicalPath = destDir.canonicalPath
            var entry: ZipEntry? = zip.nextEntry

            while (entry != null) {
                val name = entry.name.trimEnd('/')
                val simpleName = File(name).name

                if (shouldSkip(name, simpleName)) {
                    Timber.d("  skipped: %s", name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                val destFile = File(destDir, name).canonicalFile

                // 必须带分隔符比较：纯前缀会被 "<dest>2/..." 这类同前缀兄弟目录绕过
                if (destFile.path != destCanonicalPath &&
                    !destFile.path.startsWith(destCanonicalPath + File.separator)
                ) {
                    Timber.w("  skipped illegal path: %s", name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    // 先写临时文件再原子 rename：mmap 后的旧映射指向旧 inode，
                    // 不会被原地截断覆写导致 SIGBUS
                    val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
                    tmpFile.outputStream().use { out ->
                        zip.copyTo(out, 64 * 1024)
                    }
                    if (!tmpFile.renameTo(destFile)) {
                        // rename 失败（极少见，同目录不可能跨文件系统）：
                        // 先删目标再 rename —— 旧 mmap 映射仍指向被删的旧 inode，
                        // 不会 SIGBUS。再失败即整体失败（不再回退原地 copy
                        // 覆盖——那恰恰是 tmp+rename 要避免的截断覆写，也不再
                        // 只记日志继续：旧行为二次失败仅记日志、仍计 fileCount
                        // 并记“extracted”，上层无法感知失败却写了版本标记，
                        // 下次启动不再重解、缺文件永久存在）。此处删 tmp、
                        // 返回 false，调用方不写版本标记，下次启动重解压。
                        destFile.delete()
                        if (!tmpFile.renameTo(destFile)) {
                            Timber.w("  rename failed, extraction aborted: %s", name)
                            tmpFile.delete()
                            return false
                        }
                    }
                    fileCount++
                    Timber.d("  extracted: %s", name)
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
            Timber.d("Zip extraction complete: %d files", fileCount)
            true
        }
    }

    private fun shouldSkip(path: String, simpleName: String): Boolean {
        if (simpleName.isEmpty()) return true
        if (simpleName.startsWith("._")) return true
        return path.split('/').any { segment ->
            SKIP_PATTERNS.contains(segment)
        }
    }
}