// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupChangeGuard
import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 逐文件改动检测（D-M1）：读前取元数据、读后重验，变了即整个备份失败。
 * 纯 java.io/java.nio，不碰 Android，可主机单测。
 */
class BackupChangeGuardTest {

    private val guard = BackupChangeGuard()

    private fun tempDir(): File =
        Files.createTempDirectory("change-guard-test").toFile().also { it.deleteOnExit() }

    @Test
    fun unchangedFile_guardReturnsReadValue() {
        val file = File(tempDir(), "a.txt").apply { writeText("hello") }
        val result = guard.guard(file, "备份") { "read-value" }
        assertEquals("read-value", result)
    }

    @Test
    fun contentChangedDuringRead_throwsDataChanged() {
        val file = File(tempDir(), "a.txt").apply { writeText("hello") }
        val e = assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.guard(file, "备份") {
                file.appendText(" world")
                "read-value"
            }
        }
        assertEquals("备份中数据变了，请重试", e.message)
    }

    @Test
    fun mtimeOnlyChanged_throws() {
        val file = File(tempDir(), "a.txt").apply { writeText("hello") }
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.guard(file, "备份") {
                file.setLastModified(file.lastModified() + 60_000L)
                "read-value"
            }
        }
    }

    @Test
    fun fileDeletedDuringRead_throws() {
        val file = File(tempDir(), "a.txt").apply { writeText("hello") }
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.guard(file, "备份") {
                file.delete()
                "read-value"
            }
        }
    }

    @Test
    fun fileAbsentThroughout_guardReturnsReadValue() {
        val missing = File(tempDir(), "missing.txt")
        val result = guard.guard(missing, "备份") { "read-value" }
        assertEquals("read-value", result)
    }

    @Test
    fun fileAppearsDuringRead_throws() {
        val dir = tempDir()
        val file = File(dir, "appears.txt")
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.guard(file, "备份") {
                file.writeText("now I exist")
                "read-value"
            }
        }
    }

    @Test
    fun replacedWithSameContent_throwsOnInodeChange() {
        val dir = tempDir()
        val file = File(dir, "a.txt").apply { writeText("hello") }
        // inode 维只在文件系统给出 fileKey 时才参与比较；先确认本机给出。
        val mark = guard.mark(file)
        assumeTrue(mark?.fileKey != null)
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.guard(file, "备份") {
                // 删后同内容重建：长度与内容一致，mtime 未必变，inode 一定变。
                file.delete()
                File(dir, "a.txt").writeText("hello")
                "read-value"
            }
        }
    }

    @Test
    fun restoreOperation_messageUsesRestore() {
        val file = File(tempDir(), "a.txt").apply { writeText("hello") }
        val e = assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.guard(file, "还原") {
                file.appendText("!")
                "read-value"
            }
        }
        assertEquals("还原中数据变了，请重试", e.message)
    }

    @Test
    fun markAndUnchanged_direct() {
        val file = File(tempDir(), "a.txt").apply { writeText("hello") }
        val mark = guard.mark(file)
        assertTrue(mark != null)
        assertTrue(guard.unchanged(file, mark!!))
        file.appendText("x")
        assertTrue(!guard.unchanged(file, mark))
    }

    @Test
    fun markMissingFile_isNull() {
        assertTrue(guard.mark(File(tempDir(), "nope.txt")) == null)
    }
}
