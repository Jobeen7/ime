package com.jobeen.ime.skin.pkg

import java.io.File
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * .jime zip 安全解包（定稿十一.1/11.2，B1 新实现）：
 *
 * - 包级上限：原始 ≤10MB、展开 ≤40MB、单条目 ≤8MB、条目 ≤100、
 *   整体压缩比 ≤100:1，任一超限整包拒绝。
 * - 路径：归一化后校验——拒 ../、绝对路径、盘符、反斜杠、归一化
 *   重名（NFC + 大小写不敏感）、嵌套 zip/.jime。
 * - 符号链接：java.util.zip 不暴露条目 unix 模式，无从判别；本实现
 *   只把条目内容写为普通文件字节且全程不出暂存区，链接语义不被
 *   跟随（落盘的是链接目标路径字符串本身，作为普通数据文件）。
 *
 * 纯 JVM 实现（java.util.zip），可直接单测。
 */
object ZipSafeExtractor {

    const val MAX_RAW_BYTES = 10L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 40L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_ENTRIES = 100
    const val MAX_RATIO = 100.0

    data class ExtractedFile(val path: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ExtractedFile && path == other.path && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
    }

    sealed interface Result {
        data class Ok(val files: List<ExtractedFile>) : Result
        data class Rejected(val reason: String) : Result
    }

    fun extract(zipFile: File): Result {
        if (!zipFile.isFile) return Result.Rejected("file_missing")
        val rawBytes = zipFile.length()
        if (rawBytes > MAX_RAW_BYTES) return Result.Rejected("zip_too_large:$rawBytes")
        val files = mutableListOf<ExtractedFile>()
        val seenNormalized = mutableSetOf<String>()
        var totalExpanded = 0L
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (files.size >= MAX_ENTRIES) {
                        return Result.Rejected("too_many_entries")
                    }
                    validateEntryName(name)?.let { return Result.Rejected(it) }
                    val normalized = normalizePath(name)
                    if (!seenNormalized.add(normalized)) {
                        return Result.Rejected("duplicate_path:$name")
                    }
                    val lower = name.lowercase()
                    if (lower.endsWith(".zip") || lower.endsWith(".jime")) {
                        return Result.Rejected("nested_archive:$name")
                    }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val out = java.io.ByteArrayOutputStream()
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES) {
                            return Result.Rejected("entry_too_large:$name")
                        }
                        totalExpanded += read
                        if (totalExpanded > MAX_EXPANDED_BYTES) {
                            return Result.Rejected("expanded_too_large")
                        }
                        out.write(buffer, 0, read)
                    }
                    files += ExtractedFile(name, out.toByteArray())
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return Result.Rejected("zip_unreadable:${e.javaClass.simpleName}")
        }
        if (rawBytes > 0 && totalExpanded.toDouble() / rawBytes > MAX_RATIO) {
            return Result.Rejected("compression_ratio_too_high")
        }
        if (files.isEmpty()) return Result.Rejected("zip_empty")
        return Result.Ok(files)
    }

    /** 路径安全校验（定稿十一.2）；合法返回 null，否则返回拒绝原因。 */
    fun validateEntryName(name: String): String? {
        if (name.isBlank()) return "empty_path"
        if (name.contains('\\')) return "backslash_in_path:$name"
        if (name.startsWith("/")) return "absolute_path:$name"
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) {
            return "drive_letter_path:$name"
        }
        val segments = name.split('/')
        if (segments.any { it == ".." }) return "parent_ref_in_path:$name"
        if (segments.any { it.isEmpty() }) return "empty_segment_in_path:$name"
        return null
    }

    /** 路径归一口径（定稿七.3）：Unicode NFC + 大小写不敏感。 */
    fun normalizePath(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase()
}
