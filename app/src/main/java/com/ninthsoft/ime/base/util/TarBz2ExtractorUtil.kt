package com.ninthsoft.ime.base.util

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * .tar.bz2 解包工具：针对 BZip2 的流特性进行了多层缓存优化，支持保留文件和目录的 mtime。
 */
object TarBz2ExtractorUtil {

    private const val BUFFER_SIZE = 64 * 1024
    private const val STREAM_BUFFER = 256 * 1024
    private const val REPORT_STEP = 256 * 1024L
    private const val LOG_STEP = 16 * 1024 * 1024L

    fun extract(
        archive: File,
        destDir: File,
        onProgress: (current: Long, total: Long) -> Unit = { _, _ -> },
        filter: (entryName: String) -> Boolean = { true },
    ) {
        destDir.mkdirs()
        val destPath =
            destDir.canonicalPath.let { if (it.endsWith(File.separator)) it else "$it${File.separator}" }
        val total = archive.length()

        val counting = CountingInputStream(FileInputStream(archive))
        val state = ProgressState()
        val sharedBuffer = ByteArray(BUFFER_SIZE)

        counting.use { cis ->
            BufferedInputStream(cis, STREAM_BUFFER).use { bufferedIn ->
                BZip2CompressorInputStream(bufferedIn).use { bzIn ->
                    BufferedInputStream(bzIn, STREAM_BUFFER).use { tarBufferedIn ->
                        TarArchiveInputStream(tarBufferedIn).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                val entryName = entry.name
                                val outFile = File(destDir, entryName)
                                val outPath = outFile.canonicalPath

                                // 严格路径穿越防护
                                if (!outPath.startsWith(destPath) && outPath != destDir.canonicalPath) {
                                    Timber.w("Skip unsafe archive entry: %s", entryName)
                                } else {
                                    val shouldExtract = filter(entryName)
                                    if (entry.isDirectory) {
                                        if (shouldExtract) {
                                            outFile.mkdirs()
                                            // 恢复目录的修改时间
                                            setEntryTime(outFile, entry)
                                        }
                                    } else {
                                        if (shouldExtract) {
                                            outFile.parentFile?.mkdirs()
                                            BufferedOutputStream(
                                                outFile.outputStream(), STREAM_BUFFER
                                            ).use { os ->
                                                pump(tarIn, os, counting, total, onProgress, state, sharedBuffer)
                                            }
                                            // 文件写入完成后，恢复文件的修改时间 (mtime)
                                            setEntryTime(outFile, entry)
                                        } else {
                                            // 过滤条目：纯消费跳过，不写盘
                                            pump(tarIn, null, counting, total, onProgress, state, sharedBuffer)
                                        }
                                    }
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }
            }
        }
        onProgress(counting.bytesRead.coerceAtMost(total), total)
    }

    private fun setEntryTime(file: File, entry: TarArchiveEntry) {
        try {
            entry.lastModifiedDate?.time?.let { time ->
                if (time > 0) {
                    file.setLastModified(time)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to set mtime for %s", file.name)
        }
    }

    private fun pump(
        tarIn: TarArchiveInputStream,
        out: OutputStream?,
        counting: CountingInputStream,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        state: ProgressState,
        buffer: ByteArray,
    ) {
        var n: Int
        while (tarIn.read(buffer).also { n = it } != -1) {
            out?.write(buffer, 0, n)
            val currentBytes = counting.bytesRead

            if (currentBytes - state.lastReported >= REPORT_STEP) {
                state.lastReported = currentBytes
                onProgress(currentBytes, total)
            }
            if (currentBytes - state.lastLogged >= LOG_STEP) {
                state.lastLogged = currentBytes
                Timber.d("Extracting... %.1f%%", currentBytes * 100f / total)
            }
        }
        out?.flush()
    }

    private data class ProgressState(
        var lastReported: Long = 0L,
        var lastLogged: Long = 0L,
    )

    private class CountingInputStream(private val src: InputStream) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            return src.read().also { if (it >= 0) bytesRead++ }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return src.read(b, off, len).also { if (it > 0) bytesRead += it }
        }

        override fun close() = src.close()
    }
}