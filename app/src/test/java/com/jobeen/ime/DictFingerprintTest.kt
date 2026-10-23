package com.jobeen.ime

import com.jobeen.ime.base.update.DictFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 词库内容指纹口径（S3 后仅存本地计算）：元组格式、combine 的确
 * 定性与顺序敏感性。原 ZipCentralDirectoryTest 随中央目录解析器
 * 一并退役——远端指纹不再实时取数，指纹只由本地文件元组算出。
 */
class DictFingerprintTest {

    @Test
    fun entryPart_formatIsPathSizeHexCrc() {
        assertEquals("a.yaml|123|1a2b", DictFingerprint.entryPart("a.yaml", 123L, 0x1a2bL))
        assertEquals("sub/b.dict.yaml|0|0", DictFingerprint.entryPart("sub/b.dict.yaml", 0L, 0L))
    }

    @Test
    fun combine_deterministicForSameParts() {
        val parts = listOf("a|1|ff", "b|2|ee")
        assertEquals(DictFingerprint.combine(parts), DictFingerprint.combine(parts))
        // 64 位十六进制（SHA-256）
        assertEquals(64, DictFingerprint.combine(parts).length)
    }

    @Test
    fun combine_orderSensitiveAndContentSensitive() {
        val a = DictFingerprint.entryPart("a.yaml", 10L, 0x11L)
        val b = DictFingerprint.entryPart("b.yaml", 20L, 0x22L)
        // 调用方负责先排序；combine 本身不重排，顺序不同指纹不同
        assertNotEquals(DictFingerprint.combine(listOf(a, b)), DictFingerprint.combine(listOf(b, a)))
        // 任一元组字段变化（大小/CRC）指纹随之变化
        val bChanged = DictFingerprint.entryPart("b.yaml", 21L, 0x22L)
        assertNotEquals(DictFingerprint.combine(listOf(a, b)), DictFingerprint.combine(listOf(a, bChanged)))
    }
}
