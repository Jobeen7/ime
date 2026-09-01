package com.ninthsoft.ime.base.util

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

                if (!destFile.path.startsWith(destCanonicalPath)) {
                    Timber.w("  skipped illegal path: %s", name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { out ->
                        zip.copyTo(out, 64 * 1024)
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