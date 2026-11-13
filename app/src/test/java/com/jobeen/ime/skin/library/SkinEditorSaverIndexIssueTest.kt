// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.ContextWrapper
import com.jobeen.ime.R
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinApplyPort
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyTransaction
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-L4：保存成功但索引更新失败时，issues 里不再是硬编码中文，而是
 * strings.xml 的 `skin_editor_reason_index_update_failed` 串；保存
 * 本身不被推翻（皮肤已生效）。索引损坏可让 updateIndex 返回 null，
 * 是 JVM 单测里确定触发索引更新失败的办法。
 */
class SkinEditorSaverIndexIssueTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    // 取串走显式解析器：保存流程除 issues 取串外不碰 Context（版本号
    // 与几何回落均由构造参数显式传入，JVM 单测无真实资源）；Context
    // 本身只用 ContextWrapper(null) 占位。
    private class FakeApplier : SkinApplyPort {
        var applyCalls = 0

        override suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result {
            applyCalls++
            return ApplyTransaction.Result.Applied(ref)
        }

        override suspend fun reinstallActiveSnapshot(): Boolean = true
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun skinManifest(skinSha: String): PackageManifest = PackageManifest(
        formatVersion = SkinValidator.SUPPORTED_FORMAT_VERSION,
        contents = listOf(
            PackageManifest.Content("skin", "skin", "skin.json", skinSha, 10L),
        ),
    )

    private fun skinText(keyBackground: String = "#FFFFFFFF"): String =
        """{"formatVersion":3,"styles":{"keyBackground":"$keyBackground"},"stylesDark":{"keyBackground":"#FF222222"}}"""

    @Test
    fun saveSkinJsonIndexFailureKeepsSavedWithStringIssue() = runBlocking<Unit> {
        val root = Files.createTempDirectory("skin-editor-index-issue").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        val store = SkinLibraryStore(layout)
        val text = skinText()
        val genDir = layout.skinGenDir("s1", 1)
        genDir.mkdirs()
        File(genDir, "skin.json").writeText(text)
        val hash = sha256Hex(text.toByteArray(Charsets.UTF_8))
        File(genDir, "manifest.json").writeText(PackageManifest.toJson(skinManifest(hash)))
        store.writeActive(ActiveRecord("s1", hash, 1))
        store.writeIndex(
            LibraryIndex(
                skins = listOf(
                    SkinIndexEntry(
                        "s1", "测试皮肤", hash, 1,
                        layout.skinGenRelative("s1", 1), 100L, 1L,
                    ),
                ),
            ),
        )
        val context = ContextWrapper(null)
        val applier = FakeApplier()
        val saver = SkinEditorSaver(
            context = context,
            store = store,
            layout = layout,
            applier = applier,
            currentVersionCode = 0,
            fallbacks = fallbacks,
            mainDispatcher = Dispatchers.Unconfined,
            // 取串解析器显式传入：STUB 回显 resId，断言保存器确实用了
            // strings.xml 的 skin_editor_reason_index_update_failed 串。
            resolveString = { resId -> "STUB[$resId]" },
        )
        val resolved = saver.resolveActiveFiles()
        assertTrue("expected Ok, got $resolved", resolved is ResolveActiveResult.Ok)
        val active = (resolved as ResolveActiveResult.Ok).files
        // 索引损坏：后续 updateIndex 读到 Corrupt 即返回 null，索引更新失败。
        layout.indexFile.writeText("{not json")
        val newText = skinText(keyBackground = "#FF000000")
        val result = saver.saveSkinJson(active, newText)
        assertTrue("expected Saved, got $result", result is SkinEditorSaveResult.Saved)
        val issues = (result as SkinEditorSaveResult.Saved).issues
        // issues 末尾须是取自 strings.xml 串的那一条，而非硬编码中文：
        // 断言它等于同一 Context 对该串 ID 的取串结果（不是字面量比对）。
        // 前面的条目是校验回落项（decide 的 issues），不假定其为空。
        val expected = "STUB[${R.string.skin_editor_reason_index_update_failed}]"
        assertEquals(expected, issues.last())
        assertEquals(1, issues.count { it == expected })
        assertEquals(1, applier.applyCalls)
        assertEquals(newText, File(layout.skinGenDir("s1", 2), "skin.json").readText())
        root.deleteRecursively()
    }
}
