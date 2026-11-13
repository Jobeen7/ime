// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.RestoreJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * journal 本身损坏（D-L1）：损坏不得按“无 journal”处理——NONE 分支的
 * 孤儿快照清扫会删掉损坏 journal 对应的还原前快照（唯一副本），回滚
 * 能力永久丢失。损坏时按“疑似中断还原”处理：快照全部保留、走横幅
 * （回滚不可用，只能重导／忽略）。纯决策＋文件操作，可主机单测；
 * reconcile 的横幅接线与 BackupScreen 展示需真机验。
 */
class RestoreJournalCorruptTest {

    private fun tempDir(): File =
        RestoreJournal.dir(
            Files.createTempDirectory("restore-journal-corrupt-test").toFile()
                .also { it.deleteOnExit() },
        )

    private fun writeJournalText(dir: File, text: String) {
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }

    private fun entry(phase: RestoreJournal.Phase) = RestoreJournal.Entry(
        token = "t1",
        phase = phase,
        startedAt = 1_700_000_000_000L,
        backupFingerprint = "fp",
        snapshotFile = "restore-snapshot-t1.json",
        completedSections = emptySet(),
    )

    // ---- decide：损坏标志只在 entry 为 null 时起作用 ----

    @Test
    fun decide_corruptWithoutEntry_promptCorrupt() {
        assertEquals(
            RestoreJournal.RecoveryAction.PROMPT_CORRUPT,
            RestoreJournal.decide(null, false, journalCorrupt = true),
        )
        // 目录里纵有快照文件也无法归属到损坏的 journal，同样提示、不清扫。
        assertEquals(
            RestoreJournal.RecoveryAction.PROMPT_CORRUPT,
            RestoreJournal.decide(null, true, journalCorrupt = true),
        )
    }

    @Test
    fun decide_noEntryNoCorrupt_none() {
        assertEquals(
            RestoreJournal.RecoveryAction.NONE,
            RestoreJournal.decide(null, false, journalCorrupt = false),
        )
        assertEquals(
            RestoreJournal.RecoveryAction.NONE,
            RestoreJournal.decide(null, true),
        )
    }

    @Test
    fun decide_entryPresent_corruptFlagIgnored() {
        // entry 能解析出来即 journal 未损坏；损坏标志不可能与有效 entry 并存。
        assertEquals(
            RestoreJournal.RecoveryAction.PROMPT_USER,
            RestoreJournal.decide(entry(RestoreJournal.Phase.REPLACING), false, journalCorrupt = true),
        )
        assertEquals(
            RestoreJournal.RecoveryAction.CLEANUP_COMPLETED,
            RestoreJournal.decide(entry(RestoreJournal.Phase.COMMITTED), true, journalCorrupt = true),
        )
        assertEquals(
            RestoreJournal.RecoveryAction.CLEANUP_STAGING,
            RestoreJournal.decide(entry(RestoreJournal.Phase.STAGING), true, journalCorrupt = true),
        )
    }

    // ---- isCorrupt：缺文件／可解析 → false；文件在但解析失败 → true ----

    @Test
    fun isCorrupt_missingFile_false() {
        assertFalse(RestoreJournal.isCorrupt(tempDir()))
    }

    @Test
    fun isCorrupt_validJournal_false() {
        val dir = tempDir()
        RestoreJournal.write(dir, entry(RestoreJournal.Phase.REPLACING))
        assertFalse(RestoreJournal.isCorrupt(dir))
    }

    @Test
    fun isCorrupt_garbage_true() {
        val dir = tempDir()
        writeJournalText(dir, "this is not a journal\nno equals here\n")
        assertTrue(RestoreJournal.isCorrupt(dir))
    }

    @Test
    fun isCorrupt_missingToken_true() {
        val dir = tempDir()
        writeJournalText(dir, "phase=STAGING\n")
        assertTrue(RestoreJournal.isCorrupt(dir))
    }

    @Test
    fun isCorrupt_badPhase_true() {
        val dir = tempDir()
        writeJournalText(dir, "token=t1\nphase=BOGUS\n")
        assertTrue(RestoreJournal.isCorrupt(dir))
    }
}
