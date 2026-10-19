package com.jobeen.ime

import com.jobeen.ime.base.util.DigestUtil
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DigestUtil 已知向量测试：期望值全部取自 FIPS 180-2 / RFC 6234
 * 公布的标准 SHA-256 测试向量，不从被测实现反推。
 */
class DigestUtilTest {

    // FIPS 180-2: SHA-256("")
    private val sha256Empty =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    // FIPS 180-2: SHA-256("abc")
    private val sha256Abc =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    // FIPS 180-2: SHA-256("a" × 1,000,000)
    private val sha256MillionA =
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"

    @Test
    fun bytesVectors() {
        assertEquals(sha256Empty, DigestUtil.sha256Hex(ByteArray(0)))
        assertEquals(sha256Abc, DigestUtil.sha256Hex("abc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun toHexPadsAndTreatsBytesUnsigned() {
        // 0x00/0x0f 必须补零；0xff/0xab 等负值字节必须按无符号输出
        // 两位（历史实现用 "%02x".format(byte) 的口径），不能符号扩展
        assertEquals(
            "000fffab",
            DigestUtil.toHex(byteArrayOf(0x00, 0x0f, 0xff.toByte(), 0xab.toByte())),
        )
    }

    @Test
    fun incrementalDigestMatchesOneShot() {
        // AppUpdateManager 的用法：边下载边增量喂入，最后 hexDigest 收尾，
        // 结果必须与一次性哈希相同
        val digest = DigestUtil.newSha256Digest()
        digest.update("ab".toByteArray(Charsets.UTF_8))
        digest.update("c".toByteArray(Charsets.UTF_8))
        assertEquals(sha256Abc, DigestUtil.hexDigest(digest))
    }

    @Test
    fun fileVectors() {
        val empty = File.createTempFile("digest-empty", ".bin")
        val abc = File.createTempFile("digest-abc", ".bin")
        try {
            abc.writeBytes("abc".toByteArray(Charsets.UTF_8))
            assertEquals(sha256Empty, DigestUtil.sha256Hex(empty))
            assertEquals(sha256Abc, DigestUtil.sha256Hex(abc))
        } finally {
            empty.delete()
            abc.delete()
        }
    }

    @Test
    fun largeFileStreamedAndCancellableMatchesVector() {
        // 1MB 输入跨越 32KB 缓冲边界（32 块），且可取消版每 32 块走
        // 一次 ensureActive 检查点：两条路径结果都必须等于标准向量
        val file = File.createTempFile("digest-large", ".bin")
        try {
            file.writeBytes(ByteArray(1_000_000) { 'a'.code.toByte() })
            assertEquals(sha256MillionA, DigestUtil.sha256Hex(file))
            val cancellable = runBlocking { DigestUtil.sha256HexCancellable(file) }
            assertEquals(sha256MillionA, cancellable)
        } finally {
            file.delete()
        }
    }

    @Test
    fun hexLengthIs64Lowercase() {
        val hex = DigestUtil.sha256Hex("jime".toByteArray(Charsets.UTF_8))
        assertTrue(hex.matches(Regex("[0-9a-f]{64}")))
    }
}
