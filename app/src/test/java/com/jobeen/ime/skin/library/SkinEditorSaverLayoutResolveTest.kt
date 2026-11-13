// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.ContextWrapper
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinApplyPort
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
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * S-L2／S-L3：resolveActiveFiles 的布局循环按文件状态分区——
 * 缺失跳过（既有口径，见 SkinEditorSaverTest）、读失败 fail-closed、
 * hash 失配降级排除、一致放行；降级后 skin.json 仍可保存。
 * 读失败的触发：root 会绕过权限位，chmod 无法让一次读失败；
 * 故用指向 /proc/self/mem 的符号链接——它仍是 isFile 为真的常规
 * 文件，但 open 或读恒失败（root 下读报 EIO，非 root 下 open 报
 * EACCES），两档都进 fail-closed 分支。
 */
class SkinEditorSaverLayoutResolveTest {

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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun skinManifest(skinSha: String): PackageManifest = PackageManifest(
        formatVersion = SkinValidator.SUPPORTED_FORMAT_VERSION,
        contents = listOf(
            PackageManifest.Content("skin", "skin", "skin.json", skinSha, 10L),
        ),
    )

    private fun newStore(): Triple<File, SkinLibraryLayout, SkinLibraryStore> {
        val root = Files.createTempDirectory("skin-editor-layout-resolve").toFile()
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

    private suspend fun seedSkin(
        layout: SkinLibraryLayout,
        store: SkinLibraryStore,
    ) {
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

    // ---- S-L2：布局读失败 fail-closed ----

    @Test
    fun resolveActiveFilesRejectsUnreadableLayout() = runBlocking<Unit> {
        Assume.assumeTrue(
            "needs /proc/self/mem as a deterministically unreadable regular file",
            Files.exists(Path.of("/proc/self/mem")),
        )
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        val layoutFile = File(layout.layoutGenDir("lay1", 1), "layout.json")
        layoutFile.delete()
        Files.createSymbolicLink(layoutFile.toPath(), Path.of("/proc/self/mem"))
        val resolved = saver(layout, store, FakeApplier()).resolveActiveFiles()
        assertTrue("expected Unavailable, got $resolved", resolved is ResolveActiveResult.Unavailable)
        assertEquals(
            "layout_unreadable:lay1",
            (resolved as ResolveActiveResult.Unavailable).reason,
        )
        root.deleteRecursively()
    }

    // ---- S-L3：布局 hash 失配降级排除，不整个打不开 ----

    @Test
    fun resolveActiveFilesExcludesLayoutHashMismatch() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        // 外部改动布局文件、索引未同步：该布局被排除，编辑器照常打开。
        File(layout.layoutGenDir("lay1", 1), "layout.json").writeText(layoutTextV2)
        val resolved = saver(layout, store, FakeApplier()).resolveActiveFiles()
        assertTrue("expected Ok, got $resolved", resolved is ResolveActiveResult.Ok)
        val active = (resolved as ResolveActiveResult.Ok).files
        assertEquals(1, active.files.size)
        assertTrue(active.files.all { it.kind == EditorFile.Kind.SKIN_JSON })
        assertEquals(listOf("lay1"), active.unavailableLayouts)
        root.deleteRecursively()
    }

    @Test
    fun degradedResolveStillSavesSkinJson() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        File(layout.layoutGenDir("lay1", 1), "layout.json").writeText(layoutTextV2)
        val applier = FakeApplier()
        val s = saver(layout, store, applier)
        val resolved = s.resolveActiveFiles()
        assertTrue("expected Ok, got $resolved", resolved is ResolveActiveResult.Ok)
        val active = (resolved as ResolveActiveResult.Ok).files
        val newText = skinText(keyBackground = "#FF000000")
        val result = s.saveSkinJson(active, newText)
        assertTrue("expected Saved, got $result", result is SkinEditorSaveResult.Saved)
        assertEquals(1, applier.applyCalls)
        assertEquals(newText, File(layout.skinGenDir("s1", 2), "skin.json").readText())
        val index = store.readIndex()
        assertTrue(index is SkinLibraryStore.ReadResult.Ok)
        assertEquals(
            2,
            (index as SkinLibraryStore.ReadResult.Ok).value.skins.single { it.id == "s1" }.generation,
        )
        root.deleteRecursively()
    }

    @Test
    fun resolveActiveFilesKeepsConsistentLayout() = runBlocking<Unit> {
        val (root, layout, store) = newStore()
        seedSkin(layout, store)
        val resolved = saver(layout, store, FakeApplier()).resolveActiveFiles()
        assertTrue("expected Ok, got $resolved", resolved is ResolveActiveResult.Ok)
        val active = (resolved as ResolveActiveResult.Ok).files
        assertEquals(2, active.files.size)
        assertTrue(active.unavailableLayouts.isEmpty())
        root.deleteRecursively()
    }
}
