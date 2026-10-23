package com.jobeen.ime.skin.draft

import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 布局草稿提交（B3 详设 §4.5）：校验→事务入库→新代次；未变跳过。 */
class LayoutDraftCommitterTest {

    private class Fixture(
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val committer: LayoutDraftCommitter,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("layout-commit").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        return Fixture(
            layout, store,
            LayoutDraftCommitter(ImportTransaction(layout, store)),
        )
    }

    private fun layoutJson(name: String = "L"): ByteArray =
        ("""{"formatVersion":3,"meta":{"id":"lay-1","name":"$name"},""" +
            """"rows":[{"keys":[{"stableId":"1","label":"q",""" +
            """"tap":{"type":"text","value":"q"}}]}]}""").encodeToByteArray()

    @Test
    fun commitCreatesFirstGeneration() = runBlocking {
        val f = fixture()
        val result = f.committer.commit("lay-1", layoutJson())
        assertEquals(LayoutDraftCommitter.Result.Committed("lay-1", 1), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.layouts.single().generation)
        assertTrue(File(f.layout.layoutGenDir("lay-1", 1), "layout.json").isFile)
        assertTrue(File(f.layout.layoutGenDir("lay-1", 1), "manifest.json").isFile)
    }

    @Test
    fun commitSameBytesIsNoChanges() = runBlocking {
        val f = fixture()
        f.committer.commit("lay-1", layoutJson())
        val second = f.committer.commit("lay-1", layoutJson())
        assertEquals(LayoutDraftCommitter.Result.NoChanges, second)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.layouts.single().generation)
    }

    @Test
    fun commitChangedBytesBumpsGeneration() = runBlocking {
        val f = fixture()
        f.committer.commit("lay-1", layoutJson("L1"))
        val result = f.committer.commit("lay-1", layoutJson("L2"))
        assertEquals(LayoutDraftCommitter.Result.Committed("lay-1", 2), result)
    }

    @Test
    fun invalidLayoutIsRefusedBeforeTransaction() = runBlocking {
        val f = fixture()
        val bad = ("""{"formatVersion":3,"meta":{"id":"lay-1","name":"L"},""" +
            """"rows":[]}""").encodeToByteArray()
        val result = f.committer.commit("lay-1", bad)
        assertEquals(LayoutDraftCommitter.Result.Failed("layout_invalid"), result)
        // 库零变化：索引从未落盘
        assertTrue(f.store.readIndex() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun unparseableFails() = runBlocking {
        val f = fixture()
        val result = f.committer.commit("lay-1", "{oops".encodeToByteArray())
        assertEquals(LayoutDraftCommitter.Result.Failed("unparseable"), result)
    }

    @Test
    fun layoutDraftKeyspaceCoexistsWithSkinDrafts() = runBlocking {
        // 布局草稿与皮肤草稿共用草稿区：key 前缀隔离，皮肤侧过滤后互不可见
        val f = fixture()
        val draftStore = DraftStore(f.layout)
        val layoutTree = com.jobeen.ime.skin.model.SkinJson.parseObject(
            layoutJson().decodeToString(),
        )!!
        draftStore.create(
            DraftStore.layoutDraftKey("lay-1"), layoutTree,
            DraftStore.SOURCE_EXISTING, baseGeneration = 1,
        )
        val skinTree = com.jobeen.ime.skin.model.SkinJson.parseObject(
            """{"formatVersion":3,"meta":{"id":"skin-1","name":"S"}}""",
        )!!
        draftStore.create("skin-1", skinTree, DraftStore.SOURCE_EXISTING, baseGeneration = 0)
        val metas = draftStore.listMetas()
        assertEquals(2, metas.size)
        val skinDrafts = metas.filter {
            !it.skinId.startsWith(DraftStore.LAYOUT_DRAFT_KEY_PREFIX)
        }
        assertEquals(listOf("skin-1"), skinDrafts.map { it.skinId })
        // 布局草稿可按 key 恢复（进程死亡恢复面）
        val loaded = draftStore.load(DraftStore.layoutDraftKey("lay-1"))
        assertEquals(layoutTree, loaded?.tree)
    }

    @Test
    fun skinDraftAnchorRoundTrips() = runBlocking {
        val f = fixture()
        val draftStore = DraftStore(f.layout)
        val skinTree = com.jobeen.ime.skin.model.SkinJson.parseObject(
            """{"formatVersion":3,"meta":{"id":"skin-1","name":"S"},"layoutId":"lay-1"}""",
        )!!
        draftStore.create(
            "skin-1", skinTree, DraftStore.SOURCE_EXISTING, baseGeneration = 3,
            baseHash = "skin-hash",
            layoutAnchorId = "lay-1", layoutAnchorHash = "layout-hash",
        )
        val loaded = draftStore.load("skin-1")!!
        assertEquals("lay-1", loaded.meta.layoutAnchorId)
        assertEquals("layout-hash", loaded.meta.layoutAnchorHash)
        // save 不动锚（锚是立稿时快照）
        val saved = draftStore.save("skin-1", skinTree)!!
        assertEquals("layout-hash", saved.meta.layoutAnchorHash)
    }
}
