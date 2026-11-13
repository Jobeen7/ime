// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.ContextWrapper
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinApplyPort
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.ApplyTransaction
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-M2：保存失败且事务回滚再失败（RollbackFailed）后，live 运行态与
 * active 记录须收敛。状态空间按（路径 × 事务结果 × 重装结果）分区：
 * skin 路径只在 RollbackFailed 时恢复（写回旧记录＋重装），其余结果
 * 不另行恢复；layout 路径的重装／记录修复结果进 restored 汇总。
 * 假应用事务在 applySkin 里模拟失败事务的残留：把 active 写成
 * PENDING 新代（生产里事务写下 PENDING 后回滚失败即留此态）。
 */
class SkinEditorSaverRollbackFailedTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private class FakeContext : ContextWrapper(null)

    private class FakeApplier(
        var result: ApplyTransaction.Result,
        var reinstallResult: Boolean = true,
        private val store: SkinLibraryStore? = null,
        private val leavePendingOnApply: Boolean = false,
    ) : SkinApplyPort {
        var applyCalls = 0
        var reinstallCalls = 0

        override suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result {
            applyCalls++
            if (leavePendingOnApply) {
                store?.writeActive(
                    ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.PENDING, 1L),
                )
            }
            return result
        }

        override suspend fun reinstallActiveSnapshot(): Boolean {
            reinstallCalls++
            return reinstallResult
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun skinManifest(skinSha: String): PackageManifest = PackageManifest(
        formatVersion = SkinValidator.SUPPORTED_FORMAT_VERSION,
        contents = listOf(
            PackageManifest.Content("skin", "skin", "skin.json", skinSha, 10L),
        ),
    )

    private fun newStore(): Triple<File, SkinLibraryLayout, SkinLibraryStore> {
        val root = Files.createTempDirectory("skin-editor-rollback-failed").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        return Triple(root, layout, SkinLibraryStore(layout))
    }

    private val layoutTextV1: String =
        """{"formatVersion":3,"meta":{"id":"lay1","name":"测试布局"},"rows":[{"keys":[{"stableId":"1","label":"q","tap":{"type":"text","value":"q"}}]}]}"""

    private val layoutTextV2: String =
        """{"formatVersion":3,"meta":{"id":"lay1","name":"测试布局"},"rows":[{"keys":[{"stableId":"1","label":"w","tap":{"type":"text","value":"w"}}]}]}"""

    private fun skinText(keyBackground: String = "#FFFFFFFF"): String =
        """{"formatVersion":3,"styles":{"keyBackground":"$keyBackground"},"stylesDark":{"keyBackground":"#FF222222"},"layoutId":"lay1"}"""

    private suspend fun seedSkin(layout: SkinLibraryLayout, store: SkinLibraryStore): String {
        val text = skinText()
        val genDir = layout.skinGenDir("s1", 1)
        genDir.mkdirs()
        File(genDir, "skin.json").writeText(text)
        val hash = sha256Hex(text.toByteArray(Charsets.UTF_8))
        File(genDir, "manifest.json").writeText(PackageManifest.toJson(skinManifest(hash)))
        store.writeActive(ActiveRecord("s1", hash, 1))
        val layoutHash = sha256Hex(layoutTextV1.toByteArray(Charsets.UTF_8))
        val layoutDir = layout.layoutGenDir("lay1", 1)
        layoutDir.mkdirs()
        File(layoutDir, "layout.json").writeText(layoutTextV1)
        store.writeIndex(
            LibraryIndex(
                skins = listOf(
                    SkinIndexEntry(
                        "s1", "测试皮肤", hash, 1,
                        layout.skinGenRelative("s1", 1), 100L, 1L,
                    ),
                ),
                layouts = listOf(
                    LayoutIndexEntry(
                        "lay1", layoutHash, 1,
                        layout.layoutGenRelative("lay1", 1), layoutTextV1.length.toLong(), 1L,
                    ),
                ),
            ),
        )
        return hash
    }

    private fun saver(
        layout: SkinLibraryLayout,
        store: SkinLibraryStore,
        applier: FakeApplier,
    ): SkinEditorSaver = SkinEditorSaver(
        context = FakeContext(),
        store = store,
        layout = layout,
        applier = applier,
        currentVersionCode = 0,
        fallbacks = fallbacks,
        mainDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun resolveOk(saver: SkinEditorSaver): ActiveSkinFiles {
        val resolved = saver.resolveActiveFiles()
        assertTrue("expected Ok, got $resolved", resolved is ResolveActiveResult.Ok)
        return (resolved as ResolveActiveResult.Ok).files
    }

    private suspend fun readActive(store: SkinLibraryStore): ActiveRecord {
        val read = store.readActive()
        assertTrue(read is SkinLibraryStore.ReadResult.Ok)
        return (read as SkinLibraryStore.ReadResult.Ok).value
    }

    private suspend fun readIndex(store: SkinLibraryStore): LibraryIndex {
        val read = store.readIndex()
        assertTrue(read is SkinLibraryStore.ReadResult.Ok)
        return (read as SkinLibraryStore.ReadResult.Ok).value
    }

    private fun rollbackFailed(): ApplyTransaction.Result.Failed =
        ApplyTransaction.Result.Failed(
            SkinGenRef("s1", "", 2),
            ApplyTransaction.Result.Stage.ROLLBACK,
            "restore_default_switch_failed",
        )

    // ---- skin 路径 ----

    @Test
    fun saveSkinJsonRollbackFailedRecoversActiveAndRuntime() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val oldHash = seedSkin(layout, store)
        val applier = FakeApplier(rollbackFailed(), store = store, leavePendingOnApply = true)
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val result = s.saveSkinJson(active, skinText(keyBackground = "#FF000000"))
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        assertTrue("reason=$reason", reason.startsWith("apply_failed:rollback_failed:"))
        assertTrue("reason=$reason", reason.contains("old_gen_intact"))
        assertTrue("reason=$reason", reason.contains(":recovery_ok"))
        // 恢复只跑一次：写回旧记录＋重装快照。
        assertEquals(1, applier.reinstallCalls)
        assertEquals(ActiveRecord("s1", oldHash, 1), readActive(store))
        assertFalse(layout.skinGenDir("s1", 2).exists())
        assertEquals(
            skinText(),
            File(layout.skinGenDir("s1", 1), "skin.json").readText(),
        )
        root.deleteRecursively()
    }

    @Test
    fun saveSkinJsonRollbackFailedWithReinstallFailureReportsRecoveryFailed() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val oldHash = seedSkin(layout, store)
        val applier = FakeApplier(
            rollbackFailed(),
            reinstallResult = false,
            store = store,
            leavePendingOnApply = true,
        )
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val result = s.saveSkinJson(active, skinText(keyBackground = "#FF000000"))
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        assertTrue("reason=$reason", reason.contains(":recovery_failed"))
        assertFalse("reason=$reason", reason.contains(":recovery_ok"))
        assertEquals(1, applier.reinstallCalls)
        // 记录修复本身成功（重装失败不倒扣记录修复）。
        assertEquals(ActiveRecord("s1", oldHash, 1), readActive(store))
        root.deleteRecursively()
    }

    @Test
    fun saveSkinJsonRolledBackDoesNotAttemptRecovery() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val oldHash = seedSkin(layout, store)
        val applier = FakeApplier(
            ApplyTransaction.Result.Failed(SkinGenRef("s1", "", 2), ApplyTransaction.Result.Stage.SWITCH, "switch_failed"),
            store = store,
        )
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val result = s.saveSkinJson(active, skinText(keyBackground = "#FF000000"))
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        assertTrue("reason=$reason", reason.contains("old_gen_intact"))
        assertFalse("reason=$reason", reason.contains(":recovery_"))
        assertEquals(0, applier.reinstallCalls)
        assertEquals(ActiveRecord("s1", oldHash, 1), readActive(store))
        root.deleteRecursively()
    }

    // ---- layout 路径 ----

    @Test
    fun saveLayoutRollbackFailedRestoresOkWhenReinstallSucceeds() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val oldHash = seedSkin(layout, store)
        val layoutHash = sha256Hex(layoutTextV1.toByteArray(Charsets.UTF_8))
        val applier = FakeApplier(rollbackFailed(), store = store, leavePendingOnApply = true)
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val layoutFile = active.files.first { it.kind == EditorFile.Kind.LAYOUT }
        val result = s.saveLayout(active, layoutFile, layoutTextV2)
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        assertTrue("reason=$reason", reason.startsWith("apply_failed:rollback_failed:"))
        assertTrue("reason=$reason", reason.contains("restored_ok"))
        assertEquals(1, applier.reinstallCalls)
        // 索引条目恢复旧代、记录修回旧态、新代删除、旧布局文件完好。
        val entry = readIndex(store).layouts.single { it.id == "lay1" }
        assertEquals(1, entry.generation)
        assertEquals(layoutHash, entry.hash)
        assertEquals(ActiveRecord("s1", oldHash, 1), readActive(store))
        assertFalse(layout.layoutGenDir("lay1", 2).exists())
        assertEquals(layoutTextV1, File(layout.layoutGenDir("lay1", 1), "layout.json").readText())
        root.deleteRecursively()
    }

    @Test
    fun saveLayoutRollbackFailedRestoresPartialWhenReinstallFails() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        val applier = FakeApplier(
            rollbackFailed(),
            reinstallResult = false,
            store = store,
            leavePendingOnApply = true,
        )
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val layoutFile = active.files.first { it.kind == EditorFile.Kind.LAYOUT }
        val result = s.saveLayout(active, layoutFile, layoutTextV2)
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        // 重装失败不再被吞掉：restored_ok 只在全部恢复项成功时写。
        assertTrue("reason=$reason", reason.contains("restored_partial"))
        assertEquals(1, applier.reinstallCalls)
        root.deleteRecursively()
    }

    @Test
    fun saveLayoutBuildFailedStillRestoresOkWithoutReinstall() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        val applier = FakeApplier(
            ApplyTransaction.Result.Failed(SkinGenRef("s1", "", 1), ApplyTransaction.Result.Stage.BUILD, "snapshot_rebuild_failed"),
            store = store,
        )
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val layoutFile = active.files.first { it.kind == EditorFile.Kind.LAYOUT }
        val result = s.saveLayout(active, layoutFile, layoutTextV2)
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        assertTrue("reason=$reason", reason.contains("restored_ok"))
        assertEquals(0, applier.reinstallCalls)
        root.deleteRecursively()
    }
}
