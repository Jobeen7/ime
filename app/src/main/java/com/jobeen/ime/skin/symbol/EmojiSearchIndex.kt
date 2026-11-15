// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Emoji 搜索索引（WO52 7.2，新增能力——对端静态证据倾向不存在，
 * 做出来是新能力不是对齐）：
 *
 * - 数据：`assets/data/emoji_index.json`，9 组全量 1925 条（与
 *   `Symbol.Emoji` 一一对应），字段：名称／关键词／拼音／简繁＋英文；
 *   生成脚本见 `~/workspace/system/wo52-g4-gen-emoji-index.py`
 *   （中文名／关键词取 Unicode CLDR 注释，拼音由 pypinyin 生成，
 *   繁体由内置简繁表转换）；
 * - 搜索：简／繁中文名与关键词、英文名与关键词、拼音（全拼含查询
 *   或首字母前缀）——与对端 0.1.0(55) 宣称口径一致；
 * - 本文件纯逻辑（解析＋检索），不碰 View；资产缺失／损坏时
 *   解析回空表，搜索恒回空（面板退回分类浏览，不崩）。
 */
data class EmojiIndexEntry(
    val emoji: String,
    val group: Int,
    val name: String,
    val traditionalName: String,
    val englishName: String,
    val keywords: List<String>,
    val traditionalKeywords: List<String>,
    val englishKeywords: List<String>,
    val pinyin: String,
    val initials: String,
    /** 关键词拼音（与 [keywords] 一一对应，检索用；见生成脚本注释）。 */
    val keywordPinyins: List<String> = emptyList(),
)

object EmojiSearchIndex {

    /** 索引资产路径。 */
    const val ASSET_PATH = "data/emoji_index.json"

    /** 搜索结果上限（面板网格一次喂全量，超限截断防卡顿）。 */
    const val MAX_RESULTS = 200

    /** 解析索引 JSON：非法／损坏时回空表（不抛）。 */
    fun parse(jsonText: String): List<EmojiIndexEntry> {
        if (jsonText.isBlank()) return emptyList()
        return try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(jsonText) as? JsonArray
                ?: return emptyList()
            arr.mapNotNull { parseEntry(it as? JsonObject) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseEntry(obj: JsonObject?): EmojiIndexEntry? {
        if (obj == null) return null
        val emoji = obj.string("e")?.takeIf { it.isNotEmpty() } ?: return null
        return EmojiIndexEntry(
            emoji = emoji,
            group = obj.int("g") ?: 0,
            name = obj.string("n").orEmpty(),
            traditionalName = obj.string("t").orEmpty(),
            englishName = obj.string("en").orEmpty(),
            keywords = obj.strings("k"),
            traditionalKeywords = obj.strings("kt"),
            englishKeywords = obj.strings("ek"),
            pinyin = obj.string("p").orEmpty(),
            initials = obj.string("i").orEmpty(),
            keywordPinyins = obj.strings("kp"),
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strings(name: String): List<String> =
        (this[name] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }

    /** 从资产加载索引：缺失／损坏时回空表。 */
    fun loadFromAssets(context: Context): List<EmojiIndexEntry> {
        return try {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
                parse(reader.readText())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * C-L-24：首次加载的后台化入口——读资产＋全量 JSON 解析
     * （644KB）在 Dispatchers.Default 上跑，调用方在 UI 线程
     * await；返回后调用方缓存到 searchIndex，二次查询不再加载。
     * load 参数即 loadFromAssets 的调用，便于主机单测探针线程。
     */
    suspend fun loadAsync(load: () -> List<EmojiIndexEntry>): List<EmojiIndexEntry> =
        withContext(Dispatchers.Default) { load() }

    /**
     * 检索：空查询回空表；命中任一字段即入选（按索引顺序、去重、
     * 截断 [MAX_RESULTS]）：
     * - Emoji 本身、简／繁中文名与关键词、英文名与关键词（含子串）；
     * - 拼音：全拼含查询（去空格后），或首字母以查询开头。
     */
    fun search(entries: List<EmojiIndexEntry>, query: String): List<String> {
        val q = query.trim().lowercase().replace(" ", "")
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (entry in entries) {
            if (matches(entry, q)) {
                if (entry.emoji !in out) out += entry.emoji
                if (out.size >= MAX_RESULTS) break
            }
        }
        return out
    }

    private fun matches(entry: EmojiIndexEntry, q: String): Boolean {
        if (entry.emoji.contains(q)) return true
        if (entry.name.contains(q) || entry.traditionalName.contains(q)) return true
        if (entry.keywords.any { it.contains(q) }) return true
        if (entry.traditionalKeywords.any { it.contains(q) }) return true
        if (entry.englishName.replace(" ", "").contains(q)) return true
        if (entry.englishKeywords.any { it.replace(" ", "").contains(q) }) return true
        if (q.all { it in 'a'..'z' }) {
            if (entry.pinyin.isNotEmpty() && entry.pinyin.contains(q)) return true
            if (entry.initials.isNotEmpty() && entry.initials.startsWith(q)) return true
            if (entry.keywordPinyins.any { it.isNotEmpty() && it.contains(q) }) return true
        }
        return false
    }
}
