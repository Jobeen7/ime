package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动对账（详设 §2.8 + §2.9 文件级等价场景）：I-3 索引未更新、
 * I-4 索引损坏、A-2 启动见 pending、A-5 active 截断、A-7 代次被删、
 * 代次回收、引用计数重建与孤儿标记。
 */
class LibraryReconcilerTest {

    private class Fixture {
        val root: File = Files.createTempDirectory("skin-reconcile").toFile()
        val layout = SkinLibraryLayout(root)
        val store = SkinLibraryStore(layout)
        val reconciler = LibraryReconciler(layout, store) { 1_000L }

        init {
            layout.ensureDirs()
        }

        /** 在盘上造一代完整皮肤，返回其 skin.json 内容 hash。 */
        fun skinGen(id: String, gen: Int, name: String = "Skin-$id", assets: List<String> = emptyList()): String {
            val dir = layout.skinGenDir(id, gen)
            dir.mkdirs()
            val skinJson = """{"meta":{"id":"$id","name":"$name","version":"1.0"},"styles":{}}"""
            File(dir, "skin.json").writeText(skinJson)
            val hash = DigestUtil.sha256Hex(skinJson.toByteArray())
            val assetEntries = assets.joinToString(",") {
                """{"type":"asset","id":"a-$it","path":"assets/$it.png","sha256":"$it"}"""
            }
            val contents = """{"type":"skin","id":"$id","path":"skin.json","sha256":"$hash"}""" +
                (if (assetEntries.isNotEmpty()) ",$assetEntries" else "")
            File(dir, "manifest.json").writeText("""{"version":1,"contents":[$contents]}""")
            return hash
        }

        fun skinGenIncomplete(id: String, gen: Int) {
            val dir = layout.skinGenDir(id, gen)
            dir.mkdirs()
            File(dir, "skin.json").writeText("{}")
            // 无 manifest.json → 不完整
        }

        suspend fun indexEntry(id: String): SkinIndexEntry? =
            (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value?.skins?.firstOrNull { it.id == id }

        suspend fun active(): ActiveRecord =
            (store.readActive() as SkinLibraryStore.ReadResult.Ok).value
    }

    @Test
    fun emptyLibraryConverges() = runBlocking {
        val f = Fixture()
        val report = f.reconciler.reconcile()
        assertFalse(report.indexRebuilt)
        assertTrue(report.activeResolution is ActiveResolver.Resolution.NoSkin)
        assertEquals(0, report.droppedEntries.size)
        assertTrue(f.layout.indexFile.isFile)
    }

    @Test
    fun orphanGenDirIsRegistered_I3() = runBlocking {
        val f = Fixture()
        val hash = f.skinGen("s1", 2, name = "Ocean")
        val report = f.reconciler.reconcile()
        assertTrue(report.registeredEntries.contains("skin:s1"))
        val entry = f.indexEntry("s1")!!
        assertEquals(2, entry.generation)
        assertEquals(hash, entry.hash)
        assertEquals("Ocean", entry.name)
        assertEquals("skins/s1/gen-2", entry.dir)
    }

    @Test
    fun entryWithMissingDirIsDropped() = runBlocking {
        val f = Fixture()
        f.store.writeIndex(
            LibraryIndex(
                skins = listOf(
                    SkinIndexEntry("ghost", "G", "h".repeat(64), 1, "skins/ghost/gen-1", 10, 1),
                ),
            ),
        )
        val report = f.reconciler.reconcile()
        assertTrue(report.droppedEntries.contains("skin:ghost"))
        assertEquals(null, f.indexEntry("ghost"))
    }

    @Test
    fun incompleteGenIsPrunedNotRegistered() = runBlocking {
        val f = Fixture()
        f.skinGenIncomplete("s1", 1)
        val report = f.reconciler.reconcile()
        assertEquals(null, f.indexEntry("s1"))
        assertTrue(report.prunedGenerations.contains("skin:s1/gen-1"))
        assertFalse(f.layout.skinGenDir("s1", 1).exists())
    }

    @Test
    fun corruptIndexIsRebuiltFromDisk_I4() = runBlocking {
        val f = Fixture()
        val hash = f.skinGen("s1", 1)
        f.layout.indexFile.writeText("{broken")
        val report = f.reconciler.reconcile()
        assertTrue(report.indexRebuilt)
        assertEquals(hash, f.indexEntry("s1")!!.hash)
    }

    @Test
    fun pendingActiveRollsBackToLastGood_A2() = runBlocking {
        val f = Fixture()
        val hash1 = f.skinGen("s1", 1)
        val hash2 = f.skinGen("s1", 2)
        f.store.writeLastGood(LastGoodPointer("s1", hash1, 1))
        f.store.writeActive(ActiveRecord("s1", hash2, 2, ApplyState.PENDING, 5))
        val report = f.reconciler.reconcile()
        val resolution = report.activeResolution
        assertTrue(resolution is ActiveResolver.Resolution.RollbackToLastGood)
        val active = f.active()
        assertEquals(1, active.generation)
        assertEquals(hash1, active.hash)
        assertEquals(ApplyState.VERIFIED, active.state)
        // gen-2 是最新代次、gen-1 是 last-good：两代都保留
        assertTrue(f.layout.skinGenDir("s1", 2).exists())
        assertTrue(f.layout.skinGenDir("s1", 1).exists())
    }

    @Test
    fun pendingActiveRollsBackToLastGood_otherSkinsUntouched() = runBlocking {
        // 启动场景 1 的多皮肤版：s1 待回退、s2 已验证——回退只动 s1，
        // 不扰动其它皮肤的索引与代次（与单皮肤 A2 区分）。
        val f = Fixture()
        val hash1 = f.skinGen("s1", 1)
        val hash2 = f.skinGen("s1", 2)
        val hashS2 = f.skinGen("s2", 1)
        f.store.writeLastGood(LastGoodPointer("s1", hash1, 1))
        f.store.writeActive(ActiveRecord("s1", hash2, 2, ApplyState.PENDING, 5))
        val report = f.reconciler.reconcile()
        assertTrue(report.activeResolution is ActiveResolver.Resolution.RollbackToLastGood)
        val active = f.active()
        assertEquals(1, active.generation)
        assertEquals(hash1, active.hash)
        assertEquals(ApplyState.VERIFIED, active.state)
        assertEquals(hashS2, f.indexEntry("s2")!!.hash)
        assertTrue(f.layout.skinGenDir("s2", 1).exists())
    }

    @Test
    fun pendingActiveWithCorruptLastGoodDefaults() = runBlocking {
        // 启动场景 2：PENDING + 损坏的 Last-Good 文件——指针非 Ok，
        // 按 RollbackToDefault 回到无皮肤，不崩、不把库当空库
        // （磁盘代次仍登记在索引内）。
        val f = Fixture()
        f.skinGen("s1", 1)
        val hash2 = f.skinGen("s1", 2)
        f.layout.lastGoodFile.writeText("{broken")
        f.store.writeActive(ActiveRecord("s1", hash2, 2, ApplyState.PENDING, 5))
        val report = f.reconciler.reconcile()
        assertTrue(report.activeResolution is ActiveResolver.Resolution.RollbackToDefault)
        assertEquals(null, f.active().skinId)
        assertTrue(f.indexEntry("s1") != null)
    }

    @Test
    fun corruptIndexRebuiltFromDisk_multiSkin() = runBlocking {
        // 启动场景 3 的多皮肤版：索引损坏、磁盘代次完整——从磁盘
        // 重建后两皮肤均登记（s2 取最新代次），且不自动生效任一皮肤
        // （与单皮肤 I4 区分；无 active 记录时对账不凭空写一条，
        // 故此处断言 Missing 而非空 skinId）。
        val f = Fixture()
        val hashS1 = f.skinGen("s1", 1)
        f.skinGen("s2", 1)
        val hashS2Gen2 = f.skinGen("s2", 2)
        f.layout.indexFile.writeText("{broken")
        val report = f.reconciler.reconcile()
        assertTrue(report.indexRebuilt)
        assertEquals(hashS1, f.indexEntry("s1")!!.hash)
        assertEquals(hashS2Gen2, f.indexEntry("s2")!!.hash)
        assertTrue(report.activeResolution is ActiveResolver.Resolution.NoSkin)
        assertTrue(f.store.readActive() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun oldGenerationsAreRecycled() = runBlocking {
        val f = Fixture()
        f.skinGen("s1", 1)
        val hash2 = f.skinGen("s1", 2)
        val hash3 = f.skinGen("s1", 3)
        f.store.writeLastGood(LastGoodPointer("s1", hash2, 2))
        f.store.writeActive(ActiveRecord("s1", hash3, 3, ApplyState.VERIFIED, 5))
        val report = f.reconciler.reconcile()
        // 保留：生效 gen-3 + Last-Good gen-2（同时最新为 gen-3）；gen-1 回收
        assertTrue(report.prunedGenerations.contains("skin:s1/gen-1"))
        assertFalse(f.layout.skinGenDir("s1", 1).exists())
        assertTrue(f.layout.skinGenDir("s1", 2).exists())
        assertTrue(f.layout.skinGenDir("s1", 3).exists())
        assertEquals(hash3, f.indexEntry("s1")!!.hash)
    }

    @Test
    fun verifiedActiveWithDeletedGenRollsBack_A7() = runBlocking {
        val f = Fixture()
        val hash1 = f.skinGen("s1", 1)
        f.store.writeLastGood(LastGoodPointer("s1", hash1, 1))
        // active 指向已不存在的 gen-2
        f.store.writeActive(ActiveRecord("s1", "e".repeat(64), 2, ApplyState.VERIFIED, 5))
        val report = f.reconciler.reconcile()
        assertTrue(report.activeResolution is ActiveResolver.Resolution.RollbackToLastGood)
        assertEquals(1, f.active().generation)
    }

    @Test
    fun verifiedActiveWithDeletedGenAndNoLastGoodDefaults_A7() = runBlocking {
        val f = Fixture()
        f.skinGen("s1", 1)
        f.store.writeActive(ActiveRecord("s1", "e".repeat(64), 2, ApplyState.VERIFIED, 5))
        val report = f.reconciler.reconcile()
        assertTrue(report.activeResolution is ActiveResolver.Resolution.RollbackToDefault)
        assertEquals(null, f.active().skinId)
    }

    @Test
    fun corruptActiveResetsToNoSkin_A5() = runBlocking {
        val f = Fixture()
        f.skinGen("s1", 1)
        f.layout.activeFile.writeText("{\"skinId\":")
        val report = f.reconciler.reconcile()
        assertTrue(report.activeResolution is ActiveResolver.Resolution.NoSkin)
        assertEquals(null, f.active().skinId)
    }

    @Test
    fun stagingResidueIsCleared_I1() = runBlocking {
        val f = Fixture()
        val junk = File(f.layout.stagingDir, "uuid-1/partial.bin")
        junk.parentFile?.mkdirs()
        junk.writeText("x")
        f.reconciler.reconcile()
        assertFalse(junk.exists())
        assertTrue(f.layout.stagingDir.isDirectory)
    }

    @Test
    fun invalidLastGoodPointerIsCleared() = runBlocking {
        val f = Fixture()
        f.skinGen("s1", 1)
        f.store.writeLastGood(LastGoodPointer("s1", "f".repeat(64), 9))
        f.reconciler.reconcile()
        assertTrue(f.store.readLastGood() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun assetRefcountsRebuiltAndOrphansMarked() = runBlocking {
        val f = Fixture()
        val shaA = "a".repeat(64)
        val shaB = "b".repeat(64)
        val shaC = "c".repeat(64)
        f.skinGen("s1", 1, assets = listOf(shaA, shaB))
        File(f.layout.assetsDir, "$shaA.png").writeBytes(byteArrayOf(1, 2, 3))
        File(f.layout.assetsDir, "$shaB.png").writeBytes(byteArrayOf(4))
        File(f.layout.assetsDir, "$shaC.png").writeBytes(byteArrayOf(5, 6))
        val report = f.reconciler.reconcile()
        val assets = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value.assets
        assertEquals(1, assets.first { it.sha256 == shaA }.refcountCache)
        assertEquals(1, assets.first { it.sha256 == shaB }.refcountCache)
        assertEquals(0, assets.first { it.sha256 == shaC }.refcountCache)
        assertEquals(listOf(shaC), report.orphanAssets)
        // 异步回收：删实体并摘除索引条目
        val deleted = f.reconciler.pruneOrphanAssets(report.orphanAssets)
        assertEquals(1, deleted)
        assertFalse(File(f.layout.assetsDir, "$shaC.png").exists())
        val after = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value.assets
        assertTrue(after.none { it.sha256 == shaC })
    }

    @Test
    fun quotaReflectsTreeBytes() = runBlocking {
        val f = Fixture()
        f.skinGen("s1", 1)
        val report = f.reconciler.reconcile()
        assertTrue(report.quotaBytes > 0)
        assertFalse(report.overQuota)
        assertEquals(f.layout.quotaBytes(), report.quotaBytes)
    }
}
