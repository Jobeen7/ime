package com.jobeen.ime.base.webserver

import android.webkit.MimeTypeMap
import java.io.File

/**
 * Wi-Fi 传输文件存储（WO52 4.4）：根目录＝应用 external files dir，
 * 全部操作先规范化再约束在根内；符号链接逃逸与越界一律拒绝。
 *
 * 受保护路径（DELETE → 403，清单见 [PROTECTED_RELATIVE_NAMES]）：
 * - `user/`：方案目录子树（多目录方案集，删了丢方案）；
 * - `skin_library/`：皮肤目录子树（删了丢皮肤）；
 * - `shared/`：系统文件（内置共享方案与预编译数据）；
 * - 根目录本身（path 为空或 "/"）。
 * 注意：皮肤路径另禁符号链接皮肤目录（[isSkinSymlink]）——
 * 皮肤安装只走 /api/skins/apply（校验后写盘），不走通用写口。
 */
object WebServerPaths {
    /** 受保护相对路径（根下第一级目录名）。 */
    val PROTECTED_RELATIVE_NAMES: Set<String> = setOf("user", "skin_library", "shared")

    /**
     * 把请求相对路径规范化为根内文件：空串即根；绝对路径、
     * ".." 逃逸、空段均拒绝（返回 null）。不触碰磁盘
     * （符号链接判定另见 [isSkinSymlink]）。
     */
    fun resolveWithin(root: File, relPath: String): File? {
        val clean = relPath.trim().replace('\\', '/').trim('/')
        if (clean.isEmpty()) return root
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        var depth = 0
        for (part in parts) {
            if (part == "..") {
                depth--
                if (depth < 0) return null
            } else {
                depth++
            }
        }
        // 折叠 ".."（已验不逃逸）：
        val folded = ArrayDeque<String>()
        for (part in parts) {
            if (part == "..") folded.removeLastOrNull() else folded.addLast(part)
        }
        var file = root
        for (part in folded) file = File(file, part)
        return file
    }

    /** 该文件（或其任一祖先，至根为止）是否在受保护子树内。 */
    fun isProtected(root: File, file: File): Boolean {
        if (file == root) return true
        val rel = file.path.removePrefix(root.path + File.separator)
        val first = rel.substringBefore(File.separator)
        return first in PROTECTED_RELATIVE_NAMES
    }

    /**
     * 皮肤路径禁符号链接皮肤目录：从皮肤库根向下任一路径段是
     * 符号链接即返回 true（调用方拒绝该请求）。
     */
    fun isSkinSymlink(skinLibraryRoot: File, file: File): Boolean {
        var current = file
        while (true) {
            if (current == skinLibraryRoot) return false
            if (current.parentFile == null) return false
            // pre-26 等价判定：canonicalPath 会解析掉符号链接，
            // absolutePath 只是词法绝对化；两者不等即路径中含符号链接
            // （或非常规段），保守按链接处理。canonicalPath 取不到时
            // 同样保守拒绝。
            val canonical = runCatching { current.canonicalPath }.getOrNull() ?: return true
            if (canonical != current.absolutePath) return true
            current = current.parentFile ?: return false
            if (!current.path.startsWith(skinLibraryRoot.path)) return false
        }
    }
}

data class WebEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long,
)

enum class DeleteResult { OK, NOT_FOUND, ROOT, PROTECTED, FAILED }

enum class WriteResult { OK, CONFLICT, FAILED }

/** 文件操作口：生产实现走磁盘（[FileWebServerStore]），单测用内存 fake。 */
interface WebServerStore {
    val root: File

    fun listDir(rel: String): List<WebEntry>?
    fun listRecursive(rel: String): List<WebEntry>?
    fun readFile(rel: String): File?
    fun fileSize(rel: String): Long?
    fun writeFile(rel: String, bytes: ByteArray, override: Boolean): WriteResult
    fun appendFile(rel: String, bytes: ByteArray, expectedOffset: Long): Long?
    /** 流式写盘：从临时文件边读边写，不整块进堆（A-25；与 writeFile 同口径）。 */
    fun writeFileStream(rel: String, source: File, override: Boolean): WriteResult
    /** 流式追加：从临时文件边读边写到 offset 处（A-25；与 appendFile 同口径）。 */
    fun appendFileStream(rel: String, source: File, expectedOffset: Long): Long?
    fun mkdirs(rel: String): Boolean
    fun delete(rel: String): DeleteResult
    fun move(fromRel: String, toRel: String, copy: Boolean): Boolean
    fun exists(rel: String): Boolean
    fun isDir(rel: String): Boolean
    fun usage(): Pair<Long, Long>
    fun mimeType(rel: String): String
}

/** 磁盘实现：全部入口先过 [WebServerPaths.resolveWithin]。 */
class FileWebServerStore(
    override val root: File,
    private val totalBytesProvider: () -> Long,
) : WebServerStore {

    override fun listDir(rel: String): List<WebEntry>? {
        val dir = WebServerPaths.resolveWithin(root, rel) ?: return null
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.sortedByDescending { it.name }
            ?.map { it.toEntry() }
            ?: emptyList()
    }

    override fun listRecursive(rel: String): List<WebEntry>? {
        val dir = WebServerPaths.resolveWithin(root, rel) ?: return null
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .filter { it != dir }
            .map { it.toEntry() }
            .toList()
    }

    override fun readFile(rel: String): File? {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return null
        return if (file.isFile) file else null
    }

    override fun fileSize(rel: String): Long? {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return null
        return if (file.isFile) file.length() else null
    }

    override fun writeFile(rel: String, bytes: ByteArray, override: Boolean): WriteResult {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return WriteResult.FAILED
        if (file.exists() && !override) return WriteResult.CONFLICT
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            WriteResult.OK
        }.getOrDefault(WriteResult.FAILED)
    }

    override fun appendFile(rel: String, bytes: ByteArray, expectedOffset: Long): Long? {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return null
        if (!file.isFile || file.length() != expectedOffset) return null
        return runCatching {
            file.appendBytes(bytes)
            file.length()
        }.getOrNull()
    }

    override fun writeFileStream(rel: String, source: File, override: Boolean): WriteResult {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return WriteResult.FAILED
        if (file.exists() && !override) return WriteResult.CONFLICT
        return runCatching {
            file.parentFile?.mkdirs()
            source.inputStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            WriteResult.OK
        }.getOrDefault(WriteResult.FAILED)
    }

    override fun appendFileStream(rel: String, source: File, expectedOffset: Long): Long? {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return null
        if (!file.isFile || file.length() != expectedOffset) return null
        return runCatching {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(expectedOffset)
                source.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                    }
                }
            }
            file.length()
        }.getOrNull()
    }

    override fun mkdirs(rel: String): Boolean {
        val dir = WebServerPaths.resolveWithin(root, rel) ?: return false
        return runCatching {
            if (dir.exists()) dir.isDirectory else dir.mkdirs()
        }.getOrDefault(false)
    }

    override fun delete(rel: String): DeleteResult {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return DeleteResult.FAILED
        if (file == root) return DeleteResult.ROOT
        if (WebServerPaths.isProtected(root, file)) return DeleteResult.PROTECTED
        if (!file.exists()) return DeleteResult.NOT_FOUND
        return if (file.deleteRecursively()) DeleteResult.OK else DeleteResult.FAILED
    }

    override fun move(fromRel: String, toRel: String, copy: Boolean): Boolean {
        val from = WebServerPaths.resolveWithin(root, fromRel) ?: return false
        val to = WebServerPaths.resolveWithin(root, toRel) ?: return false
        if (!from.exists()) return false
        return runCatching {
            to.parentFile?.mkdirs()
            if (copy) {
                if (from.isDirectory) {
                    from.copyRecursively(to, overwrite = true)
                } else {
                    from.copyTo(to, overwrite = true)
                    true
                }
            } else {
                from.renameTo(to)
            }
        }.getOrDefault(false)
    }

    override fun exists(rel: String): Boolean {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return false
        return file.exists()
    }

    override fun isDir(rel: String): Boolean {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return false
        return file.isDirectory
    }

    override fun usage(): Pair<Long, Long> {
        val used = runCatching {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        return used to totalBytesProvider()
    }

    override fun mimeType(rel: String): String {
        val file = WebServerPaths.resolveWithin(root, rel) ?: return "application/octet-stream"
        val ext = file.extension.lowercase()
        val fromSystem = runCatching {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull()
        return fromSystem ?: COMMON_MIME_TYPES[ext] ?: "application/octet-stream"
    }

    private fun File.toEntry(): WebEntry =
        WebEntry(name, isDirectory, if (isDirectory) 0L else length(), lastModified())

    companion object {
        /** JVM 单测下 MimeTypeMap 不可用时的回落表。 */
        private val COMMON_MIME_TYPES: Map<String, String> = mapOf(
            "json" to "application/json",
            "txt" to "text/plain",
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "js" to "text/javascript",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "zip" to "application/zip",
            "yaml" to "text/yaml",
            "yml" to "text/yaml",
            "mp3" to "audio/mpeg",
            "mp4" to "video/mp4",
            "pdf" to "application/pdf",
        )
    }
}
