// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.backup.RestoreJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁盘回滚覆盖判定（D-S2）：快照里缺段、且 journal 显示该段已被
 * 失败还原写过的，列入 uncovered。纯函数，可主机单测。
 * Room 两库与 prefs 在快照里恒有，永不缺段——判定只看三个条件段。
 */
class BackupRollbackCoverageTest {

    private fun uncovered(
        completed: Set<String>,
        inputStats: Boolean = true,
        skinLibrary: Boolean = true,
        skinScripts: Boolean = true,
    ): List<String> = BackupManager.uncoveredSectionsForRollback(
        completedSections = completed,
        snapshotHasInputStats = inputStats,
        snapshotHasSkinLibrary = skinLibrary,
        snapshotHasSkinScripts = skinScripts,
    )

    @Test
    fun nothingCompleted_uncoveredEmpty() {
        assertTrue(uncovered(emptySet()).isEmpty())
        assertTrue(
            uncovered(emptySet(), inputStats = false, skinLibrary = false, skinScripts = false)
                .isEmpty(),
        )
    }

    @Test
    fun allCompletedAndAllInSnapshot_uncoveredEmpty() {
        val all = setOf(
            RestoreJournal.SECTION_CLIPBOARD,
            RestoreJournal.SECTION_APPDB,
            RestoreJournal.SECTION_PREFS,
            RestoreJournal.SECTION_INPUT_STATS,
            RestoreJournal.SECTION_SKIN_LIBRARY,
            RestoreJournal.SECTION_SKIN_SCRIPTS,
        )
        assertTrue(uncovered(all).isEmpty())
    }

    @Test
    fun completedSkinLibraryButSnapshotLacksIt_uncovered() {
        val result = uncovered(
            setOf(RestoreJournal.SECTION_SKIN_LIBRARY),
            skinLibrary = false,
        )
        assertEquals(listOf(RestoreJournal.SECTION_SKIN_LIBRARY), result)
    }

    @Test
    fun completedScriptsButSnapshotLacksThem_uncovered() {
        val result = uncovered(
            setOf(RestoreJournal.SECTION_SKIN_SCRIPTS),
            skinScripts = false,
        )
        assertEquals(listOf(RestoreJournal.SECTION_SKIN_SCRIPTS), result)
    }

    @Test
    fun completedInputStatsButSnapshotLacksIt_uncovered() {
        val result = uncovered(
            setOf(RestoreJournal.SECTION_INPUT_STATS),
            inputStats = false,
        )
        assertEquals(listOf(RestoreJournal.SECTION_INPUT_STATS), result)
    }

    @Test
    fun missingSnapshotSectionButNotCompleted_notUncovered() {
        // 快照里没有、失败还原也没写过：什么都没丢，不列入。
        val result = uncovered(
            setOf(RestoreJournal.SECTION_CLIPBOARD),
            inputStats = false,
            skinLibrary = false,
            skinScripts = false,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun multipleUncovered_allListed() {
        val result = uncovered(
            setOf(
                RestoreJournal.SECTION_INPUT_STATS,
                RestoreJournal.SECTION_SKIN_LIBRARY,
                RestoreJournal.SECTION_SKIN_SCRIPTS,
            ),
            inputStats = false,
            skinLibrary = false,
            skinScripts = false,
        )
        assertEquals(
            listOf(
                RestoreJournal.SECTION_INPUT_STATS,
                RestoreJournal.SECTION_SKIN_LIBRARY,
                RestoreJournal.SECTION_SKIN_SCRIPTS,
            ),
            result,
        )
    }
}
