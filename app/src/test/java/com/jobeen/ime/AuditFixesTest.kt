package com.jobeen.ime

import com.jobeen.ime.base.ngram.normalizeSha256
import com.jobeen.ime.base.ngram.pickExpectedSha256
import com.jobeen.ime.base.update.WanxiangUpdateManager
import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.backup.readBackupBytes
import com.jobeen.ime.ui.collectSearchMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Properties

class AuditFixesTest {

    // ── 备份流式上限读取 ──

    /** 记录实际被读走字节数的输入流，用于验证超限时不会把整个流读完 */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int {
            val r = delegate.read()
            if (r >= 0) bytesRead++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    @Test
    fun readBackupBytes_smallContent_roundTrips() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val out = readBackupBytes(ByteArrayInputStream(data), maxBytes = 200_000)
        assertTrue(data.contentEquals(out))
    }

    @Test
    fun readBackupBytes_exactlyAtLimit_passes() {
        val data = ByteArray(1_000) { 7 }
        val out = readBackupBytes(ByteArrayInputStream(data), maxBytes = 1_000)
        assertEquals(1_000, out.size)
    }

    @Test
    fun readBackupBytes_overLimit_throwsAndStopsReading() {
        val limit = 100_000
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(10_000_000) { 1 }))
        assertThrows(BackupManager.BackupException::class.java) {
            readBackupBytes(stream, maxBytes = limit)
        }
        // 中止点至多多读一个缓冲块，绝不能把 10MB 全量读完
        assertTrue(stream.bytesRead <= limit + 32 * 1024)
        assertTrue(stream.bytesRead < 10_000_000)
    }

    @Test
    fun readBackupBytes_emptyStream_returnsEmpty() {
        val out = readBackupBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 10)
        assertEquals(0, out.size)
    }

    // ── 万象更新日志解析（损坏日志必须判 null 交隔离处理，不能抛错堵死启动） ──

    private fun journalProps(vararg pairs: Pair<String, String>): Properties =
        Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    @Test
    fun parseUpdateJournal_complete_readsAllFlags() {
        val j = WanxiangUpdateManager.parseUpdateJournal(
            journalProps(
                "hadDicts" to "true", "hadGram" to "false",
                "changedDicts" to "true", "changedGram" to "true",
            )
        )!!
        assertTrue(j.hadDicts)
        assertTrue(!j.hadGram)
        assertTrue(j.changedDicts)
        assertTrue(j.changedGram)
    }

    @Test
    fun parseUpdateJournal_missingField_returnsNull() {
        val j = WanxiangUpdateManager.parseUpdateJournal(
            journalProps("hadDicts" to "true", "hadGram" to "true", "changedDicts" to "true")
        )
        assertNull(j)
    }

    @Test
    fun parseUpdateJournal_empty_returnsNull() {
        assertNull(WanxiangUpdateManager.parseUpdateJournal(Properties()))
    }

    // ── 文件搜索枚举限界 ──

    private fun tempTree(build: (File) -> Unit): File {
        val root = Files.createTempDirectory("search-test").toFile()
        build(root)
        return root
    }

    @Test
    fun collectSearchMatches_findsCaseInsensitivelyAcrossDirs() {
        val root = tempTree { r ->
            File(r, "Alpha.txt").writeText("x")
            val sub = File(r, "sub").apply { mkdirs() }
            File(sub, "beta-ALPHA.md").writeText("x")
            File(sub, "unrelated.bin").writeText("x")
        }
        val found = collectSearchMatches(root, "alpha")
        assertEquals(2, found.size)
    }

    @Test
    fun collectSearchMatches_resultsCapped() {
        val root = tempTree { r ->
            repeat(20) { File(r, "match-$it.txt").writeText("x") }
        }
        val found = collectSearchMatches(root, "match", maxResults = 5)
        assertEquals(5, found.size)
    }

    @Test
    fun collectSearchMatches_visitedBudgetBoundsHugeDirectory() {
        val root = tempTree { r ->
            repeat(500) { File(r, "filler-$it.dat").writeText("x") }
            File(r, "needle.txt").writeText("x")
        }
        // 预算远小于目录扇出：枚举必须停在预算内（needle 在枚举序末尾，不保证命中）
        val found = collectSearchMatches(root, "needle", maxVisited = 50)
        assertTrue(found.size <= 1)
        // 预算充足时仍能正常找到
        val foundAll = collectSearchMatches(root, "needle", maxVisited = 10_000)
        assertEquals(1, foundAll.size)
    }

    // ── 语法模型摘要选取 ──

    private val validSha = "6169f35d48aa1018e6effd16cb6ae07f307964cffe5d8c79b97faf2164693bec"

    @Test
    fun normalizeSha256_acceptsPlainAndPrefixedUppercase() {
        assertEquals(validSha, normalizeSha256(validSha))
        assertEquals(validSha, normalizeSha256("sha256:${validSha.uppercase()}"))
        assertEquals(validSha, normalizeSha256("  $validSha  "))
    }

    @Test
    fun normalizeSha256_rejectsGarbage() {
        assertEquals("", normalizeSha256(null))
        assertEquals("", normalizeSha256(""))
        assertEquals("", normalizeSha256("not-a-hash"))
        assertEquals("", normalizeSha256(validSha.drop(1)))
    }

    @Test
    fun pickExpectedSha256_githubRegistryOnly() {
        val other = "a".repeat(64)
        // 清单摘要永不作数：只认 GitHub 登记值（信任根收口）
        assertEquals(other, pickExpectedSha256(validSha, other))
        assertEquals("", pickExpectedSha256(validSha, ""))
        assertEquals(other, pickExpectedSha256("", other))
        assertEquals(other, pickExpectedSha256("garbage", "sha256:$other"))
        assertEquals("", pickExpectedSha256("", ""))
    }
}
