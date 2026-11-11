// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 7 问题事务化重构的新行为单测（纯决策＋文件层，不依赖 Android Context）。
 * 覆盖：copy-on-write 代次分配、CAS 不一致中止、备份缺失 fail closed、
 * 回滚校验、manifest 缺失拒绝、布局只列绑定项、校验与工厂口径一致。
 */
class SkinEditorAuditFixTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun skinManifest(
        skinSha: String,
        extra: List<PackageManifest.Content> = emptyList(),
    ): PackageManifest = PackageManifest(
        formatVersion = SkinValidator.SUPPORTED_FORMAT_VERSION,
        contents = listOf(
            PackageManifest.Content("skin", "skin", "skin.json", skinSha, 10L),
        ) + extra,
    )

    private fun audioAsset(id: String = "snd_tap"): PackageManifest.Content =
        PackageManifest.Content("asset", id, "$id.ogg", "b".repeat(64), 100L)

    private fun newStore(): Triple<File, SkinLibraryLayout, SkinLibraryStore> {
        val root = Files.createTempDirectory("skin-editor-audit").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        return Triple(root, layout, SkinLibraryStore(layout))
    }

    // ---- 问题 5：manifest 校验（编辑前 fail closed） ----

    @Test
    fun manifestValidPasses() {
        val skinHash = "a".repeat(64)
        val check = SkinEditorSave.validateManifestForEdit(
            PackageManifest.toJson(skinManifest(skinHash)), skinHash,
        )
        assertTrue(check is ManifestCheck.Valid)
    }

    @Test
    fun manifestMissingRejected() {
        val check = SkinEditorSave.validateManifestForEdit(null, "a".repeat(64))
        assertTrue(check is ManifestCheck.Invalid)
        assertEquals("manifest_missing", (check as ManifestCheck.Invalid).reason)
    }

    @Test
    fun manifestUnparseableRejected() {
        val check = SkinEditorSave.validateManifestForEdit("{not json", "a".repeat(64))
        assertEquals("manifest_unparseable", (check as ManifestCheck.Invalid).reason)
    }

    @Test
    fun manifestSkinEntryMissingRejected() {
        val manifest = PackageManifest(contents = listOf(audioAsset()))
        val check = SkinEditorSave.validateManifestForEdit(
            PackageManifest.toJson(manifest), "a".repeat(64),
        )
        assertEquals("manifest_skin_entry_missing", (check as ManifestCheck.Invalid).reason)
    }

    @Test
    fun manifestDuplicatePathRejected() {
        val skinHash = "a".repeat(64)
        val manifest = skinManifest(
            skinHash,
            listOf(audioAsset("snd1"), audioAsset("snd2").copy(path = "snd1.ogg")),
        )
        val check = SkinEditorSave.validateManifestForEdit(
            PackageManifest.toJson(manifest), skinHash,
        )
        assertEquals("manifest_duplicate_path", (check as ManifestCheck.Invalid).reason)
    }

    @Test
    fun manifestSkinHashMismatchRejected() {
        val check = SkinEditorSave.validateManifestForEdit(
            PackageManifest.toJson(skinManifest("a".repeat(64))), "c".repeat(64),
        )
        assertEquals("manifest_skin_hash_mismatch", (check as ManifestCheck.Invalid).reason)
    }

    @Test
    fun manifestVersionUnsupportedRejected() {
        val manifest = skinManifest("a".repeat(64)).copy(formatVersion = 99)
        val check = SkinEditorSave.validateManifestForEdit(
            PackageManifest.toJson(manifest), "a".repeat(64),
        )
        assertEquals("manifest_version_unsupported", (check as ManifestCheck.Invalid).reason)
    }

    // ---- 问题 7：校验与工厂同一口径（manifest resolver） ----

    @Test
    fun manifestResolversMatchFactoryDerivation() {
        val manifest = skinManifest("a".repeat(64), listOf(audioAsset()))
        val kindById = SkinEditorSave.manifestAssetKindById(manifest)
        val shaById = SkinEditorSave.manifestAssetShaById(manifest)
        assertEquals(AssetKind.AUDIO, kindById["snd_tap"])
        assertEquals("b".repeat(64), shaById["snd_tap"])
        assertTrue(kindById["missing"] == null)
    }

    @Test
    fun decideSkinJsonWithManifestResolverHasNoFalseMissing() {
        val manifest = skinManifest("a".repeat(64), listOf(audioAsset()))
        val kindById = SkinEditorSave.manifestAssetKindById(manifest)
        val skinText = """{"formatVersion":3,"styles":{"keyBackground":"#FFFFFFFF"},"stylesDark":{"keyBackground":"#FF222222"},"keySound":{"actions":{"a":"snd_tap"}}}"""
        // 新口径：manifest resolver 认得 snd_tap，不报缺失。
        val ok = SkinEditorSave.decideSkinJson(skinText) { tree ->
            SkinValidator.validate(tree, 0, fallbacks, assetKind = { kindById[it] }, assetBytes = { null })
        }
        assertTrue(ok is EditorSaveOutcome.Commit)
        assertTrue((ok as EditorSaveOutcome.Commit).issues.none { it.contains("keysound_asset_missing") })
        // 旧口径对照：assetKind={null} 时同一皮肤恒报缺失（即问题 7 的误报）。
        val old = SkinEditorSave.decideSkinJson(skinText) { tree ->
            SkinValidator.validate(tree, 0, fallbacks, assetKind = { null }, assetBytes = { null })
        }
        assertTrue(old is EditorSaveOutcome.Commit)
        assertTrue((old as EditorSaveOutcome.Commit).issues.any { it.contains("keysound_asset_missing") })
    }

    // ---- 问题 6：布局只列绑定项 ----

    @Test
    fun boundLayoutIdsExtractTopLevelBindings() {
        val tree = SkinJson.parseObject(
            """{"formatVersion":3,"layoutId":"main9","layoutIdLandscape":"main9_land","styles":{"keyBackground":"#FFFFFFFF"},"stylesDark":{"keyBackground":"#FF222222"}}""",
        )!!
        val ids = SkinEditorSave.boundLayoutIds(tree)
        assertEquals(setOf("main9", "main9_land"), ids)
    }

    @Test
    fun filterBoundLayoutsKeepsOnlyBound() {
        val entries = listOf(
            LayoutIndexEntry("a", "h".repeat(64), 1, "layouts/a/gen-1", 10, 1),
            LayoutIndexEntry("b", "h".repeat(64), 1, "layouts/b/gen-1", 10, 1),
            LayoutIndexEntry("c", "h".repeat(64), 1, "layouts/c/gen-1", 10, 1),
        )
        val filtered = SkinEditorSave.filterBoundLayouts(entries, setOf("a", "c"))
        assertEquals(listOf("a", "c"), filtered.map { it.id })
    }

    // ---- 问题 1：copy-on-write 代次分配与复制 ----

    @Test
    fun nextGenerationTakesMaxPlusOne() {
        assertEquals(6, SkinEditorSave.nextGeneration(3, listOf(1, 2, 5)))
        assertEquals(1, SkinEditorSave.nextGeneration(null, emptyList()))
        assertEquals(6, SkinEditorSave.nextGeneration(5, listOf(1, 2)))
        assertEquals(4, SkinEditorSave.nextGeneration(1, listOf(3)))
    }

    @Test
    fun diskGenerationsScansGenDirs() {
        val (root, layout, _) = newStore()
        val idDir = File(layout.skinsDir, "s1")
        File(idDir, "gen-1").mkdirs()
        File(idDir, "gen-3").mkdirs()
        File(idDir, "other").mkdirs()
        File(idDir, "gen-x").mkdirs()
        assertEquals(listOf(1, 3), SkinEditorSave.diskGenerations(layout.skinsDir, "s1").sorted())
        assertEquals(emptyList<Int>(), SkinEditorSave.diskGenerations(layout.skinsDir, "nope"))
        root.deleteRecursively()
    }

    @Test
    fun copyGenDirCopiesAllFiles() {
        val (root, _, _) = newStore()
        val src = File(root, "src")
        File(src, "prepared").mkdirs()
        File(src, "skin.json").writeText("{}")
        File(src, "manifest.json").writeText("{}")
        File(src, "prepared/a.bin").writeBytes(byteArrayOf(1, 2, 3))
        val dst = File(root, "dst")
        assertTrue(SkinEditorSave.copyGenDir(src, dst))
        assertEquals("{}", File(dst, "skin.json").readText())
        assertEquals("{}", File(dst, "manifest.json").readText())
        assertTrue(File(dst, "prepared/a.bin").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        root.deleteRecursively()
    }

    @Test
    fun copyGenDirFailsOnMissingSource() {
        val (root, _, _) = newStore()
        assertFalse(SkinEditorSave.copyGenDir(File(root, "missing"), File(root, "dst")))
        root.deleteRecursively()
    }

    // ---- 问题 3：CAS ----

    @Test
    fun casMatchesOnlyOnIdenticalRecord() {
        val record = ActiveRecord("s1", "h".repeat(64), 2)
        assertTrue(SkinEditorSave.casMatches(record, "s1", 2, "h".repeat(64)))
        assertFalse(SkinEditorSave.casMatches(null, "s1", 2, "h".repeat(64)))
        assertFalse(SkinEditorSave.casMatches(record, "s2", 2, "h".repeat(64)))
        assertFalse(SkinEditorSave.casMatches(record, "s1", 3, "h".repeat(64)))
        assertFalse(SkinEditorSave.casMatches(record, "s1", 2, "g".repeat(64)))
        assertFalse(SkinEditorSave.casMatches(record, "s1", 2, null))
    }

    // ---- 问题 4：备份缺失 fail closed 与回滚校验 ----

    @Test
    fun skinBackupsCompleteWhenAllPresent() = runBlocking {
        val (_, layout, store) = newStore()
        val genDir = layout.skinGenDir("s1", 1)
        genDir.mkdirs()
        File(genDir, "skin.json").writeText("{}")
        File(genDir, "manifest.json").writeText("{}")
        store.writeActive(ActiveRecord("s1", "h".repeat(64), 1))
        val backups = SkinEditorSave.readSkinBackups(store, genDir)
        assertTrue(backups.isComplete())
    }

    @Test
    fun skinBackupsIncompleteWhenManifestMissing() = runBlocking {
        val (_, layout, store) = newStore()
        val genDir = layout.skinGenDir("s1", 1)
        genDir.mkdirs()
        File(genDir, "skin.json").writeText("{}")
        store.writeActive(ActiveRecord("s1", "h".repeat(64), 1))
        val backups = SkinEditorSave.readSkinBackups(store, genDir)
        assertFalse(backups.isComplete())
    }

    @Test
    fun skinBackupsIncompleteWhenNoActiveRecord() = runBlocking {
        val (_, layout, store) = newStore()
        val genDir = layout.skinGenDir("s1", 1)
        genDir.mkdirs()
        File(genDir, "skin.json").writeText("{}")
        File(genDir, "manifest.json").writeText("{}")
        val backups = SkinEditorSave.readSkinBackups(store, genDir)
        assertFalse(backups.isComplete())
    }

    @Test
    fun layoutBackupsIncompleteWhenIndexMissing() = runBlocking {
        val (_, layout, store) = newStore()
        val layoutFile = File(layout.layoutGenDir("l1", 1), "layout.json")
        layoutFile.parentFile?.mkdirs()
        layoutFile.writeText("{}")
        store.writeActive(ActiveRecord("s1", "h".repeat(64), 1))
        val backups = SkinEditorSave.readLayoutBackups(store, layoutFile)
        assertFalse(backups.isComplete())
    }

    @Test
    fun genSkinHashMatchesVerifiesRollback() {
        val (root, layout, _) = newStore()
        val genDir = layout.skinGenDir("s1", 1)
        genDir.mkdirs()
        val bytes = "{}".toByteArray(Charsets.UTF_8)
        File(genDir, "skin.json").writeBytes(bytes)
        val hash = sha256Hex(bytes)
        assertTrue(SkinEditorSave.genSkinHashMatches(genDir, hash))
        assertFalse(SkinEditorSave.genSkinHashMatches(genDir, "0".repeat(64)))
        assertFalse(SkinEditorSave.genSkinHashMatches(File(root, "missing"), hash))
        root.deleteRecursively()
    }
}
