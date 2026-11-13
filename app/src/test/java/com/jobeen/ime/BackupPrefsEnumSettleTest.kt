// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * prefs 枚举缺口（D-L2）：采集中途新建的 prefs 文件不在守卫跟踪表里，
 * 结算重验抓不到。结算点重枚举一次 shared_prefs 目录，与采集开始时
 * 的文件集比对——多出文件即整包失败（与“枚举到但读前消失”的口径
 * 对称；不纳入本次备份：新文件内容未经守卫在一致点上验过）。
 * 纯文件操作，可主机单测；collectPayload 内的调用点接线需全量单测
 * 覆盖（本批不跑 Gradle，见汇报）。
 */
class BackupPrefsEnumSettleTest {

    private fun tempDir(): File =
        Files.createTempDirectory("prefs-enum-settle-test").toFile().also { it.deleteOnExit() }

    private fun prefsDir(): File {
        val root = tempDir()
        return File(root, "shared_prefs").also { it.mkdirs() }
    }

    @Test
    fun noNewFile_empty() {
        val dir = prefsDir()
        File(dir, "a.xml").writeText("<map/>")
        File(dir, "b.xml").writeText("<map/>")
        assertTrue(
            BackupManager.newPrefsFilesSince(setOf("a", "b"), dir).isEmpty(),
        )
    }

    @Test
    fun newFileDetected() {
        val dir = prefsDir()
        File(dir, "a.xml").writeText("<map/>")
        File(dir, "new_table.xml").writeText("<map/>")
        assertEquals(
            setOf("new_table"),
            BackupManager.newPrefsFilesSince(setOf("a"), dir),
        )
    }

    @Test
    fun newNonXmlFileIgnored() {
        val dir = prefsDir()
        File(dir, "a.xml").writeText("<map/>")
        File(dir, "a.xml.bak").writeText("bak")
        File(dir, "notes.txt").writeText("txt")
        assertTrue(
            BackupManager.newPrefsFilesSince(setOf("a"), dir).isEmpty(),
        )
    }

    @Test
    fun deletedFileNotReported() {
        // 删文件不走这里：被删的已跟踪文件由 reverifyAll 抓住。
        val dir = prefsDir()
        File(dir, "a.xml").writeText("<map/>")
        assertTrue(
            BackupManager.newPrefsFilesSince(setOf("a", "gone"), dir).isEmpty(),
        )
    }

    @Test
    fun newAndDeletedTogether_onlyNewReported() {
        val dir = prefsDir()
        File(dir, "a.xml").writeText("<map/>")
        File(dir, "fresh.xml").writeText("<map/>")
        assertEquals(
            setOf("fresh"),
            BackupManager.newPrefsFilesSince(setOf("a", "gone"), dir),
        )
    }

    @Test
    fun emptyDir_emptyInitial_empty() {
        val dir = prefsDir()
        assertTrue(BackupManager.newPrefsFilesSince(emptySet(), dir).isEmpty())
        File(dir, "first.xml").writeText("<map/>")
        assertEquals(setOf("first"), BackupManager.newPrefsFilesSince(emptySet(), dir))
    }
}
