package com.jobeen.ime.skin.store

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态文件读写层：原子写、缺失/损坏区分（详设 §2.2/§2.4）。 */
class SkinLibraryStoreTest {

    private fun newStore(): Pair<SkinLibraryLayout, SkinLibraryStore> {
        val root = Files.createTempDirectory("skin-store").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        return layout to SkinLibraryStore(layout)
    }

    @Test
    fun indexRoundTrip() = runBlocking {
        val (_, store) = newStore()
        val index = LibraryIndex(
            skins = listOf(
                SkinIndexEntry("s1", "One", "h".repeat(64), 3, "skins/s1/gen-3", 1234, 99),
            ),
            assets = listOf(AssetIndexEntry("a".repeat(64), 55, 2)),
        )
        store.writeIndex(index)
        val read = store.readIndex()
        assertTrue(read is SkinLibraryStore.ReadResult.Ok)
        assertEquals(index, (read as SkinLibraryStore.ReadResult.Ok).value)
    }

    @Test
    fun activeAndLastGoodRoundTrip() = runBlocking {
        val (_, store) = newStore()
        val active = ActiveRecord("s1", "b".repeat(64), 2, ApplyState.PENDING, 123)
        store.writeActive(active)
        assertEquals(
            active,
            (store.readActive() as SkinLibraryStore.ReadResult.Ok).value,
        )
        val pointer = LastGoodPointer("s1", "b".repeat(64), 2)
        store.writeLastGood(pointer)
        assertEquals(
            pointer,
            (store.readLastGood() as SkinLibraryStore.ReadResult.Ok).value,
        )
        store.clearLastGood()
        assertTrue(store.readLastGood() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun missingAndCorruptAreDistinct() = runBlocking {
        val (layout, store) = newStore()
        assertTrue(store.readIndex() is SkinLibraryStore.ReadResult.Missing)
        layout.indexFile.writeText("{not valid json")
        assertTrue(store.readIndex() is SkinLibraryStore.ReadResult.Corrupt)
        // A-5 等价：active.json 被截断 → Corrupt（走回退链而非崩溃）
        layout.activeFile.writeText("{\"skinId\":")
        assertTrue(store.readActive() is SkinLibraryStore.ReadResult.Corrupt)
    }

    @Test
    fun atomicWriteLeavesNoTmpAndReplaces() {
        val dir = Files.createTempDirectory("atomic").toFile()
        AtomicFiles.writeAtomically(dir, "index.json", "first")
        assertEquals("first", File(dir, "index.json").readText())
        AtomicFiles.writeAtomically(dir, "index.json", "second-longer")
        assertEquals("second-longer", File(dir, "index.json").readText())
        assertFalse(File(dir, "index.json.tmp").exists())
    }

    @Test
    fun nullSkinActiveSerializesExplicitly() = runBlocking {
        val (layout, store) = newStore()
        store.writeActive(ActiveRecord())
        val text = layout.activeFile.readText()
        assertTrue(text.contains("\"skinId\": null"))
        val read = (store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(null, read.skinId)
    }
}
