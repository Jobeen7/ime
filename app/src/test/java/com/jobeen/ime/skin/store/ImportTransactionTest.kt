package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.fault.SkinFaultHooks
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入事务机制层（详设 §2.3）：staging 生命周期、代次分配、
 * 同 id 同 hash 跳过、hash 复核、失败清理（库零变化）。
 * 包内容由测试回调直接在 staging 内摆好（校验管线属 T3）。
 * 并行导入：提交段（读索引→改名→写索引）跨实例串行——
 * 同 id 并行会撞同一代次目录（rename 失败），异 id 并行会
 * 各自拿旧索引快照写回、丢对方条目（探针复现：同 id 9/30、
 * 异 id 24/30）；两支测试用 I2 栅栏把两笔导入对齐在读索引
 * 之前，否则时序可能自然串行、测不到竞态。
 */
class ImportTransactionTest {

    private class Fixture {
        val root: File = Files.createTempDirectory("skin-import").toFile()
        val layout = SkinLibraryLayout(root)
        val store = SkinLibraryStore(layout)
        var uuidSeq = 0
        val tx = ImportTransaction(layout, store, uuid = { "uuid-${uuidSeq++}" }, clock = { 5_000L })

        init {
            layout.ensureDirs()
        }

        /** 在 staging 内摆一代皮肤（含可选资产引用），返回其描述。 */
        fun stageSkin(
            staging: File,
            id: String,
            name: String = "Skin-$id",
            salt: String = "",
            assetShas: List<String> = emptyList(),
        ): ImportTransaction.StagedSkin {
            val dir = File(staging, "skins/$id")
            dir.mkdirs()
            val skinJson =
                """{"meta":{"id":"$id","name":"$name","version":"1.0"},"styles":{},"salt":"$salt"}"""
            File(dir, "skin.json").writeText(skinJson)
            val hash = DigestUtil.sha256Hex(skinJson.toByteArray())
            val assetEntries = assetShas.joinToString(",") {
                """,{"type":"asset","id":"a-$it","path":"assets/$it.png","sha256":"$it"}"""
            }
            File(dir, "manifest.json").writeText(
                """{"version":1,"contents":[{"type":"skin","id":"$id","path":"skin.json","sha256":"$hash"}$assetEntries]}""",
            )
            return ImportTransaction.StagedSkin(id, name, dir, hash)
        }

        fun stageAsset(staging: File, bytes: ByteArray): ImportTransaction.StagedAsset {
            val sha = DigestUtil.sha256Hex(bytes)
            val dir = File(staging, "assets")
            dir.mkdirs()
            val file = File(dir, "$sha.png")
            file.writeBytes(bytes)
            return ImportTransaction.StagedAsset(sha, file)
        }

        suspend fun index(): LibraryIndex =
            (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value ?: LibraryIndex()
    }

    @Test
    fun importsSkinAndAsset() = runBlocking {
        val f = Fixture()
        lateinit var assetSha: String
        val result = f.tx.importPackage { staging ->
            val asset = f.stageAsset(staging, byteArrayOf(9, 9, 9))
            assetSha = asset.sha256
            val skin = f.stageSkin(staging, "s1", assetShas = listOf(asset.sha256))
            ImportTransaction.BuiltPackage(skins = listOf(skin), assets = listOf(asset))
        }
        assertTrue(result is ImportTransaction.Result.Imported)
        result as ImportTransaction.Result.Imported
        assertEquals(1, result.skins.size)
        assertEquals(1, result.skins[0].generation)
        assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        assertTrue(f.layout.assetFiles(assetSha).isNotEmpty())
        val index = f.index()
        assertEquals(1, index.skins.size)
        assertEquals("skins/s1/gen-1", index.skins[0].dir)
        // 引用图刷新：资产被该代 manifest 引用 → refcountCache 1
        assertEquals(1, index.assets.first { it.sha256 == assetSha }.refcountCache)
        // staging 已清
        assertTrue(f.layout.stagingDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun secondImportBumpsGeneration() = runBlocking {
        val f = Fixture()
        f.tx.importPackage { staging ->
            ImportTransaction.BuiltPackage(skins = listOf(f.stageSkin(staging, "s1", salt = "v1")))
        }
        val result = f.tx.importPackage { staging ->
            ImportTransaction.BuiltPackage(skins = listOf(f.stageSkin(staging, "s1", salt = "v2")))
        }
        result as ImportTransaction.Result.Imported
        assertEquals(2, result.skins[0].generation)
        assertTrue(f.layout.skinGenDir("s1", 1).isDirectory)
        assertTrue(f.layout.skinGenDir("s1", 2).isDirectory)
        assertEquals(2, f.index().skins[0].generation)
    }

    @Test
    fun sameIdSameHashIsSkipped() = runBlocking {
        val f = Fixture()
        f.tx.importPackage { staging ->
            ImportTransaction.BuiltPackage(skins = listOf(f.stageSkin(staging, "s1", salt = "v1")))
        }
        val result = f.tx.importPackage { staging ->
            ImportTransaction.BuiltPackage(skins = listOf(f.stageSkin(staging, "s1", salt = "v1")))
        }
        result as ImportTransaction.Result.Imported
        assertEquals(listOf("s1"), result.skippedSkinIds)
        assertTrue(result.skins.isEmpty())
        assertFalse(f.layout.skinGenDir("s1", 2).exists())
    }

    @Test
    fun builderFailureLeavesLibraryUntouched() = runBlocking {
        val f = Fixture()
        val result = f.tx.importPackage { staging ->
            f.stageSkin(staging, "s1")
            throw IllegalStateException("bad package")
        }
        assertTrue(result is ImportTransaction.Result.Failed)
        assertEquals("build", (result as ImportTransaction.Result.Failed).stage)
        assertTrue(f.index().skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        assertTrue(f.layout.stagingDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun stagedHashMismatchIsRejected() = runBlocking {
        val f = Fixture()
        val result = f.tx.importPackage { staging ->
            val staged = f.stageSkin(staging, "s1")
            ImportTransaction.BuiltPackage(
                skins = listOf(staged.copy(hash = "0".repeat(64))),
            )
        }
        assertTrue(result is ImportTransaction.Result.Failed)
        assertTrue(f.index().skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
    }

    @Test
    fun importsLayoutGeneration() = runBlocking {
        val f = Fixture()
        val result = f.tx.importPackage { staging ->
            val dir = File(staging, "layouts/l1")
            dir.mkdirs()
            val layoutJson = """{"id":"l1","rows":[]}"""
            File(dir, "layout.json").writeText(layoutJson)
            val hash = DigestUtil.sha256Hex(layoutJson.toByteArray())
            ImportTransaction.BuiltPackage(
                layouts = listOf(ImportTransaction.StagedLayout("l1", dir, hash)),
            )
        }
        result as ImportTransaction.Result.Imported
        assertEquals(1, result.layouts[0].generation)
        assertTrue(File(f.layout.layoutGenDir("l1", 1), "layout.json").isFile)
        assertEquals("layouts/l1/gen-1", f.index().layouts[0].dir)
    }

    @Test
    fun parallelSameIdImports_bothSucceedWithDistinctGenerations() = runBlocking {
        val f = Fixture()
        try {
            repeat(20) { round ->
                val id = "race-$round"
                val barrier = CyclicBarrier(2)
                SkinFaultHooks.handler = { point ->
                    if (point == SkinFaultHooks.Point.I2) barrier.await(30, TimeUnit.SECONDS)
                }
                val a = async(Dispatchers.Default) {
                    f.tx.importPackage { staging ->
                        ImportTransaction.BuiltPackage(
                            skins = listOf(f.stageSkin(staging, id, salt = "v1")),
                        )
                    }
                }
                val b = async(Dispatchers.Default) {
                    f.tx.importPackage { staging ->
                        ImportTransaction.BuiltPackage(
                            skins = listOf(f.stageSkin(staging, id, salt = "v2")),
                        )
                    }
                }
                val ra = a.await()
                val rb = b.await()
                assertTrue("round $round a failed: $ra", ra is ImportTransaction.Result.Imported)
                assertTrue("round $round b failed: $rb", rb is ImportTransaction.Result.Imported)
                val gens = listOf(
                    (ra as ImportTransaction.Result.Imported).skins[0].generation,
                    (rb as ImportTransaction.Result.Imported).skins[0].generation,
                ).sorted()
                assertEquals("round $round generations", listOf(1, 2), gens)
                assertEquals(2, f.index().skins.first { it.id == id }.generation)
                assertTrue(File(f.layout.skinGenDir(id, 1), "skin.json").isFile)
                assertTrue(File(f.layout.skinGenDir(id, 2), "skin.json").isFile)
            }
        } finally {
            SkinFaultHooks.handler = {}
            f.root.deleteRecursively()
        }
    }

    @Test
    fun parallelDifferentIdImports_bothEntriesKept() = runBlocking {
        val f = Fixture()
        try {
            repeat(20) { round ->
                val barrier = CyclicBarrier(2)
                SkinFaultHooks.handler = { point ->
                    if (point == SkinFaultHooks.Point.I2) barrier.await(30, TimeUnit.SECONDS)
                }
                val a = async(Dispatchers.Default) {
                    f.tx.importPackage { staging ->
                        ImportTransaction.BuiltPackage(
                            skins = listOf(f.stageSkin(staging, "dx-$round", salt = "v1")),
                        )
                    }
                }
                val b = async(Dispatchers.Default) {
                    f.tx.importPackage { staging ->
                        ImportTransaction.BuiltPackage(
                            skins = listOf(f.stageSkin(staging, "dy-$round", salt = "v1")),
                        )
                    }
                }
                val ra = a.await()
                val rb = b.await()
                assertTrue("round $round a failed: $ra", ra is ImportTransaction.Result.Imported)
                assertTrue("round $round b failed: $rb", rb is ImportTransaction.Result.Imported)
                val ids = f.index().skins.map { it.id }.toSet()
                assertTrue("round $round missing dx-$round in $ids", ids.contains("dx-$round"))
                assertTrue("round $round missing dy-$round in $ids", ids.contains("dy-$round"))
            }
        } finally {
            SkinFaultHooks.handler = {}
            f.root.deleteRecursively()
        }
    }

    @Test
    fun cancelBeforeRename_leavesLibraryUntouchedAndRethrows() = runBlocking {
        // 取消在 rename 前（I2 注入）：库零变化，取消重抛给调用方，
        // 不再像旧口径那样被 catch (Exception) 吞成 Failed。
        val f = Fixture()
        SkinFaultHooks.handler = { point ->
            if (point == SkinFaultHooks.Point.I2) {
                throw CancellationException("injected cancel before rename")
            }
        }
        try {
            val deferred = async(Dispatchers.Default) {
                f.tx.importPackage { staging ->
                    val asset = f.stageAsset(staging, byteArrayOf(1))
                    val skin = f.stageSkin(staging, "s1", assetShas = listOf(asset.sha256))
                    ImportTransaction.BuiltPackage(skins = listOf(skin), assets = listOf(asset))
                }
            }
            val error = runCatching { deferred.await() }.exceptionOrNull()
            assertTrue(error is CancellationException)
            // 索引从未落盘（取消在写索引前）：f.index() 在 Missing 时
            // 给空索引，不可直接 as Ok。
            val index = f.index()
            assertTrue(index.skins.isEmpty())
            assertTrue(index.assets.isEmpty())
            assertFalse(File(f.layout.skinsDir, "s1").exists())
        } finally {
            SkinFaultHooks.handler = {}
            f.root.deleteRecursively()
        }
    }

    @Test
    fun cancelAfterRenameBeforeIndexCommit_rollsBackAndRethrows() = runBlocking {
        // 目录 rename 后、索引提交前取消（I4 注入）：重读索引确认
        // 未提交，已 rename 的目标回滚删除，取消重抛。
        val f = Fixture()
        SkinFaultHooks.handler = { point ->
            if (point == SkinFaultHooks.Point.I4) {
                throw CancellationException("injected cancel after rename, before index commit")
            }
        }
        try {
            val deferred = async(Dispatchers.Default) {
                f.tx.importPackage { staging ->
                    val asset = f.stageAsset(staging, byteArrayOf(1))
                    val skin = f.stageSkin(staging, "s1", assetShas = listOf(asset.sha256))
                    ImportTransaction.BuiltPackage(skins = listOf(skin), assets = listOf(asset))
                }
            }
            val error = runCatching { deferred.await() }.exceptionOrNull()
            assertTrue(error is CancellationException)
            // 索引从未落盘（取消在写索引前）：f.index() 在 Missing 时
            // 给空索引，不可直接 as Ok。
            val index = f.index()
            assertTrue(index.skins.isEmpty())
            assertTrue(index.assets.isEmpty())
            // 回滚删的是已 rename 的目标（代次目录与资产文件）；
            // mkdirs() 建的空父目录 skins/s1/ 会留下，与旧通用
            // catch 的回滚口径一致（无害，对账忽略空目录）。
            assertFalse(f.layout.skinGenDir("s1", 1).exists())
            assertTrue(f.layout.assetsDir.listFiles().isNullOrEmpty())
        } finally {
            SkinFaultHooks.handler = {}
            f.root.deleteRecursively()
        }
    }

    @Test
    fun cancelAfterAtomicReplace_keepsCommittedIndexAndRethrows() = runBlocking {
        // 窄窗口：取消在 AtomicFiles rename/fsync 后、writeIndex
        // 返回前请求。提交边界（NonCancellable）使“I4 后、写索引
        // 前请求的取消”与“写索引挂起期间请求的取消”走同一投递点
        // （边界后 ensureActive）：写必跑完、stage 已是 post_index，
        // 重读索引确认已提交，索引引用的目录与资产均存在且可读，
        // 不删不回滚、取消重抛。故在 I4 后立即取消即覆盖该窗口，
        // 无须轮询 tmp 文件（轮询在满载下会错过数毫秒写窗口）。
        // 索引垫大（2000 条假条目）只为拉长 I4 到返回的时间，
        // 让 I4 后立即取消在满载下也有充足余量。
        val f = Fixture()
        f.tx.importPackage { staging ->
            val asset = f.stageAsset(staging, byteArrayOf(0))
            val skin = f.stageSkin(staging, "s0", assetShas = listOf(asset.sha256))
            ImportTransaction.BuiltPackage(skins = listOf(skin), assets = listOf(asset))
        }
        val base = f.index()
        val bulk = (0 until 2000).map { i ->
            SkinIndexEntry("bulk-$i", "bulk-$i", "hash-$i", 1, "skins/bulk-$i/gen-1", 10L, 0L)
        }
        f.store.writeIndex(base.copy(skins = base.skins + bulk))
        val atI4 = CountDownLatch(1)
        SkinFaultHooks.handler = { point ->
            if (point == SkinFaultHooks.Point.I4) atI4.countDown()
        }
        var s1AssetSha: String? = null
        try {
            val deferred = async(Dispatchers.Default) {
                f.tx.importPackage { staging ->
                    val asset = f.stageAsset(staging, byteArrayOf(7, 7, 7))
                    s1AssetSha = asset.sha256
                    val skin = f.stageSkin(staging, "s1", assetShas = listOf(asset.sha256))
                    ImportTransaction.BuiltPackage(skins = listOf(skin), assets = listOf(asset))
                }
            }
            assertTrue("import did not reach I4", atI4.await(30, TimeUnit.SECONDS))
            // I4 后立即取消：此时 rename 已做、写索引尚未开始或正
            // 在进行；提交边界保证写跑完、取消在边界后投递。
            deferred.cancel()
            val error = runCatching { deferred.await() }.exceptionOrNull()
            assertTrue(error is CancellationException)
            val index = f.index()
            val entry = index.skins.singleOrNull { it.id == "s1" }
            assertTrue(entry != null)
            val genDir = File(f.layout.root, entry!!.dir)
            assertTrue(File(genDir, "skin.json").isFile)
            assertTrue(File(genDir, "skin.json").readText().contains("\"s1\""))
            assertTrue(File(genDir, "manifest.json").isFile)
            val assetSha = s1AssetSha
            assertTrue(assetSha != null)
            assertTrue(index.assets.any { it.sha256 == assetSha })
            val assetFile = f.layout.assetFile(assetSha!!, "png")
            assertTrue(assetFile.isFile)
            assertTrue(assetFile.readBytes().contentEquals(byteArrayOf(7, 7, 7)))
        } finally {
            SkinFaultHooks.handler = {}
            f.root.deleteRecursively()
        }
    }
}
