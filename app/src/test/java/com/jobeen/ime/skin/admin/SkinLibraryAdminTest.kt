package com.jobeen.ime.skin.admin

import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.LastGoodPointer
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import com.jobeen.ime.skin.sync.SkinSyncState
import com.jobeen.ime.skin.sync.SkinSyncStateStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除口径：先生效回退、Last-Good 清理、墓碑、引用保护。 */
class SkinLibraryAdminTest {

    private class Fixture(
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val stateStore: SkinSyncStateStore,
        val admin: SkinLibraryAdmin,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-admin").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val stateStore = SkinSyncStateStore(layout)
        return Fixture(layout, store, stateStore, SkinLibraryAdmin(layout, store, stateStore))
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
    fun deletePlainSkinRemovesEntryDirsAndMarksDirty() = runBlocking {
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        assertTrue(f.stateStore.read().dirty)
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

    @Test
    fun deleteSyncedSkinRecordsTombstone() = runBlocking {
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.stateStore.write(SkinSyncState(units = mapOf("skin:s1" to "hash1")))
        f.admin.deleteSkin("s1") { true }
        val state = f.stateStore.read()
        assertEquals(1, state.tombstones.size)
        assertEquals("hash1", state.tombstones[0].hashAtDelete)
    }

    @Test
    fun deleteReferencedLayoutRefused() = runBlocking {
        val f = fixture()
        val layoutGen = f.layout.layoutGenDir("lay-1", 1).apply { mkdirs() }
        File(layoutGen, "layout.json").writeText("""{"rows":[]}""")
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        f.store.writeIndex(
            index.copy(
                layouts = listOf(
                    LayoutIndexEntry("lay-1", "lhash", 1, "layouts/lay-1/gen-1", 5L, 0L),
                ),
            ),
        )
        addSkin(f, "s1", "hash1", layoutId = "lay-1")
        val result = f.admin.deleteLayout("lay-1")
        assertEquals(
            SkinLibraryAdmin.Result.Refused("referenced_by:s1"), result,
        )
        // 摘掉引用后可删
        f.admin.deleteSkin("s1") { true }
        assertEquals(
            SkinLibraryAdmin.Result.Done("lay-1"), f.admin.deleteLayout("lay-1"),
        )
    }
}
