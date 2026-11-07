package com.jobeen.ime

import com.jobeen.ime.base.update.DictFingerprint
import com.jobeen.ime.base.update.ZipCentralDirectory
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中央目录解析与词库指纹口径：远端（zip 条目）与本地（文件实测）
 * 对同一批 dict 内容必须算出同一指纹——这是检查更新时能实时显示
 * 远端词库指纹、且与本地行同值同色的前提。
 */
class ZipCentralDirectoryTest {

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun crcOf(content: ByteArray): Long =
        CRC32().apply { update(content) }.value

    /** 与管理器相同的过滤＋元组＋合成口径，作用于中央目录条目。 */
    private fun fingerprintFromEntries(entries: List<ZipCentralDirectory.Entry>): String? {
        val parts = entries.asSequence()
            .filter {
                !it.name.endsWith("/") &&
                    it.name.startsWith("dicts/") &&
                    it.name.endsWith(".dict.yaml")
            }
            .map {
                DictFingerprint.entryPart(
                    it.name.removePrefix("dicts/"),
                    it.size,
                    it.crc32,
                )
            }
            .toList()
        if (parts.isEmpty()) return null
        return DictFingerprint.combine(parts.sorted())
    }

    @Test
    fun centralDirectoryFingerprintMatchesLocalTupleFingerprint() {
        val dictA = "jichu.dict.yaml".toByteArray()
        val dictB = "ci content longer".toByteArray()
        val zip = zipBytes(
            listOf(
                "dicts/jichu.dict.yaml" to dictA,
                "dicts/sub/ci.dict.yaml" to dictB,
                "dicts/readme.txt" to "ignored".toByteArray(),
                "lua/other.dict.yaml" to "wrong prefix".toByteArray(),
            )
        )
        val eocd = ZipCentralDirectory.findEocd(zip, 0)
        assertTrue("EOCD should be found", eocd != null)
        val cd = zip.copyOfRange(
            eocd!!.cdOffset.toInt(),
            (eocd.cdOffset + eocd.cdSize).toInt(),
        )
        val entries = ZipCentralDirectory.parseEntries(cd)
        assertTrue("entries should parse", entries != null)
        assertEquals(4, entries!!.size)

        val remoteFp = fingerprintFromEntries(entries)
        // 本地口径：同一批文件按相对路径/大小/CRC32 组元组
        val localParts = listOf(
            DictFingerprint.entryPart("jichu.dict.yaml", dictA.size.toLong(), crcOf(dictA)),
            DictFingerprint.entryPart("sub/ci.dict.yaml", dictB.size.toLong(), crcOf(dictB)),
        )
        val localFp = DictFingerprint.combine(localParts.sorted())
        assertEquals(localFp, remoteFp)
    }

    @Test
    fun corruptTailFindsNoEocd() {
        assertNull(ZipCentralDirectory.findEocd(ByteArray(1024) { 7 }, 0))
        assertNull(ZipCentralDirectory.findEocd(ByteArray(10), 0))
    }

    @Test
    fun truncatedCentralDirectoryFails() {
        val zip = zipBytes(listOf("dicts/a.dict.yaml" to "x".toByteArray()))
        val eocd = ZipCentralDirectory.findEocd(zip, 0)!!
        val cd = zip.copyOfRange(
            eocd.cdOffset.toInt(),
            (eocd.cdOffset + eocd.cdSize).toInt(),
        )
        assertNull(ZipCentralDirectory.parseEntries(cd.copyOf(20)))
    }

    @Test
    fun noAllowedEntriesYieldsNullFingerprint() {
        val zip = zipBytes(listOf("dicts/readme.txt" to "x".toByteArray()))
        val eocd = ZipCentralDirectory.findEocd(zip, 0)!!
        val cd = zip.copyOfRange(
            eocd.cdOffset.toInt(),
            (eocd.cdOffset + eocd.cdSize).toInt(),
        )
        val entries = ZipCentralDirectory.parseEntries(cd)!!
        assertNull(fingerprintFromEntries(entries))
    }
}
