// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO36 导入事务失败码：用户可见的 Result.Failed.reason 只放稳定码，
 * 不放原始异常消息（英文技术文本，可能含 id／路径，经 JimeImporter →
 * 皮肤库页拒绝对话框会原样上屏）。完整异常只进 DiagLog。
 * 分类走 DeepErrorMapper 既有口径，归类不出的按本类 check 文案前缀定码。
 */
class Wo36SkinImportTransactionTest {

    @Test
    fun failureCode_renameCheckMessage() {
        assertEquals(
            "rename_failed",
            importFailureCode(IllegalStateException("rename staged skin s1 failed")),
        )
        assertEquals(
            "rename_failed",
            importFailureCode(IllegalStateException("rename staged asset abc failed")),
        )
    }

    @Test
    fun failureCode_verifyCheckMessage() {
        assertEquals(
            "verify_failed",
            importFailureCode(IllegalStateException("staged skin s1 hash mismatch")),
        )
        assertEquals(
            "verify_failed",
            importFailureCode(IllegalStateException("staged skin s1 missing skin.json")),
        )
    }

    @Test
    fun failureCode_deepErrorMapperClassification() {
        assertEquals(
            "no_space",
            importFailureCode(IOException("No space left on device")),
        )
        assertEquals(
            "no_space",
            importFailureCode(IOException("disk full")),
        )
        assertEquals("io", importFailureCode(IOException("boom")))
        assertEquals("corrupt", importFailureCode(ZipException("bad zip")))
    }

    @Test
    fun failureCode_unknownIsFailed() {
        assertEquals("failed", importFailureCode(IllegalStateException("bad package")))
        assertEquals("failed", importFailureCode(RuntimeException("boom")))
    }

    @Test
    fun stagedHashMismatchFailsWithStableCode() = runBlocking {
        val f = Fixture()
        val result = f.tx.importPackage { staging ->
            val staged = f.stageSkin(staging, "s1")
            ImportTransaction.BuiltPackage(
                skins = listOf(staged.copy(hash = "0".repeat(64))),
            )
        }
        assertTrue(result is ImportTransaction.Result.Failed)
        result as ImportTransaction.Result.Failed
        assertEquals("build", result.stage)
        // 原因只放稳定码：不含皮肤 id，不含原始 check 消息。
        assertEquals("verify_failed", result.reason)
        assertFalse(result.reason.contains("s1"))
        assertTrue(f.index().skins.isEmpty())
    }

    @Test
    fun builderIoFailureFailsWithClassifiedCode() = runBlocking {
        val f = Fixture()
        val result = f.tx.importPackage {
            throw IOException("No space left on device")
        }
        assertTrue(result is ImportTransaction.Result.Failed)
        result as ImportTransaction.Result.Failed
        assertEquals("build", result.stage)
        assertEquals("no_space", result.reason)
        assertTrue(f.index().skins.isEmpty())
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("skin-import-wo36").toFile()
        val layout = SkinLibraryLayout(root)
        val store = SkinLibraryStore(layout)
        val tx = ImportTransaction(layout, store, uuid = { "uuid-wo36" }, clock = { 5_000L })

        init {
            layout.ensureDirs()
        }

        fun stageSkin(staging: File, id: String): ImportTransaction.StagedSkin {
            val dir = File(staging, "skins/$id")
            dir.mkdirs()
            val skinJson =
                """{"meta":{"id":"$id","name":"Skin-$id","version":"1.0"},"styles":{}}"""
            File(dir, "skin.json").writeText(skinJson)
            val hash = DigestUtil.sha256Hex(skinJson.toByteArray())
            File(dir, "manifest.json").writeText(
                """{"version":1,"contents":[{"type":"skin","id":"$id","path":"skin.json","sha256":"$hash"}]}""",
            )
            return ImportTransaction.StagedSkin(id, "Skin-$id", dir, hash)
        }

        suspend fun index(): LibraryIndex =
            (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value ?: LibraryIndex()
    }
}
