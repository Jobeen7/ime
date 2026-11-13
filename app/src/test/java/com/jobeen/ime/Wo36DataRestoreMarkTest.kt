// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.backup.RestoreJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * D-P1 皮肤段开始标记（WO36）：标记同步落盘后才进皮肤段，写失败
 * 上报、不静默吞。
 *
 * 状态空间：
 * - journal 完好 → 标记并入现有行（完成段与进行段都保留）；
 * - journal 缺失／损坏 → 用本次 REPLACING fallback 重建整行；
 * - 标记写失败（目录不可写）→ 抛错（调用方按还原失败处理）；
 * - 返回后标记已在磁盘文件里（同步落盘，非仅内存）。
 *
 * 覆盖不到（真机验收）：applyPayload 里“标记先于第一块皮肤
 * 文件写盘”的顺序本身——JVM 单测跑不通 applyPayload，需真机
 * 验一次中断还原（杀在皮肤段中途，磁盘回滚报 uncovered）。
 */
class Wo36DataRestoreMarkTest {

    private fun tempDir(): File =
        Files.createTempDirectory("wo36-restore-mark-test").toFile()
            .also { it.deleteOnExit() }

    private fun fallback(phase: RestoreJournal.Phase = RestoreJournal.Phase.REPLACING) =
        RestoreJournal.Entry(
            token = "t1",
            phase = phase,
            startedAt = 1_700_000_000_000L,
            backupFingerprint = "fp",
            snapshotFile = "restore-snapshot-t1.json",
            completedSections = emptySet(),
        )

    @Test
    fun markSectionStarted_mergesIntoExistingEntry() {
        val dir = tempDir()
        RestoreJournal.write(
            dir,
            fallback().copy(
                completedSections = setOf(RestoreJournal.SECTION_CLIPBOARD),
                inProgressSections = setOf(RestoreJournal.SECTION_APPDB),
            ),
        )
        BackupManager.markSectionStarted(dir, RestoreJournal.SECTION_SKIN_LIBRARY, fallback())

        val read = RestoreJournal.read(dir)!!
        assertEquals(setOf(RestoreJournal.SECTION_CLIPBOARD), read.completedSections)
        assertEquals(
            setOf(RestoreJournal.SECTION_APPDB, RestoreJournal.SECTION_SKIN_LIBRARY),
            read.inProgressSections,
        )
        assertEquals(RestoreJournal.Phase.REPLACING, read.phase)
        assertEquals("t1", read.token)
    }

    @Test
    fun markSectionStarted_missingJournalRebuildsFromFallback() {
        val dir = tempDir()
        BackupManager.markSectionStarted(dir, RestoreJournal.SECTION_SKIN_LIBRARY, fallback())

        val read = RestoreJournal.read(dir)!!
        assertEquals(setOf(RestoreJournal.SECTION_SKIN_LIBRARY), read.inProgressSections)
        assertEquals(RestoreJournal.Phase.REPLACING, read.phase)
        assertEquals("t1", read.token)
        assertEquals("restore-snapshot-t1.json", read.snapshotFile)
    }

    @Test
    fun markSectionStarted_corruptJournalRebuildsFromFallback() {
        val dir = tempDir()
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText("this is not a journal\nphase=???\n")
        }
        BackupManager.markSectionStarted(dir, RestoreJournal.SECTION_SKIN_LIBRARY, fallback())

        val read = RestoreJournal.read(dir)!!
        assertEquals(setOf(RestoreJournal.SECTION_SKIN_LIBRARY), read.inProgressSections)
        assertEquals(RestoreJournal.Phase.REPLACING, read.phase)
    }

    @Test
    fun markSectionStarted_markIsOnDiskWhenItReturns() {
        val dir = tempDir()
        BackupManager.markSectionStarted(dir, RestoreJournal.SECTION_SKIN_LIBRARY, fallback())

        // 直接读磁盘文件原文（不经 RestoreJournal.read）：返回即落盘。
        val raw = RestoreJournal.journalFile(dir).readText(Charsets.UTF_8)
        assertTrue(raw.contains("inProgressSections=${RestoreJournal.SECTION_SKIN_LIBRARY}"))
        assertTrue(raw.contains("phase=REPLACING"))
    }

    @Test
    fun markSectionStarted_unwritableDirThrows() {
        // journalDir 本身是个普通文件：AtomicFiles 写 tmp 即失败。
        val fileAsDir = Files.createTempFile("wo36-restore-mark-test", ".file").toFile()
            .also { it.deleteOnExit() }
        var thrown: Throwable? = null
        try {
            BackupManager.markSectionStarted(fileAsDir, RestoreJournal.SECTION_SKIN_LIBRARY, fallback())
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue("标记写失败必须抛错、不静默吞", thrown is IOException)
        // 失败后磁盘上无半截 journal：目标文件根本不存在。
        assertFalse(RestoreJournal.journalFile(fileAsDir).exists())
    }
}
