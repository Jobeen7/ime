package com.jobeen.ime.base.update

import com.jobeen.ime.base.util.DigestUtil

/**
 * 词库内容指纹的元组口径：每个文件一条「相对路径|大小|CRC32」
 * part，排序后以换行连接取 SHA-256。本地现用词库与下载暂存词库
 * 共用同一口径，指纹可直接比对（远端 zip 中央目录实时取数已按
 * S3 退役，本口径只在本地文件上计算）。
 */
internal object DictFingerprint {

    fun entryPart(relPath: String, size: Long, crc32: Long): String =
        "$relPath|$size|${crc32.toString(16)}"

    fun combine(sortedParts: List<String>): String =
        DigestUtil.sha256Hex(sortedParts.joinToString("\n").toByteArray(Charsets.UTF_8))
}
