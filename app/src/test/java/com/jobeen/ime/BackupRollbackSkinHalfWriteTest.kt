// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.backup.RestoreJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 磁盘回滚皮肤库半写段（D-P1）：杀在皮肤文件循环中途时，journal 里
 * 只有“已开始未完成”（inProgressSections），completedSections 里没有
 * 皮肤段；快照无皮肤段时回滚必须报 uncovered 而非成功。
 * 状态空间：快照有无皮肤段 × 皮肤段触碰态（未碰／已完成／进行中）
 * × 还原前有无皮肤文件。纯函数＋journal 文件操作，可主机单测。
 */
class BackupRollbackSkinHalfWriteTest {

    private fun uncovered(
        completed: Set<String> = emptySet(),
        inProgress: Set<String> = emptySet(),
        skinLibrary: Boolean = true,
        skinsPresentBefore: Boolean = true,
    ): List<String> = BackupManager.uncoveredSectionsForRollback(
        completedSections = completed,
        snapshotHasInputStats = true,
        snapshotHasSkinLibrary = skinLibrary,
        snapshotHasSkinScripts = true,
        inProgressSections = inProgress,
        skinsPresentBefore = skinsPresentBefore,
    )

    private val skin = RestoreJournal.SECTION_SKIN_LIBRARY

    // ---- 核心回归：进行中＋快照无段＋还原前有皮肤 → uncovered ----

    @Test
    fun inProgressSkin_noSnapshotSection_skinsPresentBefore_uncovered() {
        assertEquals(
            listOf(skin),
            uncovered(inProgress = setOf(skin), skinLibrary = false, skinsPresentBefore = true),
        )
    }

    // ---- 还原前本就无皮肤：孤儿文件由 clearSkinsForRollback 清掉，全覆盖 ----

    @Test
    fun inProgressSkin_noSnapshotSection_noSkinsBefore_covered() {
        assertTrue(
            uncovered(inProgress = setOf(skin), skinLibrary = false, skinsPresentBefore = false)
                .isEmpty(),
        )
    }

    @Test
    fun completedSkin_noSnapshotSection_noSkinsBefore_covered() {
        // 旧规则此处误报 uncovered（只看完成段、不看还原前有无皮肤）；
        // 新规则：还原前无皮肤时清零即恢复，不列入。
        assertTrue(
            uncovered(completed = setOf(skin), skinLibrary = false, skinsPresentBefore = false)
                .isEmpty(),
        )
    }

    // ---- 完成段＋快照无段＋还原前有皮肤 → uncovered（旧行为保持） ----

    @Test
    fun completedSkin_noSnapshotSection_skinsPresentBefore_uncovered() {
        assertEquals(
            listOf(skin),
            uncovered(completed = setOf(skin), skinLibrary = false, skinsPresentBefore = true),
        )
    }

    // ---- 快照有皮肤段：回放整表替换写回，全覆盖，与触碰态无关 ----

    @Test
    fun skinInSnapshot_touchedEitherWay_covered() {
        assertTrue(
            uncovered(completed = setOf(skin), skinLibrary = true).isEmpty(),
        )
        assertTrue(
            uncovered(inProgress = setOf(skin), skinLibrary = true).isEmpty(),
        )
    }

    // ---- 未碰过皮肤段：现状即还原前状态，全覆盖，与还原前有无皮肤无关 ----

    @Test
    fun skinUntouched_noSnapshotSection_covered() {
        assertTrue(
            uncovered(skinLibrary = false, skinsPresentBefore = true).isEmpty(),
        )
        assertTrue(
            uncovered(skinLibrary = false, skinsPresentBefore = false).isEmpty(),
        )
    }

    // ---- 其余条件段：进行中标记统一按写过计（保守） ----

    @Test
    fun inProgressStatsOrScripts_noSnapshotSection_uncovered() {
        val stats = BackupManager.uncoveredSectionsForRollback(
            completedSections = emptySet(),
            snapshotHasInputStats = false,
            snapshotHasSkinLibrary = true,
            snapshotHasSkinScripts = true,
            inProgressSections = setOf(RestoreJournal.SECTION_INPUT_STATS),
        )
        assertEquals(listOf(RestoreJournal.SECTION_INPUT_STATS), stats)
        val scripts = BackupManager.uncoveredSectionsForRollback(
            completedSections = emptySet(),
            snapshotHasInputStats = true,
            snapshotHasSkinLibrary = true,
            snapshotHasSkinScripts = false,
            inProgressSections = setOf(RestoreJournal.SECTION_SKIN_SCRIPTS),
        )
        assertEquals(listOf(RestoreJournal.SECTION_SKIN_SCRIPTS), scripts)
    }

    // ---- journal 新字段：写读往返与旧 journal 缺省 ----

    private fun tempDir(): File =
        RestoreJournal.dir(
            Files.createTempDirectory("rollback-skin-test").toFile().also { it.deleteOnExit() },
        )

    @Test
    fun journal_newFields_roundTrip() {
        val dir = tempDir()
        val written = RestoreJournal.Entry(
            token = "t1",
            phase = RestoreJournal.Phase.REPLACING,
            startedAt = 1_700_000_000_000L,
            backupFingerprint = "fp",
            snapshotFile = "restore-snapshot-t1.json",
            completedSections = setOf(RestoreJournal.SECTION_PREFS),
            inProgressSections = setOf(RestoreJournal.SECTION_SKIN_LIBRARY),
            skinsPresentBefore = false,
        )
        RestoreJournal.write(dir, written)
        assertEquals(written, RestoreJournal.read(dir))
    }

    @Test
    fun journal_oldFormatWithoutNewLines_defaultsConservative() {
        val dir = tempDir()
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText("token=t9\nphase=REPLACING\n")
        }
        val read = RestoreJournal.read(dir)!!
        assertTrue(read.inProgressSections.isEmpty())
        // 旧 journal 无此行时按 true 保守取：磁盘回滚不清、不谎报。
        assertTrue(read.skinsPresentBefore)
    }

    @Test
    fun journal_garbageSkinsPresentBefore_readsTrue() {
        val dir = tempDir()
        RestoreJournal.journalFile(dir).apply {
            parentFile?.mkdirs()
            writeText("token=t9\nphase=REPLACING\nskinsPresentBefore=yes\n")
        }
        assertTrue(RestoreJournal.read(dir)!!.skinsPresentBefore)
    }
}
