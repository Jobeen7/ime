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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除口径：先生效回退、Last-Good 清理、布局引用全量列出后可删。 */
class SkinLibraryAdminTest {

    private class Fixture(
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val admin: SkinLibraryAdmin,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-admin").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        return Fixture(layout, store, SkinLibraryAdmin(layout, store))
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
}
