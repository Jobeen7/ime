package com.jobeen.ime.base.update

import com.jobeen.ime.base.util.DigestUtil

/**
 * zip 中央目录解析（纯函数，无 IO）：检查更新时只用 Range 请求取回
 * 方案包末尾的中央目录，即可得到每个条目的大小与 CRC32，无需整包
 * 下载就能算出与本地同口径的词库指纹。
 */
object ZipCentralDirectory {

    data class Entry(val name: String, val size: Long, val crc32: Long)

    data class Eocd(val cdOffset: Long, val cdSize: Long, val totalEntries: Int)

    private const val EOCD_SIG = 0x06054b50L
    private const val CD_SIG = 0x02014b50L
    private const val EOCD_FIXED = 22
    private const val MAX_COMMENT = 65535

    /**
     * 在包尾字节中定位 EOCD 记录。[tail] 是文件末尾的一段字节，
     * 其首字节在整个文件中的偏移为 [tailStart]（本函数只用它校验
     * 记录位置自洽，偏移换算由调用方做）。格式异常返回 null。
     */
    fun findEocd(tail: ByteArray, tailStart: Long): Eocd? {
        if (tail.size < EOCD_FIXED) return null
        val earliest = maxOf(0, tail.size - EOCD_FIXED - MAX_COMMENT)
        for (i in tail.size - EOCD_FIXED downTo earliest) {
            if (u32(tail, i) != EOCD_SIG) continue
            val commentLen = u16(tail, i + 20)
            if (i + EOCD_FIXED + commentLen != tail.size) continue
            // 多盘包与 zip64 EOCD 不支持：词库包不属于这两种形态，
            // 命中即放弃实时指纹、回落既有记录口径
            if (u16(tail, i + 4) != 0 || u16(tail, i + 6) != 0) return null
            val total = u16(tail, i + 10)
            val cdSize = u32(tail, i + 12)
            val cdOffset = u32(tail, i + 16)
            if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                return null
            }
            if (cdOffset + cdSize > tailStart + tail.size) return null
            return Eocd(cdOffset, cdSize, total)
        }
        return null
    }

    /** 解析中央目录字节为条目列表；任一条目格式损坏返回 null。 */
    fun parseEntries(cd: ByteArray): List<Entry>? {
        val out = ArrayList<Entry>()
        var pos = 0
        while (pos < cd.size) {
            if (pos + 46 > cd.size || u32(cd, pos) != CD_SIG) return null
            val crc = u32(cd, pos + 16)
            var size = u32(cd, pos + 24)
            val nameLen = u16(cd, pos + 28)
            val extraLen = u16(cd, pos + 30)
            val commentLen = u16(cd, pos + 32)
            val nameStart = pos + 46
            val extraStart = nameStart + nameLen
            val next = extraStart + extraLen + commentLen
            if (next > cd.size) return null
            if (size == 0xFFFFFFFFL) {
                size = zip64Size(cd, extraStart, extraLen) ?: return null
            }
            out.add(Entry(String(cd, nameStart, nameLen, Charsets.UTF_8), size, crc))
            pos = next
        }
        return out
    }

    /** zip64 扩展字段（0x0001）首值即未压缩大小（固定部为 0xFFFFFFFF 时才出现）。 */
    private fun zip64Size(cd: ByteArray, extraStart: Int, extraLen: Int): Long? {
        var p = extraStart
        val end = extraStart + extraLen
        while (p + 4 <= end) {
            val id = u16(cd, p)
            val len = u16(cd, p + 2)
            if (p + 4 + len > end) return null
            if (id == 0x0001) {
                if (len < 8) return null
                return u64(cd, p + 4)
            }
            p += 4 + len
        }
        return null
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u64(b: ByteArray, off: Int): Long =
        u32(b, off) or (u32(b, off + 4) shl 32)
}

/**
 * 词库指纹的元组口径：本地走文件实测、远端走中央目录，两边拼同一
 * 格式的「相对路径|大小|CRC32」元组，排序后同法合成，保证同内容
 * 同指纹、可直接比对。
 */
object DictFingerprint {

    fun entryPart(relPath: String, size: Long, crc32: Long): String =
        "$relPath|$size|${crc32.toString(16)}"

    /** 与 WanxiangUpdateManager 既有合成口径一致：逐段喂入同一 SHA-256。 */
    fun combine(parts: List<String>): String {
        val digest = DigestUtil.newSha256Digest()
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return DigestUtil.hexDigest(digest)
    }
}
