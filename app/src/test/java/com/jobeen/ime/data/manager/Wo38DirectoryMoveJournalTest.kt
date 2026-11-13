// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多目录改名／切换提交凭证（WO38 E1-5）：
 * 凭证前崩溃回旧态、凭证后崩溃向前完成、重复重放幂等、无凭证正常走。
 * 崩溃点用“只做到一半就停”模拟：写凭证后手动改目录／指针状态，
 * 再调 recover（生产上由启动首访 ensureMigrated 触发）。
 */
class Wo38DirectoryMoveJournalTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("wo38-dir-move").toFile().also { it.deleteOnExit() }

    private fun mkdir(root: File, name: String): File =
        File(root, name).also { it.mkdirs() }

    private fun writeFile(dir: File, name: String, text: String): File =
        File(dir, name).also { it.writeText(text) }

    private fun journalFiles(root: File): List<File> =
        root.listFiles()
            ?.filter { it.name.startsWith(".dir-move-") && it.name.endsWith(".journal") }
            .orEmpty()

    // ---- RENAME：崩溃点覆盖 ----

    @Test
    fun rename_crashBeforeMove_rollsBack() {
        val root = tempRoot()
        val dirA = mkdir(root, "alpha")
        writeFile(dirA, "a.schema.yaml", "a")
        val opId = DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.RENAME, "alpha", "beta",
        )
        // 崩溃点：凭证已写（INTENT），改名未发生。
        val recovered = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.ROLLED_BACK, recovered[0].outcome)
        assertEquals(opId, recovered[0].operationId)
        assertTrue(File(dirA, "a.schema.yaml").isFile)
        assertFalse(File(root, "beta").exists())
        assertTrue(journalFiles(root).isEmpty())
    }

    @Test
    fun rename_crashAfterMove_completesForward() {
        val root = tempRoot()
        val dirA = mkdir(root, "alpha")
        writeFile(dirA, "a.schema.yaml", "a")
        val opId = DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.RENAME, "alpha", "beta",
        )
        assertTrue(dirA.renameTo(File(root, "beta")))
        assertTrue(DirectoryMoveJournal.markMoved(root, opId))
        // 崩溃点：改名已发生、MOVED 已写，提交清凭证未发生。
        val recovered = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.COMPLETED_FORWARD, recovered[0].outcome)
        assertTrue(File(root, "beta/a.schema.yaml").isFile)
        assertFalse(File(root, "alpha").exists())
        assertTrue(journalFiles(root).isEmpty())
    }

    @Test
    fun rename_crashBetweenMoveAndMarkMoved_completesForward() {
        val root = tempRoot()
        val dirA = mkdir(root, "alpha")
        writeFile(dirA, "a.schema.yaml", "a")
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.RENAME, "alpha", "beta",
        )
        assertTrue(dirA.renameTo(File(root, "beta")))
        // 崩溃点：改名已发生、MOVED 没写上（凭证仍是 INTENT）。
        // 恢复不依赖阶段，只看目录现状：B 在 A 不在即向前完成。
        val recovered = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.COMPLETED_FORWARD, recovered[0].outcome)
        assertTrue(File(root, "beta/a.schema.yaml").isFile)
        assertTrue(journalFiles(root).isEmpty())
    }

    @Test
    fun rename_bothDirsExist_keepsBoth() {
        val root = tempRoot()
        mkdir(root, "alpha")
        mkdir(root, "beta")
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.RENAME, "alpha", "beta",
        )
        // 两边都在：改名不可能已跑（renameTo 落不到已存在的目标上），
        // 两边都保留、只清凭证，绝不删目录。
        val recovered = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertEquals(1, recovered.size)
        assertEquals(
            DirectoryMoveJournal.RecoveredOutcome.AMBIGUOUS_KEPT_BOTH,
            recovered[0].outcome,
        )
        assertTrue(File(root, "alpha").isDirectory)
        assertTrue(File(root, "beta").isDirectory)
        assertTrue(journalFiles(root).isEmpty())
    }

    @Test
    fun rename_neitherDirExists_staleCleared() {
        val root = tempRoot()
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.RENAME, "alpha", "beta",
        )
        val recovered = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.STALE_CLEARED, recovered[0].outcome)
        assertTrue(journalFiles(root).isEmpty())
    }

    // ---- SWITCH：崩溃点覆盖 ----

    @Test
    fun switch_crashBeforePointerFlip_rollsBack() {
        val root = tempRoot()
        mkdir(root, "alpha")
        mkdir(root, "beta")
        var current = "alpha"
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.SWITCH, "alpha", "beta",
        )
        // 崩溃点：凭证已写，指针未翻。
        val recovered = DirectoryMoveJournal.recover(root, { current }, { current = it })
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.ROLLED_BACK, recovered[0].outcome)
        assertEquals("alpha", current)
        assertTrue(journalFiles(root).isEmpty())
    }

    @Test
    fun switch_crashAfterPointerFlip_completesForward() {
        val root = tempRoot()
        mkdir(root, "alpha")
        mkdir(root, "beta")
        var current = "beta"
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.SWITCH, "alpha", "beta",
        )
        // 崩溃点：指针已翻到目标、目标目录在，向前完成（部署由指纹核对）。
        val recovered = DirectoryMoveJournal.recover(root, { current }, { current = it })
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.COMPLETED_FORWARD, recovered[0].outcome)
        assertEquals("beta", current)
        assertTrue(journalFiles(root).isEmpty())
    }

    @Test
    fun switch_crashAfterFlip_targetDirGone_rollsPointerBack() {
        val root = tempRoot()
        mkdir(root, "alpha")
        var current = "beta"
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.SWITCH, "alpha", "beta",
        )
        // 崩溃点：指针已翻，但目标目录已失（中途被删）——指针拨回旧目录。
        val recovered = DirectoryMoveJournal.recover(root, { current }, { current = it })
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.ROLLED_BACK, recovered[0].outcome)
        assertEquals("alpha", current)
        assertTrue(journalFiles(root).isEmpty())
    }

    @Test
    fun switch_supersededJournal_staleCleared() {
        val root = tempRoot()
        mkdir(root, "alpha")
        mkdir(root, "beta")
        mkdir(root, "gamma")
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.SWITCH, "alpha", "beta",
        )
        // 指针已是第三个目录：该凭证是过期凭证，只清掉、不动指针。
        val recovered = DirectoryMoveJournal.recover(root, { "gamma" }, {})
        assertEquals(1, recovered.size)
        assertEquals(DirectoryMoveJournal.RecoveredOutcome.STALE_CLEARED, recovered[0].outcome)
        assertTrue(journalFiles(root).isEmpty())
    }

    // ---- 幂等与无凭证 ----

    @Test
    fun recover_replayIsIdempotent() {
        val root = tempRoot()
        val dirA = mkdir(root, "alpha")
        writeFile(dirA, "a.schema.yaml", "a")
        DirectoryMoveJournal.begin(
            root, DirectoryMoveJournal.MoveKind.RENAME, "alpha", "beta",
        )
        assertTrue(dirA.renameTo(File(root, "beta")))
        val first = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertEquals(1, first.size)
        // 第二次重放：已无凭证，空表、状态不动。
        val second = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertTrue(second.isEmpty())
        assertTrue(File(root, "beta/a.schema.yaml").isFile)
        assertFalse(File(root, "alpha").exists())
    }

    @Test
    fun recover_noJournal_isNoop() {
        val root = tempRoot()
        mkdir(root, "alpha")
        mkdir(root, "beta")
        var current = "beta"
        val recovered = DirectoryMoveJournal.recover(root, { current }, { current = it })
        assertTrue(recovered.isEmpty())
        assertEquals("beta", current)
        assertTrue(File(root, "alpha").isDirectory)
        assertTrue(File(root, "beta").isDirectory)
    }

    @Test
    fun recover_unparseableJournal_keptAndSkipped() {
        val root = tempRoot()
        val bad = File(root, ".dir-move-bad.journal")
        bad.writeText("this is not a journal\nno keys here\n")
        val recovered = DirectoryMoveJournal.recover(root, { "alpha" }, {})
        assertTrue(recovered.isEmpty())
        // 坏凭证保留（诊断线索），不阻塞其他恢复。
        assertTrue(bad.isFile)
    }

    // ---- 改名执行器端到端 ----

    @Test
    fun renameDirectory_success_endToEnd() {
        val root = tempRoot()
        val dirA = mkdir(root, "alpha")
        writeFile(dirA, "a.schema.yaml", "a")
        val result = SchemeDirs.renameDirectory(root, "alpha", "beta")
        assertEquals(SchemeDirs.RenameDirectoryResult.Success, result)
        assertTrue(File(root, "beta/a.schema.yaml").isFile)
        assertFalse(File(root, "alpha").exists())
        assertTrue(journalFiles(root).isEmpty())
        assertTrue(DirectoryMoveJournal.pending(root).isEmpty())
    }

    @Test
    fun renameDirectory_refusals_leaveNoJournal() {
        val root = tempRoot()
        mkdir(root, "alpha")
        mkdir(root, "beta")
        assertEquals(
            SchemeDirs.RenameDirectoryResult.AlreadyExists,
            SchemeDirs.renameDirectory(root, "alpha", "beta"),
        )
        assertEquals(
            SchemeDirs.RenameDirectoryResult.NotFound,
            SchemeDirs.renameDirectory(root, "missing", "gamma"),
        )
        assertEquals(
            SchemeDirs.RenameDirectoryResult.InvalidName,
            SchemeDirs.renameDirectory(root, "alpha", "bad/name"),
        )
        assertEquals(
            SchemeDirs.RenameDirectoryResult.InvalidName,
            SchemeDirs.renameDirectory(root, "alpha", "alpha"),
        )
        assertTrue(journalFiles(root).isEmpty())
        assertTrue(File(root, "alpha").isDirectory)
        assertTrue(File(root, "beta").isDirectory)
    }

    @Test
    fun begin_invalidName_throws() {
        val root = tempRoot()
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryMoveJournal.begin(
                root, DirectoryMoveJournal.MoveKind.RENAME, "bad/name", "beta",
            )
        }
        assertTrue(journalFiles(root).isEmpty())
    }

    // ---- 切换凭证三钩 ----

    @Test
    fun switchHooks_beginMarkFinish_roundTrip() {
        val root = tempRoot()
        mkdir(root, "alpha")
        mkdir(root, "beta")
        val opId = SchemeDirs.beginDirSwitch(root, "alpha", "beta")
        assertEquals(1, DirectoryMoveJournal.pending(root).size)
        assertEquals(DirectoryMoveJournal.MoveStage.INTENT, DirectoryMoveJournal.pending(root)[0].stage)
        assertTrue(SchemeDirs.markDirSwitchMoved(root, opId))
        assertEquals(DirectoryMoveJournal.MoveStage.MOVED, DirectoryMoveJournal.pending(root)[0].stage)
        assertTrue(SchemeDirs.finishDirSwitch(root, opId))
        assertTrue(DirectoryMoveJournal.pending(root).isEmpty())
        assertTrue(journalFiles(root).isEmpty())
    }
}
