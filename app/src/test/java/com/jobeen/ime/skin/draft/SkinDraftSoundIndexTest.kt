package com.jobeen.ime.skin.draft

import com.jobeen.ime.skin.store.SkinLibraryLayout
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿音效索引（B2 §7.1-3）：逻辑 id → sha256 认领表的往返与
 * 损坏容错（索引坏了按空表，绝不阻塞编辑器）。
 */
class SkinDraftSoundIndexTest {

    private fun newStore(): Pair<DraftStore, SkinLibraryLayout> {
        val root = Files.createTempDirectory("jime-draft-snd").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        return DraftStore(layout) to layout
    }

    @Test
    fun indexRoundTrip() {
        val (store, _) = newStore()
        assertTrue(store.readDraftSoundIndex("skin-a").isEmpty())
        store.writeDraftSoundIndex(
            "skin-a",
            mapOf("snd_pop" to "a".repeat(64), "snd_tick" to "b".repeat(64)),
        )
        assertEquals(
            mapOf("snd_pop" to "a".repeat(64), "snd_tick" to "b".repeat(64)),
            store.readDraftSoundIndex("skin-a"),
        )
        // 索引文件不得被当作草稿资产文件（asset- 前缀过滤面之外）
        assertTrue(store.draftAssetFiles("skin-a").isEmpty())
    }

    @Test
    fun corruptIndexReadsAsEmpty() {
        val (store, layout) = newStore()
        val dir = File(layout.draftsDir, "skin-b").apply { mkdirs() }
        File(dir, DraftStore.SOUND_INDEX_FILE).writeText("{not json")
        assertTrue(store.readDraftSoundIndex("skin-b").isEmpty())
        // 非对象/非字符串值同样容错
        File(dir, DraftStore.SOUND_INDEX_FILE).writeText("""{"a": 3}""")
        assertTrue(store.readDraftSoundIndex("skin-b").isEmpty())
    }
}
