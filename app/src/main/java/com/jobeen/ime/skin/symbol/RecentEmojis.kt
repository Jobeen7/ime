// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Emoji 最近使用（WO52 7.1）：
 *
 * - 键值存储（SharedPreferences `emoji`，键 `recentEmojis`），非 Room；
 * - 点一个 Emoji 即记一次（最新在前、去重）；上限未载明，
 *   默认 30 条——Jime 自定（单子已标明）；
 * - 最近使用拼到 `smileysAndPeople` 组（内置首组 😀）前面，
 *   拼法见 [EmojiBoardBuilder]；重置入口文案“重置常用 Emoji”。
 * 本文件纯逻辑＋偏好读写，不碰 View；单测直接覆盖。
 */
object RecentEmojiOps {

    /** 最近使用上限（Jime 自定：施工单未载明，默认 30 条）。 */
    const val MAX_RECENT = 30

    const val PREFS_NAME = "emoji"

    /** 存储键：与元书 `recentEmojis` 同名。 */
    const val KEY_RECENT_EMOJIS = "recentEmojis"

    /**
     * 记一次：新项放首、旧位置去重、超上限截断；空串原样返回。
     * 顺序即最近使用顺序（首项最新）。
     */
    fun record(recents: List<String>, emoji: String): List<String> {
        if (emoji.isEmpty()) return recents
        val next = ArrayList<String>(recents.size + 1)
        next += emoji
        for (item in recents) {
            if (item != emoji) next += item
            if (next.size >= MAX_RECENT) break
        }
        return next
    }

    /** 重置：清空即空表（调用方清盘）。 */
    fun reset(): List<String> = emptyList()

    /** 归一化：去空、去重（取首次出现）、上限截断。 */
    fun normalize(recents: List<String>): List<String> {
        val out = ArrayList<String>(recents.size)
        for (item in recents) {
            if (item.isEmpty()) continue
            if (item in out) continue
            out += item
            if (out.size >= MAX_RECENT) break
        }
        return out
    }

    fun serialize(recents: List<String>): String =
        JsonArray(recents.map { JsonPrimitive(it) }).toString()

    /** 解析：无记录／损坏／非数组时回空表（不抛）。 */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonArray
                ?: return emptyList()
            normalize(arr.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            })
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Emoji 最近使用存储：加载失败即空表。 */
interface RecentEmojiStore {
    fun load(): List<String>
    fun save(recents: List<String>)
    fun clear()
}

/** 内存实现（单测用）。 */
class InMemoryRecentEmojiStore(
    initial: List<String> = emptyList(),
) : RecentEmojiStore {
    private var stored = RecentEmojiOps.normalize(initial)
    override fun load(): List<String> = stored
    override fun save(recents: List<String>) {
        stored = RecentEmojiOps.normalize(recents)
    }

    override fun clear() {
        stored = emptyList()
    }
}

/** 偏好实现：`emoji` 偏好文件，一键存 JSON 数组串。 */
class SharedPrefsRecentEmojiStore(context: Context) : RecentEmojiStore {

    private val prefs = context.getSharedPreferences(
        RecentEmojiOps.PREFS_NAME, Context.MODE_PRIVATE,
    )

    override fun load(): List<String> =
        RecentEmojiOps.parse(prefs.getString(RecentEmojiOps.KEY_RECENT_EMOJIS, null))

    override fun save(recents: List<String>) {
        prefs.edit()
            .putString(
                RecentEmojiOps.KEY_RECENT_EMOJIS,
                RecentEmojiOps.serialize(recents),
            ).apply()
    }

    override fun clear() {
        prefs.edit().remove(RecentEmojiOps.KEY_RECENT_EMOJIS).apply()
    }
}
