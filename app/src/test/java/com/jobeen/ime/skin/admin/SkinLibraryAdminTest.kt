package com.jobeen.ime.skin.admin

import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.LastGoodPointer
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除口径：先生效回退、Last-Good 清理、布局引用全量列出后可删。 */
class SkinLibraryAdminTest {

    /**
     * 可注入失败的 store：除 writeIndex 可按需抛错外，其余直通
     * 真实 store（fault injection 用；真实 store 是 final 类，
     * 无法子类化伪造写失败，见 SkinLibraryAdmin.AdminStore）。
     */
    private class FakeAdminStore(
        private val real: SkinLibraryStore,
    ) : SkinLibraryAdmin.AdminStore {
        var failNextWrite = false

        override suspend fun readIndex(): SkinLibraryStore.ReadResult<LibraryIndex> =
            real.readIndex()

        override suspend fun readActive(): SkinLibraryStore.ReadResult<ActiveRecord> =
            real.readActive()

        override suspend fun readLastGood(): SkinLibraryStore.ReadResult<LastGoodPointer> =
            real.readLastGood()

        override suspend fun writeIndex(index: LibraryIndex) {
            if (failNextWrite) {
                failNextWrite = false
                throw java.io.IOException("injected index write failure")
            }
            real.writeIndex(index)
        }

        override suspend fun clearLastGood() = real.clearLastGood()
    }

    private class Fixture(
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val admin: SkinLibraryAdmin,
        val fake: FakeAdminStore,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-admin").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val fake = FakeAdminStore(store)
        return Fixture(layout, store, SkinLibraryAdmin(layout, fake), fake)
    }

    /** tombstone 残留：手造一次崩溃后的中间态（rename 已做、索引未提交）。 */
    private fun plantTombstone(
        f: Fixture,
        kind: String,
        id: String,
        withIntent: Boolean = true,
    ): File {
        val source = if (kind == "skin") File(f.layout.skinsDir, id) else File(f.layout.layoutsDir, id)
        val tombstone = File(File(f.layout.root, "trash"), "$kind-$id-123456789").apply { mkdirs() }
        val moved = source.renameTo(File(tombstone, if (kind == "skin") "skin" else "layout"))
        check(moved) { "plant tombstone failed" }
        if (withIntent) {
            File(tombstone, "delete-intent.json").writeText(
                """{"kind":"$kind","id":"$id","atMs":123}""",
            )
        }
        return tombstone
    }

    private fun trashChildren(f: Fixture): List<File> =
        File(f.layout.root, "trash").listFiles()?.toList().orEmpty()

    /** 异步 tombstone 清理：轮询等 trash 空（上限 10 秒）。 */
    private fun awaitTrashEmpty(f: Fixture) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (trashChildren(f).isEmpty()) return
            Thread.sleep(50)
        }
        throw AssertionError("trash not cleaned: ${trashChildren(f).map { it.name }}")
    }

    private suspend fun addSkin(
        f: Fixture,
        id: String,
        hash: String,
        layoutId: String? = null,
    ) {
        val genDir = f.layout.skinGenDir(id, 1).apply { mkdirs() }
        val layoutRef = layoutId?.let { ""","layoutId":"$it"""" } ?: ""
        File(genDir, "skin.json").writeText(
            """{"formatVersion":3,"meta":{"id":"$id","name":"$id"}$layoutRef}""",
        )
        File(genDir, "manifest.json").writeText("""{"formatVersion":3,"contents":[]}""")
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        f.store.writeIndex(
            index.copy(
                skins = index.skins + SkinIndexEntry(
                    id, id, hash, 1, "skins/$id/gen-1", 10L, 0L,
                ),
            ),
        )
    }

    @Test
    fun deletePlainSkinRemovesEntryAndDirs() = runBlocking {
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
    }

    @Test
    fun deleteActiveSkinRevertsFirstAndClearsLastGood() = runBlocking {
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        var reverted = false
        val refused = f.admin.deleteSkin("s1") { false }
        assertEquals(SkinLibraryAdmin.Result.Refused("revert_failed"), refused)
        val ok = f.admin.deleteSkin("s1") {
            reverted = true
            // 模拟事务回退后 active 已清空
            f.store.writeActive(ActiveRecord(appliedAt = 0L))
            true
        }
        assertTrue(reverted)
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), ok)
        val lastGood = f.store.readLastGood()
        assertTrue(lastGood is SkinLibraryStore.ReadResult.Missing)
    }

    private suspend fun addLayout(f: Fixture, id: String) {
        val layoutGen = f.layout.layoutGenDir(id, 1).apply { mkdirs() }
        File(layoutGen, "layout.json").writeText("""{"rows":[]}""")
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        f.store.writeIndex(
            index.copy(
                layouts = index.layouts + LayoutIndexEntry(
                    id, "lhash-$id", 1, "layouts/$id/gen-1", 5L, 0L,
                ),
            ),
        )
    }

    @Test
    fun layoutReferrersListsAllReferencingSkins() = runBlocking {
        val f = fixture()
        addLayout(f, "lay-1")
        addLayout(f, "lay-2")
        addSkin(f, "s1", "hash1", layoutId = "lay-1")
        addSkin(f, "s2", "hash2", layoutId = "lay-1")
        addSkin(f, "s3", "hash3", layoutId = "lay-2")
        addSkin(f, "s4", "hash4")
        val referrers = f.admin.layoutReferrers("lay-1")
        assertEquals(listOf("s1", "s2"), referrers.map { it.skinId })
        assertEquals(listOf("s3"), f.admin.layoutReferrers("lay-2").map { it.skinId })
        assertTrue(f.admin.layoutReferrers("lay-none").isEmpty())
    }

    @Test
    fun deleteReferencedLayoutNowSucceedsAndKeepsSkinFiles() = runBlocking {
        // B3 §4.6 新口径（取代旧的硬拒绝）：列出→警告→可删→引用方回退
        val f = fixture()
        addLayout(f, "lay-1")
        addSkin(f, "s1", "hash1", layoutId = "lay-1")
        val result = f.admin.deleteLayout("lay-1")
        assertEquals(SkinLibraryAdmin.Result.Done("lay-1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.layouts.isEmpty())
        assertFalse(File(f.layout.layoutsDir, "lay-1").exists())
        // 引用方皮肤文件原样保留（代次不可变；绑定靠重解析自然悬空回退）
        assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        assertEquals(1, index.skins.size)
    }

    @Test
    fun deleteLayoutRefusals() = runBlocking {
        val f = fixture()
        // 索引未落盘 → index_unavailable；空索引 → not_found
        assertEquals(
            SkinLibraryAdmin.Result.Refused("index_unavailable"),
            f.admin.deleteLayout("nope"),
        )
        f.store.writeIndex(LibraryIndex())
        assertEquals(
            SkinLibraryAdmin.Result.Refused("not_found"), f.admin.deleteLayout("nope"),
        )
    }

    // ---- 审计 #1-5：tombstone 删除事务与崩溃恢复 ----

    @Test
    fun deleteSkin_indexWriteFailure_restoresDirAndKeepsIndex() = runBlocking {
        // 崩溃点 2（索引提交失败）：目录必须原样回来、索引不动、
        // tombstone 不留；调用方拿到 Refused 而非删除后抛异常。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.fake.failNextWrite = true
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Refused("index_write_failed"), result)
        val genDir = f.layout.skinGenDir("s1", 1)
        assertTrue(File(genDir, "skin.json").isFile)
        assertTrue(File(genDir, "manifest.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("s1"), index.skins.map { it.id })
        assertTrue(trashChildren(f).isEmpty())
    }

    @Test
    fun deleteLayout_indexWriteFailure_restoresDirAndKeepsIndex() = runBlocking {
        val f = fixture()
        addLayout(f, "lay-1")
        f.fake.failNextWrite = true
        val result = f.admin.deleteLayout("lay-1")
        assertEquals(SkinLibraryAdmin.Result.Refused("index_write_failed"), result)
        assertTrue(File(f.layout.layoutGenDir("lay-1", 1), "layout.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("lay-1"), index.layouts.map { it.id })
        assertTrue(trashChildren(f).isEmpty())
    }

    @Test
    fun deleteSkin_success_cleansTombstoneAsync() = runBlocking {
        val f = fixture()
        addSkin(f, "s1", "hash1")
        // 连带草稿一并进 tombstone、随索引提交清理
        File(f.layout.draftsDir, "s1").mkdirs()
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        assertFalse(File(f.layout.draftsDir, "s1").exists())
        awaitTrashEmpty(f)
    }

    @Test
    fun deleteSkin_missingDirStillRemovesIndexEntry() = runBlocking {
        // 目录已先行消失（对账/外部清理）：删除仍提交索引，
        // 与旧 deleteRecursively 空操作口径一致。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        File(f.layout.skinsDir, "s1").deleteRecursively()
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
    }

    @Test
    fun deleteSkin_withRealStore_forwardsThroughAdapter() = runBlocking {
        // 真实 store 直通适配（RealAdminStore）：生产构造路径
        // 覆盖全部 5 个 store 方法（读索引/读 active/读 lastGood/
        // 写索引/清 lastGood），防接口委托增量陷阱。
        val root = Files.createTempDirectory("jime-admin-real").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val admin = SkinLibraryAdmin(layout, store)
        val genDir = layout.skinGenDir("s1", 1).apply { mkdirs() }
        File(genDir, "skin.json").writeText(
            """{"formatVersion":3,"meta":{"id":"s1","name":"s1"}}""",
        )
        File(genDir, "manifest.json").writeText("""{"formatVersion":3,"contents":[]}""")
        store.writeIndex(
            LibraryIndex(
                skins = listOf(
                    SkinIndexEntry("s1", "s1", "hash1", 1, "skins/s1/gen-1", 10L, 0L),
                ),
            ),
        )
        store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        val result = admin.deleteSkin("s1") {
            store.writeActive(ActiveRecord(appliedAt = 0L))
            true
        }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        assertTrue(store.readLastGood() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun recoverPendingDeletes_rollsBackUncommittedDelete() = runBlocking {
        // 崩溃点 1（rename 后、索引提交前被杀）：tombstone 在、
        // 旧索引仍指向该 id → 恢复目录名，删除如同没发生。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        f.admin.recoverPendingDeletes()
        assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("s1"), index.skins.map { it.id })
        assertFalse(tombstone.exists())
    }

    @Test
    fun recoverPendingDeletes_rollsBackWithoutIntentFile() = runBlocking {
        // intent 没落盘：按目录名 `<kind>-<id>-<nanoTime>` 回退解析，
        // 同样回滚，不留不可裁决的孤儿。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1", withIntent = false)
        f.admin.recoverPendingDeletes()
        assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        assertFalse(tombstone.exists())
    }

    @Test
    fun recoverPendingDeletes_completesCommittedDelete() = runBlocking {
        // 崩溃点 3（索引提交后、tombstone 清理前被杀）：新索引
        // 已不含该 id → 删 tombstone，继续完成删除。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        f.store.writeIndex(LibraryIndex())
        f.admin.recoverPendingDeletes()
        assertFalse(tombstone.exists())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
    }

    @Test
    fun recoverPendingDeletes_staleTombstoneDoesNotClobberNewSkin() = runBlocking {
        // 崩溃后同 id 被重新导入：索引仍指向该 id、目标目录已
        // 存在 → tombstone 是过期残留，直接清理，不覆盖新目录。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        val genDir = f.layout.skinGenDir("s1", 1).apply { mkdirs() }
        File(genDir, "skin.json").writeText(
            """{"formatVersion":3,"meta":{"id":"s1","name":"new"}}""",
        )
        File(genDir, "manifest.json").writeText("""{"formatVersion":3,"contents":[]}""")
        f.admin.recoverPendingDeletes()
        assertFalse(tombstone.exists())
        assertEquals(
            "new",
            File(genDir, "skin.json").readText().substringAfter("\"name\":\"").substringBefore("\""),
        )
    }

    @Test
    fun recoverPendingDeletes_layoutRollback() = runBlocking {
        val f = fixture()
        addLayout(f, "lay-1")
        val tombstone = plantTombstone(f, "layout", "lay-1")
        f.admin.recoverPendingDeletes()
        assertTrue(File(f.layout.layoutGenDir("lay-1", 1), "layout.json").isFile)
        assertFalse(tombstone.exists())
    }

    @Test
    fun recoverPendingDeletes_restoreFailure_keepsTombstone() = runBlocking {
        // 回滚搬回失败时 tombstone 必须保留（不得删未归位的数据），
        // 下次恢复再试；索引仍指向该 id。写权限恢复后回滚成功。
        // 以 Assume 跳过 root 用户环境（chmod 对 root 无效）。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        val skinsDir = f.layout.skinsDir
        try {
            skinsDir.setWritable(false)
            org.junit.Assume.assumeTrue(
                "restore failure needs a non-writable skins dir",
                !skinsDir.canWrite(),
            )
            f.admin.recoverPendingDeletes()
            assertTrue(tombstone.exists())
            assertTrue(File(tombstone, "skin").exists())
            val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
            assertEquals(listOf("s1"), index.skins.map { it.id })
            skinsDir.setWritable(true)
            f.admin.recoverPendingDeletes()
            assertFalse(tombstone.exists())
            assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        } finally {
            skinsDir.setWritable(true)
        }
    }

    @Test
    fun deleteSkin_renameFailure_refusesAndKeepsEverything() = runBlocking {
        // rename 失败（源目录不可写）：索引与目录都不动。
        // 以 Assume 跳过 root 用户环境（chmod 对 root 无效）。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val skinsDir = f.layout.skinsDir
        try {
            skinsDir.setWritable(false)
            org.junit.Assume.assumeTrue(
                "rename failure needs a non-writable skins dir",
                !skinsDir.canWrite(),
            )
            val result = f.admin.deleteSkin("s1") { true }
            assertEquals(SkinLibraryAdmin.Result.Refused("rename_failed"), result)
            assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
            val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
            assertEquals(listOf("s1"), index.skins.map { it.id })
        } finally {
            skinsDir.setWritable(true)
        }
    }

    @Test
    fun concurrentDeletes_doNotResurrectEachOther() = runBlocking {
        // 旧读-改-写竞态：两次并发删除互相覆盖写回、复活对方条目。
        // 删除串行化后两者都提交，索引最终为空。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        addSkin(f, "s2", "hash2")
        val first = async { f.admin.deleteSkin("s1") { true } }
        val second = async { f.admin.deleteSkin("s2") { true } }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), first.await())
        assertEquals(SkinLibraryAdmin.Result.Done("s2"), second.await())
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
    }
}
