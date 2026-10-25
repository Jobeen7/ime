package com.jobeen.ime.data.manager

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 单目录方案导入：方案包直接解压到目标目录，不做事务与回滚。
 *
 * 原则：
 * - 用户文件（受保护条目）永不覆盖，命中即跳过并计数。
 * - 路径穿越（`..` 段、绝对路径）一律挡住，不写盘。
 * - 全部文件条目共享同一顶层目录时才剥离该前缀；混有根级文件时不剥离。
 */

/**
 * 一次方案包解压的汇总结果。
 *
 * @property installed 实际写出的文件数。
 * @property skippedProtected 因命中用户保护规则而跳过的文件数。
 * @property skippedUnsafe 因路径不安全（穿越、绝对路径、canonical 校验失败）而跳过的文件数。
 * @property newSchemaIds 根级方案文件（形如 `xxx.schema.yaml`）的主干名，按条目顺序，不去重。
 * @property totalBytes 实际写出的总字节数。
 */
data class ExtractResult(
    val installed: Int,
    val skippedProtected: Int,
    val skippedUnsafe: Int,
    val newSchemaIds: List<String>,
    val totalBytes: Long,
)

/**
 * 判断字节数组是否以 ZIP 魔数开头。
 *
 * 契约：长度至少 4，且前 4 字节依次为 0x50、0x4B、0x03、0x04 时返回 true，否则 false。
 *
 * @param bytes 待检查的字节数组，可为任意长度。
 * @return 是 ZIP 本地文件头时返回 true，否则 false。
 */
fun isZipMagic(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    return bytes[0].toInt() == 0x50 &&
        bytes[1].toInt() == 0x4B &&
        bytes[2].toInt() == 0x03 &&
        bytes[3].toInt() == 0x04
}

/**
 * 计算可剥离的顶层目录前缀。
 *
 * 契约：
 * - [names] 为空时返回 null。
 * - 取首个名字的 `/` 之前部分为候选前缀；首个名字不含 `/` 时返回 null。
 * - 全部名字都以 `前缀 + "/"` 开头时返回该前缀，否则返回 null。
 *
 * @param names 条目名列表（由调用方保证是文件条目）。
 * @return 可安全剥离的顶层目录名；不符合条件时返回 null。
 */
fun stripTopDir(names: List<String>): String? {
    if (names.isEmpty()) return null
    val first = names.first()
    val slashIdx = first.indexOf('/')
    if (slashIdx <= 0) return null
    val prefix = first.substring(0, slashIdx)
    val prefixSlash = prefix + "/"
    // 空目录名（前缀为空串）不视为有效顶层目录。
    if (prefix.isEmpty()) return null
    for (name in names) {
        if (!name.startsWith(prefixSlash)) return null
    }
    return prefix
}

/**
 * 判断相对路径是否为受保护的用户文件（永不覆盖）。
 *
 * 契约：
 * - rel 按 `/` 分段，任一段以 `.userdb` 结尾时返回 true。
 * - 取最后一段为文件名：根级（只有一段）的 `default.yaml` 返回 true；
 *   文件名以 `.custom.yaml` 结尾返回 true；文件名为 `custom_phrase.txt` 返回 true；
 *   文件名以 `.userdict.txt` 结尾返回 true。
 * - 其余返回 false。
 *
 * @param rel 相对路径（已剥离顶层目录前缀）。
 * @return 命中保护规则返回 true，否则 false。
 */
fun isProtectedEntry(rel: String): Boolean {
    if (rel.isEmpty()) return false
    val segments = rel.split('/')
    for (segment in segments) {
        if (segment.endsWith(".userdb")) return true
    }
    val fileName = segments.last()
    if (segments.size == 1 && fileName == "default.yaml") return true
    if (fileName.endsWith(".custom.yaml")) return true
    if (fileName == "custom_phrase.txt") return true
    if (fileName.endsWith(".userdict.txt")) return true
    return false
}

/**
 * 将 ZIP 方案包直接解压到目标目录（单目录模式，无事务与回滚）。
 *
 * 契约：
 * - 用 [ZipFile] 打开；打不开或条目损坏时抛 [IOException]，由调用方处理。
 * - 只处理文件条目；目录条目（名字以 `/` 结尾）忽略。
 * - 用全部文件条目名计算 [stripTopDir]；有前缀时剥离 `前缀 + "/"`；剥离后为空的条目跳过（不计数）。
 * - 相对路径含 `..` 段或为绝对路径（以 `/` 开头）时记 skippedUnsafe，不写盘。
 * - [isProtectedEntry] 为真时记 skippedProtected，不写盘。
 * - 其余条目写入 targetDir 下的同相对路径：写盘前做 canonical 前缀校验，失败记 skippedUnsafe；
 *   父目录按需创建；同名直接覆盖。
 * - 写盘成功记 installed 并累计写出字节数；相对路径只有一段且以 `.schema.yaml` 结尾时，
 *   把文件名去掉后缀的主干记入 newSchemaIds（保持顺序，不去重）。
 * - ZipFile 必须可靠关闭。
 *
 * @param zipFile 待解压的 ZIP 文件。
 * @param targetDir 目标目录，不存在时按需创建。
 * @return 本次解压的汇总结果。
 * @throws IOException ZIP 打不开、条目读取损坏或写盘失败时抛出。
 */
fun extractZip(zipFile: File, targetDir: File): ExtractResult {
    targetDir.mkdirs()
    ZipFile(zipFile).use { zip ->
        val fileEntries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
        val entryNames = fileEntries.map { it.name }
        val topDir = stripTopDir(entryNames)
        val prefixSlash = topDir?.let { it + "/" } ?: ""

        var installed = 0
        var skippedProtected = 0
        var skippedUnsafe = 0
        var totalBytes = 0L
        val newSchemaIds = mutableListOf<String>()

        for (entry in fileEntries) {
            var relativePath = entry.name
            if (prefixSlash.isNotEmpty() && relativePath.startsWith(prefixSlash)) {
                relativePath = relativePath.substring(prefixSlash.length)
            }

            if (relativePath.isEmpty()) continue

            if (relativePath.startsWith("/") || relativePath.split("/").any { it == ".." }) {
                skippedUnsafe += 1
                continue
            }

            if (isProtectedEntry(relativePath)) {
                skippedProtected += 1
                continue
            }

            val outFile = File(targetDir, relativePath)
            val targetCanonical = targetDir.canonicalFile
            val outCanonical = outFile.canonicalFile
            if (outCanonical != targetCanonical && !outCanonical.path.startsWith(targetCanonical.path + File.separator)) {
                skippedUnsafe += 1
                continue
            }

            outFile.parentFile?.mkdirs()

            val bytes = zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            totalBytes += bytes
            installed += 1

            val parts = relativePath.split("/")
            if (parts.size == 1 && relativePath.endsWith(".schema.yaml")) {
                newSchemaIds.add(relativePath.removeSuffix(".schema.yaml"))
            }
        }

        return ExtractResult(installed, skippedProtected, skippedUnsafe, newSchemaIds, totalBytes)
    }
}

/**
 * 按导入体积估算部署超时时间（毫秒）。
 *
 * 理由：真机探针编 9 个 lite 方案约 22 秒，lite 全量部署在几十秒量级；
 * 180 秒基线约为典型耗时的 8 倍余量；每 8MiB 额外增加 30 秒覆盖大体积导入；
 * 上限 1800 秒防止无限等待。
 *
 * 契约：totalBytes 小于 0 时按 0 计；返回 180_000 + (totalBytes / 8_388_608) * 30_000，
 * 再与 1_800_000 取最小值。
 * 示例：0 → 180000，8MiB → 210000，66MiB → 420000，超大 → 1800000。
 *
 * @param totalBytes 导入数据总字节数。
 * @return 建议的超时时间（毫秒）。
 */
fun deployTimeoutMs(totalBytes: Long): Long {
    val safeBytes = if (totalBytes < 0L) 0L else totalBytes
    val computed = 180_000L + (safeBytes / 8_388_608L) * 30_000L
    return minOf(computed, 1_800_000L)
}