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

    fun extract(context: Context, assetName: String, destDir: File) {
        Timber.d("Extracting %s to: %s", assetName, destDir.absolutePath)
        context.assets.open(assetName).use { input ->
            extractZip(input, destDir)
        }
    }

    private fun extractZip(input: InputStream, destDir: File) {
        ZipInputStream(input).use { zip ->
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
                        // 不会 SIGBUS；只有 rename 彻底不可用才回退原地覆盖
                        destFile.delete()
                        if (!tmpFile.renameTo(destFile)) {
                            tmpFile.copyTo(destFile, overwrite = true)
                            tmpFile.delete()
                        }
                    }
                    fileCount++
                    Timber.d("  extracted: %s", name)
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
            Timber.d("Zip extraction complete: %d files", fileCount)
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