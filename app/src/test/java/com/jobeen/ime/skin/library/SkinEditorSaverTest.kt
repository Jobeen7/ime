// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.ContextWrapper
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinApplyPort
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.store.ActiveRecord
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkinEditorSaver 直接单测（WO30 §2 项 3）：保存／读回／失败路径。
 * 假应用事务（[FakeApplier]）＋真实临时库（[SkinLibraryLayout]／
 * [SkinLibraryStore]），不依赖 Android Context、不跑 Gradle。
 * 另覆 WO30 §2 项 4 的记录 hash 门与项 2 的坐标放宽口径。
 */
class SkinEditorSaverTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private class FakeContext : ContextWrapper(null)

    private class FakeApplier(
        var result: ApplyTransaction.Result = ApplyTransaction.Result.Applied(
            SkinGenRef("s1", "", 1),
        ),
    ) : SkinApplyPort {
        var applyCalls = 0
        var reinstallCalls = 0

        override suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result {
            applyCalls++
            return result
        }

        override suspend fun reinstallActiveSnapshot(): Boolean {
            reinstallCalls++
            return true
        }
    }

    private data class Seed(val skinId: String, val hash: String, val layoutHash: String)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun skinManifest(skinSha: String): PackageManifest = PackageManifest(
        formatVersion = SkinValidator.SUPPORTED_FORMAT_VERSION,
        contents = listOf(
            PackageManifest.Content("skin", "skin", "skin.json", skinSha, 10L),
        ),
    )

    private fun newStore(): Triple<File, SkinLibraryLayout, SkinLibraryStore> {
        val root = Files.createTempDirectory("skin-editor-saver").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        return Triple(root, layout, SkinLibraryStore(layout))
    }

    private val layoutTextV1: String =
        """{"formatVersion":3,"meta":{"id":"lay1","name":"测试布局"},"rows":[{"keys":[{"stableId":"1","label":"q","tap":{"type":"text","value":"q"}}]}]}"""

    private val layoutTextV2: String =
        """{"formatVersion":3,"meta":{"id":"lay1","name":"测试布局"},"rows":[{"keys":[{"stableId":"1","label":"w","tap":{"type":"text","value":"w"}}]}]}"""

    private fun skinText(
        keyBackground: String = "#FFFFFFFF",
        layoutBinding: Boolean = false,
        keyMappings: String? = null,
    ): String {
        val layoutPart = if (layoutBinding) ""","layoutId":"lay1"""" else ""
        val mappingsPart = if (keyMappings != null) ""","keyMappings":$keyMappings""" else ""
        return """{"formatVersion":3,"styles":{"keyBackground":"$keyBackground"},"stylesDark":{"keyBackground":"#FF222222"}$layoutPart$mappingsPart}"""
    }

    /** 完整一代皮肤落盘：gen-1 skin.json＋manifest＋活动记录＋索引（可选绑定布局）。 */
    private suspend fun seedSkin(
        layout: SkinLibraryLayout,
        store: SkinLibraryStore,
        skinId: String = "s1",
        text: String = skinText(layoutBinding = true),
        withLayout: Boolean = true,
    ): Seed {
        val genDir = layout.skinGenDir(skinId, 1)
        genDir.mkdirs()
        File(genDir, "skin.json").writeText(text)
        val hash = sha256Hex(text.toByteArray(Charsets.UTF_8))
        File(genDir, "manifest.json").writeText(PackageManifest.toJson(skinManifest(hash)))
        store.writeActive(ActiveRecord(skinId, hash, 1))
        val layoutHash = sha256Hex(layoutTextV1.toByteArray(Charsets.UTF_8))
        val layoutEntry = if (withLayout) {
            LayoutIndexEntry(
                "lay1", layoutHash, 1,
                layout.layoutGenRelative("lay1", 1), layoutTextV1.length.toLong(), 1L,
            )
        } else {
            null
        }
        if (withLayout) {
            val layoutDir = layout.layoutGenDir("lay1", 1)
            layoutDir.mkdirs()
            File(layoutDir, "layout.json").writeText(layoutTextV1)
        }
        store.writeIndex(
            LibraryIndex(
                skins = listOf(
                    SkinIndexEntry(
                        skinId, "测试皮肤", hash, 1,
                        layout.skinGenRelative(skinId, 1), 100L, 1L,
                    ),
                ),
                layouts = listOfNotNull(layoutEntry),
            ),
        )
        return Seed(skinId, hash, layoutHash)
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

    private suspend fun readIndex(store: SkinLibraryStore): LibraryIndex {
        val read = store.readIndex()
        assertTrue(read is SkinLibraryStore.ReadResult.Ok)
        return (read as SkinLibraryStore.ReadResult.Ok).value
    }

    // ---- resolve：读回 ----

    @Test
    fun resolveActiveFilesOkListsSkinAndBoundLayout() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val active = resolveOk(saver(layout, store, FakeApplier()))
        assertEquals(seed.skinId, active.skinId)
        assertEquals(1, active.generation)
        assertEquals(seed.hash, active.hash)
        assertEquals(2, active.files.size)
        assertTrue(active.files.any { it.kind == EditorFile.Kind.SKIN_JSON })
        assertTrue(active.files.any { it.kind == EditorFile.Kind.LAYOUT && it.id == "lay1" })
        root.deleteRecursively()
    }

    @Test
    fun loadTextReturnsContentAndNullForMissing() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        val s = saver(layout, store, FakeApplier())
        val active = resolveOk(s)
        val skinFile = active.files.first { it.kind == EditorFile.Kind.SKIN_JSON }
        assertEquals(skinText(layoutBinding = true), s.loadText(skinFile.file))
        assertNull(s.loadText(File(root, "missing.json")))
        root.deleteRecursively()
    }

    // ---- 项 4：记录 hash 门 ----

    @Test
    fun resolveActiveFilesRejectsStaleRecordHash() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        // 外部改动 skin.json 并同步更新 manifest（仅活动记录过期），
        // 隔离记录 hash 门：拒因须是 active_hash_mismatch。
        val edited = skinText(layoutBinding = true, keyBackground = "#FF000000")
        val genDir = layout.skinGenDir(seed.skinId, 1)
        File(genDir, "skin.json").writeText(edited)
        val newHash = sha256Hex(edited.toByteArray(Charsets.UTF_8))
        File(genDir, "manifest.json").writeText(PackageManifest.toJson(skinManifest(newHash)))
        val resolved = saver(layout, store, FakeApplier()).resolveActiveFiles()
        assertTrue(resolved is ResolveActiveResult.Unavailable)
        assertEquals("active_hash_mismatch", (resolved as ResolveActiveResult.Unavailable).reason)
        root.deleteRecursively()
    }

    @Test
    fun resolveActiveFilesRejectsMissingSkinJson() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        File(layout.skinGenDir("s1", 1), "skin.json").delete()
        val resolved = saver(layout, store, FakeApplier()).resolveActiveFiles()
        assertTrue(resolved is ResolveActiveResult.Unavailable)
        assertEquals("skin_json_missing", (resolved as ResolveActiveResult.Unavailable).reason)
        root.deleteRecursively()
    }

    @Test
    fun resolveActiveFilesAcceptsConsistentExternalEdit() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        // 外部改动＋记录同步更新：hash 门是比对，不是 blanket 拒绝。
        val edited = skinText(layoutBinding = true, keyBackground = "#FF000000")
        val genDir = layout.skinGenDir(seed.skinId, 1)
        File(genDir, "skin.json").writeText(edited)
        val newHash = sha256Hex(edited.toByteArray(Charsets.UTF_8))
        File(genDir, "manifest.json").writeText(PackageManifest.toJson(skinManifest(newHash)))
        store.writeActive(ActiveRecord(seed.skinId, newHash, 1))
        val active = resolveOk(saver(layout, store, FakeApplier()))
        assertEquals(newHash, active.hash)
        root.deleteRecursively()
    }

    // ---- saveSkinJson：保存路径 ----

    @Test
    fun saveSkinJsonSavedCreatesNewGeneration() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val newText = skinText(layoutBinding = true, keyBackground = "#FF000000")
        val result = s.saveSkinJson(active, newText)
        assertTrue("expected Saved, got $result", result is SkinEditorSaveResult.Saved)
        assertEquals(1, applier.applyCalls)
        val newGenDir = layout.skinGenDir(seed.skinId, 2)
        assertTrue(newGenDir.isDirectory)
        assertEquals(newText, File(newGenDir, "skin.json").readText())
        // 旧代只读不改。
        assertEquals(
            skinText(layoutBinding = true),
            File(layout.skinGenDir(seed.skinId, 1), "skin.json").readText(),
        )
        val entry = readIndex(store).skins.single { it.id == seed.skinId }
        assertEquals(2, entry.generation)
        assertEquals(sha256Hex(newText.toByteArray(Charsets.UTF_8)), entry.hash)
        root.deleteRecursively()
    }

    @Test
    fun saveSkinJsonBlockedOnInvalidText() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val result = s.saveSkinJson(active, "{not json")
        assertTrue("expected Blocked, got $result", result is SkinEditorSaveResult.Blocked)
        assertEquals(0, applier.applyCalls)
        assertFalse(layout.skinGenDir(seed.skinId, 2).exists())
        assertEquals(1, readIndex(store).skins.single { it.id == seed.skinId }.generation)
        root.deleteRecursively()
    }

    @Test
    fun saveSkinJsonStaleWhenActiveChanged() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        // 保存前活动记录被改动：CAS 须中止，不写任何文件。
        store.writeActive(ActiveRecord(seed.skinId, "0".repeat(64), 1))
        val result = s.saveSkinJson(active, skinText(layoutBinding = true, keyBackground = "#FF000000"))
        assertTrue("expected Stale, got $result", result is SkinEditorSaveResult.Stale)
        assertEquals(0, applier.applyCalls)
        assertFalse(layout.skinGenDir(seed.skinId, 2).exists())
        root.deleteRecursively()
    }

    @Test
    fun saveSkinJsonFailedWhenBackupMissing() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        File(layout.skinGenDir(seed.skinId, 1), "manifest.json").delete()
        val result = s.saveSkinJson(active, skinText(layoutBinding = true, keyBackground = "#FF000000"))
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        assertEquals("backup_missing", (result as SkinEditorSaveResult.Failed).reason)
        assertEquals(0, applier.applyCalls)
        assertFalse(layout.skinGenDir(seed.skinId, 2).exists())
        root.deleteRecursively()
    }

    @Test
    fun saveSkinJsonApplyFailedDiscardsNewGeneration() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier(
            ApplyTransaction.Result.BuildFailed(SkinGenRef(seed.skinId, "", 2)),
        )
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val result = s.saveSkinJson(active, skinText(layoutBinding = true, keyBackground = "#FF000000"))
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        assertTrue("reason=$reason", reason.startsWith("apply_failed"))
        assertTrue("reason=$reason", reason.contains("old_gen_intact"))
        assertFalse(layout.skinGenDir(seed.skinId, 2).exists())
        assertEquals(1, readIndex(store).skins.single { it.id == seed.skinId }.generation)
        assertEquals(
            skinText(layoutBinding = true),
            File(layout.skinGenDir(seed.skinId, 1), "skin.json").readText(),
        )
        root.deleteRecursively()
    }

    // ---- saveLayout：保存路径 ----

    @Test
    fun saveLayoutSavedCreatesNewGeneration() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val layoutFile = active.files.first { it.kind == EditorFile.Kind.LAYOUT }
        val result = s.saveLayout(active, layoutFile, layoutTextV2)
        assertTrue("expected Saved, got $result", result is SkinEditorSaveResult.Saved)
        assertEquals(1, applier.applyCalls)
        assertEquals(layoutTextV2, File(layout.layoutGenDir("lay1", 2), "layout.json").readText())
        assertEquals(layoutTextV1, File(layout.layoutGenDir("lay1", 1), "layout.json").readText())
        val entry = readIndex(store).layouts.single { it.id == "lay1" }
        assertEquals(2, entry.generation)
        assertEquals(sha256Hex(layoutTextV2.toByteArray(Charsets.UTF_8)), entry.hash)
        root.deleteRecursively()
    }

    @Test
    fun saveLayoutBlockedOnInvalidText() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val layoutFile = active.files.first { it.kind == EditorFile.Kind.LAYOUT }
        val result = s.saveLayout(active, layoutFile, "{not json")
        assertTrue("expected Blocked, got $result", result is SkinEditorSaveResult.Blocked)
        assertEquals(0, applier.applyCalls)
        assertFalse(layout.layoutGenDir("lay1", 2).exists())
        root.deleteRecursively()
    }

    @Test
    fun saveLayoutApplyFailedRestoresOldIndex() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier(
            ApplyTransaction.Result.BuildFailed(SkinGenRef(seed.skinId, "", 1)),
        )
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val layoutFile = active.files.first { it.kind == EditorFile.Kind.LAYOUT }
        val result = s.saveLayout(active, layoutFile, layoutTextV2)
        assertTrue("expected Failed, got $result", result is SkinEditorSaveResult.Failed)
        val reason = (result as SkinEditorSaveResult.Failed).reason
        assertTrue("reason=$reason", reason.startsWith("apply_failed"))
        assertTrue("reason=$reason", reason.contains("restored_ok"))
        assertFalse(layout.layoutGenDir("lay1", 2).exists())
        val entry = readIndex(store).layouts.single { it.id == "lay1" }
        assertEquals(1, entry.generation)
        assertEquals(seed.layoutHash, entry.hash)
        assertEquals(layoutTextV1, File(layout.layoutGenDir("lay1", 1), "layout.json").readText())
        // BuildFailed 不触发重装快照。
        assertEquals(0, applier.reinstallCalls)
        root.deleteRecursively()
    }

    // ---- 项 2：isOverridable 与出厂口径一致 ----

    @Test
    fun editorIsOverridableMatchesFactoryScope() {
        val predicate = SkinEditorSave.editorIsOverridable(setOf("lay1:K-1", "lay2:K-5"))
        assertTrue(predicate("lay1:K-1"))
        assertTrue(predicate("lay2:K-5"))
        assertTrue(predicate("KEY_Q"))
        assertFalse(predicate("lay1:K-9"))
        assertFalse(predicate("KEY_BOGUS"))
        assertFalse(predicate(""))
    }

    @Test
    fun saveSkinJsonAcceptsCoordIdKeyMapping() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        val seed = seedSkin(layout, store)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val active = resolveOk(s)
        val mappings = """{"lay1:K-1":{"tap":{"type":"text","value":"q"}}}"""
        val newText = skinText(layoutBinding = true, keyMappings = mappings)
        val result = s.saveSkinJson(active, newText)
        assertTrue("expected Saved, got $result", result is SkinEditorSaveResult.Saved)
        // 出厂口径复核：保存后的 skin.json 按快照工厂坐标并集校验，
        // 坐标覆写须落在 keyOverrides 内，而不是被静默剔掉。
        val savedTree = SkinJson.parseObject(
            File(layout.skinGenDir(seed.skinId, 2), "skin.json").readText(),
        )!!
        val factoryCoordIds = SkinSnapshotFactory.layoutCoordIds(
            savedTree,
            SkinSnapshotFactory.LayoutLookup(layout),
        )
        assertTrue(factoryCoordIds.contains("lay1:K-1"))
        val validated = SkinValidator.validate(
            savedTree, 0, fallbacks,
            assetKind = { null },
            isOverridable = SkinEditorSave.editorIsOverridable(factoryCoordIds),
        )
        assertTrue("expected Valid, got $validated", validated is SkinValidator.Result.Valid)
        val valid = validated as SkinValidator.Result.Valid
        assertTrue("lay1:K-1" in valid.skin.keyOverrides)
        assertTrue(valid.skin.keyRejections.none { it.keyId == "lay1:K-1" })
        root.deleteRecursively()
    }
}
