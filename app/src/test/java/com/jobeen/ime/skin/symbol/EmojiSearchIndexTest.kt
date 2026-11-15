// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 7.2：Emoji 搜索——索引解析／简繁英拼检索／9 组全量覆盖。 */
class EmojiSearchIndexTest {

    private fun smallIndex() = EmojiSearchIndex.parse(
        """
        [
          {"e":"😀","g":0,"n":"嘿嘿","t":"嘿嘿","en":"grinning face",
           "k":["嘿嘿","笑脸","脸","高兴"],"kt":["嘿嘿","笑臉","臉","高興"],
           "ek":["grin","smile"],"p":"heihei","i":"hh",
           "kp":["heihei","xiaolian","lian","gaoxing"]},
          {"e":"❤️","g":0,"n":"红心","t":"紅心","en":"red heart",
           "k":["心","爱","红心"],"kt":["心","愛","紅心"],
           "ek":["heart","love"],"p":"hongxin","i":"hx",
           "kp":["xin","ai","hongxin"]}
        ]
        """.trimIndent(),
    )

    @Test
    fun parse_smallJson_fieldsRoundTrip() {
        val entries = smallIndex()
        assertEquals(2, entries.size)
        assertEquals("😀", entries[0].emoji)
        assertEquals(0, entries[0].group)
        assertEquals("嘿嘿", entries[0].name)
        assertEquals("笑臉", entries[0].traditionalKeywords[1])
        assertEquals("grinning face", entries[0].englishName)
        assertEquals("xiaolian", entries[0].keywordPinyins[1])
    }

    @Test
    fun parse_corruptOrBlank_returnsEmpty() {
        assertTrue(EmojiSearchIndex.parse("").isEmpty())
        assertTrue(EmojiSearchIndex.parse("not json").isEmpty())
        assertTrue(EmojiSearchIndex.parse("{}").isEmpty())
        assertTrue(EmojiSearchIndex.parse("""[{"e":""}]""").isEmpty())
    }

    @Test
    fun search_simplifiedKeyword_hits() {
        assertTrue("😀" in EmojiSearchIndex.search(smallIndex(), "笑脸"))
    }

    @Test
    fun search_traditionalKeyword_hits() {
        assertTrue("😀" in EmojiSearchIndex.search(smallIndex(), "笑臉"))
        assertTrue("❤️" in EmojiSearchIndex.search(smallIndex(), "愛"))
    }

    @Test
    fun search_english_hits() {
        assertTrue("😀" in EmojiSearchIndex.search(smallIndex(), "grin"))
        assertTrue("❤️" in EmojiSearchIndex.search(smallIndex(), "heart"))
    }

    @Test
    fun search_pinyin_fullAndInitials_hit() {
        // 全拼：关键词“笑脸”→ xiaolian；名称“红心”→ hongxin。
        assertTrue("😀" in EmojiSearchIndex.search(smallIndex(), "xiaolian"))
        assertTrue("❤️" in EmojiSearchIndex.search(smallIndex(), "hongxin"))
        // 首字母：红心 hx。
        assertTrue("❤️" in EmojiSearchIndex.search(smallIndex(), "hx"))
    }

    @Test
    fun search_blankOrNoMatch_returnsEmpty() {
        assertTrue(EmojiSearchIndex.search(smallIndex(), "").isEmpty())
        assertTrue(EmojiSearchIndex.search(smallIndex(), "   ").isEmpty())
        assertTrue(EmojiSearchIndex.search(smallIndex(), "不存在的词").isEmpty())
    }

    @Test
    fun search_dedupesAndKeepsIndexOrder() {
        val entries = smallIndex() + smallIndex()
        val got = EmojiSearchIndex.search(entries, "脸")
        assertEquals(got.distinct(), got)
        assertEquals("😀", got.first())
    }

    // ---- 全量资产：9 组 1925 条，与 Symbol.Emoji 一一对应 ----

    private fun loadAsset(): List<EmojiIndexEntry> {
        val file = File("src/main/assets/data/emoji_index.json")
        assertTrue("索引资产缺失：${file.absolutePath}", file.exists())
        return EmojiSearchIndex.parse(file.readText(Charsets.UTF_8))
    }

    @Test
    fun asset_coversAllNineGroupsInFull() {
        val entries = loadAsset()
        assertEquals(1925, entries.size)
        val expectedCounts = listOf(170, 390, 155, 140, 220, 90, 265, 225, 270)
        expectedCounts.forEachIndexed { group, count ->
            assertEquals(
                "第 $group 组条数",
                count,
                entries.count { it.group == group },
            )
        }
        // 与内置表一一对应（集合口径；内置表本身含 5 处重复项，见下）。
        val builtin = Symbol.Emoji.flatMap { it.second.toList() }.toSet()
        val indexed = entries.map { it.emoji }.toSet()
        assertEquals(builtin, indexed)
    }

    @Test
    fun asset_spotCheckPerGroup_andPinyinRetrieval() {
        val entries = loadAsset()
        // 每组抽查：简体关键词命中该组的一个 Emoji。
        val spotChecks = listOf(
            0 to ("笑脸" to "😀"),
            1 to ("挥手" to "👋"),
            2 to ("猴" to "🐵"),
            3 to ("葡萄" to "🍇"),
            4 to ("地球" to "🌍"),
            5 to ("南瓜" to "🎃"),
            6 to ("眼镜" to "👓"),
            7 to ("提款机" to "🏧"),
            8 to ("旗" to "🏁"),
        )
        for ((group, keywordToEmoji) in spotChecks) {
            val (keyword, emoji) = keywordToEmoji
            val groupEntries = entries.filter { it.group == group }
            assertTrue(
                "第 $group 组搜“$keyword”应含 $emoji",
                emoji in EmojiSearchIndex.search(groupEntries, keyword),
            )
        }
        // 拼音检索（全量资产）：笑脸 xiaolian、红心 hongxin。
        assertTrue("😀" in EmojiSearchIndex.search(entries, "xiaolian"))
        assertTrue("❤️" in EmojiSearchIndex.search(entries, "hongxin"))
        // 繁体检索：笑臉。
        assertTrue("😀" in EmojiSearchIndex.search(entries, "笑臉"))
        // 英文检索：grinning。
        assertTrue("😀" in EmojiSearchIndex.search(entries, "grinning"))
    }

    @Test
    fun `C-L-25 english multi-word query hits both spaced and joined`() {
        val entries = smallIndex()
        assertTrue("❤️" in EmojiSearchIndex.search(entries, "red heart"))
        assertTrue("❤️" in EmojiSearchIndex.search(entries, "redheart"))
        assertTrue("😀" in EmojiSearchIndex.search(entries, "grinning face"))
        assertTrue("😀" in EmojiSearchIndex.search(entries, "grinningface"))
        // 中文／拼音照旧。
        assertTrue("❤️" in EmojiSearchIndex.search(entries, "红心"))
        assertTrue("❤️" in EmojiSearchIndex.search(entries, "hongxin"))
    }

    @Test
    fun `C-L-24 loadAsync runs off the calling thread`() = kotlinx.coroutines.runBlocking {
        val caller = Thread.currentThread().name
        var worker: String? = null
        val out = EmojiSearchIndex.loadAsync {
            worker = Thread.currentThread().name
            smallIndex()
        }
        assertEquals(2, out.size)
        assertTrue("解析应在 Default 线程，不在调用线程 $caller", worker != null && worker != caller)
        assertTrue("解析线程应为 DefaultDispatcher-worker，实际 $worker", worker!!.startsWith("DefaultDispatcher-worker"))
    }
}
