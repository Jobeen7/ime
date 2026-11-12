// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.RestoreJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 还原 journal（D-S2）：原子写盘、三阶段、启动对账决策。
 * 纯 java.io 文件操作＋AtomicFiles（tmp＋rename），可主机单测。
 */
class RestoreJournalTest {

    /** journal 目录（filesDir 下的 restore_journal 子目录）。 */
    private fun tempDir(): File =
        RestoreJournal.dir(
            Files.createTempDirectory("restore-journal-test").toFile().also { it.deleteOnExit() },
        )

    private fun entry(
        phase: RestoreJournal.Phase = RestoreJournal.Phase.STAGING,
        token: String = "token-1",
        sections: Set<String> = emptySet(),
    ) = RestoreJournal.Entry(
        token = token,
        phase = phase,
        startedAt = 1_700_000_000_000L,
        backupFingerprint = "ab".repeat(32),
        snapshotFile = RestoreJournal.SNAPSHOT_FILE_PREFIX + token + RestoreJournal.SNAPSHOT_FILE_SUFFIX,
        completedSections = sections,
    )

    @Test
    fun writeReadRoundTrip() {
        val dir = tempDir()
        val written = entry(
            phase = RestoreJournal.Phase.REPLACING,
            sections = setOf(RestoreJournal.SECTION_CLIPBOARD, RestoreJournal.SECTION_PREFS),
        )
        RestoreJournal.write(dir, written)
        val read = RestoreJournal.read(dir)
        assertEquals(written, read)
    }

    @Test
    fun writeLeavesNoTempFiles() {
        val dir = tempDir()
        RestoreJournal.write(dir, entry())
        val names = dir.listFiles().orEmpty().map { it.name }
        assertEquals(listOf(RestoreJournal.JOURNAL_FILE_NAME), names)
    }

    @Test
    fun readMissing_returnsNull() {
        assertNull(RestoreJournal.read(tempDir()))
    }

    @Test
    fun readCorrupted_returnsNull() {
        val dir = tempDir()
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText("this is not a journal\nno equals here\n")
        }
        assertNull(RestoreJournal.read(dir))
    }

    @Test
    fun readBadPhase_returnsNull() {
        val dir = tempDir()
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText("token=t1\nphase=BOGUS\n")
        }
        assertNull(RestoreJournal.read(dir))
    }

    @Test
    fun readMissingToken_returnsNull() {
        val dir = tempDir()
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText("phase=STAGING\n")
        }
        assertNull(RestoreJournal.read(dir))
    }

    @Test
    fun readToleratesMissingOptionalFields() {
        val dir = tempDir()
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText("token=t9\nphase=REPLACING\n")
        }
        val read = RestoreJournal.read(dir)
        assertEquals("t9", read?.token)
        assertEquals(RestoreJournal.Phase.REPLACING, read?.phase)
        assertEquals(0L, read?.startedAt)
        assertEquals("", read?.snapshotFile)
        assertTrue(read?.completedSections.orEmpty().isEmpty())
    }

    @Test
    fun phaseTransitions_visibleOnRead() {
        val dir = tempDir()
        RestoreJournal.write(dir, entry(phase = RestoreJournal.Phase.STAGING))
        assertEquals(RestoreJournal.Phase.STAGING, RestoreJournal.read(dir)?.phase)
        RestoreJournal.write(
            dir,
            entry(
                phase = RestoreJournal.Phase.REPLACING,
                sections = setOf(RestoreJournal.SECTION_CLIPBOARD),
            ),
        )
        val read = RestoreJournal.read(dir)
        assertEquals(RestoreJournal.Phase.REPLACING, read?.phase)
        assertEquals(setOf(RestoreJournal.SECTION_CLIPBOARD), read?.completedSections)
        RestoreJournal.write(dir, entry(phase = RestoreJournal.Phase.COMMITTED))
        assertEquals(RestoreJournal.Phase.COMMITTED, RestoreJournal.read(dir)?.phase)
    }

    @Test
    fun decide_nullEntry_none() {
        assertEquals(RestoreJournal.RecoveryAction.NONE, RestoreJournal.decide(null, false))
        assertEquals(RestoreJournal.RecoveryAction.NONE, RestoreJournal.decide(null, true))
    }

    @Test
    fun decide_committed_cleanupCompleted() {
        val e = entry(phase = RestoreJournal.Phase.COMMITTED)
        assertEquals(
            RestoreJournal.RecoveryAction.CLEANUP_COMPLETED,
            RestoreJournal.decide(e, true),
        )
    }

    @Test
    fun decide_staging_cleanupStaging() {
        val e = entry(phase = RestoreJournal.Phase.STAGING)
        assertEquals(
            RestoreJournal.RecoveryAction.CLEANUP_STAGING,
            RestoreJournal.decide(e, true),
        )
    }

    @Test
    fun decide_replacing_promptUserWithOrWithoutSnapshot() {
        val e = entry(phase = RestoreJournal.Phase.REPLACING)
        assertEquals(RestoreJournal.RecoveryAction.PROMPT_USER, RestoreJournal.decide(e, true))
        assertEquals(RestoreJournal.RecoveryAction.PROMPT_USER, RestoreJournal.decide(e, false))
    }

    @Test
    fun clear_deletesJournalAndSnapshot() {
        val dir = tempDir()
        val e = entry()
        RestoreJournal.write(dir, e)
        RestoreJournal.snapshotFile(dir, e.token).apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        RestoreJournal.clear(dir, e)
        assertFalse(RestoreJournal.journalFile(dir).exists())
        assertFalse(RestoreJournal.snapshotFile(dir, e.token).exists())
    }

    @Test
    fun sweepOrphanSnapshots_keepsReferencedDeletesOthers() {
        val dir = tempDir()
        val keep = RestoreJournal.snapshotFile(dir, "keep-me").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        val orphan = RestoreJournal.snapshotFile(dir, "orphan").apply {
            writeText("{}")
        }
        RestoreJournal.sweepOrphanSnapshots(dir, "keep-me")
        assertTrue(keep.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun sweepOrphanSnapshots_nullKeep_deletesAll() {
        val dir = tempDir()
        val one = RestoreJournal.snapshotFile(dir, "one").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        RestoreJournal.sweepOrphanSnapshots(dir, null)
        assertFalse(one.exists())
    }

    @Test
    fun snapshotExists_reflectsFile() {
        val dir = tempDir()
        val e = entry()
        assertFalse(RestoreJournal.snapshotExists(dir, e))
        RestoreJournal.snapshotFile(dir, e.token).apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        assertTrue(RestoreJournal.snapshotExists(dir, e))
    }
}
