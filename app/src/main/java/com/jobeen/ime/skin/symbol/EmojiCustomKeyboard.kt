// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context
import com.jobeen.ime.data.Symbol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 用户自定义 Emoji 键盘（WO52 7.4，新增能力——元书静态层面不存在，
 * 做出来是新能力不是对齐）：
 *
 * - 用户级（与皮肤 `subClassifiedSymbols` 覆盖分开）：分组／顺序／
 *   内容存偏好（`emoji` 文件，键 `customKeyboard`），有存档时面板
 *   改用自定义分组（见 [EmojiBoardBuilder]），无存档时走内置／皮肤板；
 * - 编辑起点：首次打开即内置 9 组内容（可改可删），不从空表起步；
 * - 本文件纯逻辑＋偏好读写，不碰 View；单测直接覆盖。
 */
data class EmojiCustomGroup(
    val label: String,
    val symbols: List<String>,
)

data class EmojiCustomConfig(
    val groups: List<EmojiCustomGroup>,
)

object EmojiCustomOps {

    /** 分组上限。 */
    const val MAX_GROUPS = 20

    /** 每组符号上限。 */
    const val MAX_SYMBOLS_PER_GROUP = 200

    const val PREFS_NAME = "emoji"

    /** 存储键。 */
    const val KEY_CUSTOM_KEYBOARD = "customKeyboard"

    /** 编辑起点：内置 9 组原样（标签＋内容）。 */
    fun seedGroups(): List<EmojiCustomGroup> =
        Symbol.Emoji.map { (category, symbols) ->
            EmojiCustomGroup(category.label, symbols.toList())
        }

    fun defaultConfig(): EmojiCustomConfig = EmojiCustomConfig(seedGroups())

    /** 归一化：组标签去空白、空标签组丢弃、组内去空去重、上限截断；空分组保留（新建后待填符号）；无分组即无配置。 */
    fun normalize(groups: List<EmojiCustomGroup>): List<EmojiCustomGroup> {
        val out = ArrayList<EmojiCustomGroup>(groups.size)
        for (group in groups) {
            val label = group.label.trim()
            if (label.isEmpty()) continue
            val symbols = ArrayList<String>(group.symbols.size)
            for (symbol in group.symbols) {
                val item = symbol.trim()
                if (item.isEmpty()) continue
                if (item in symbols) continue
                symbols += item
                if (symbols.size >= MAX_SYMBOLS_PER_GROUP) break
            }
            out += EmojiCustomGroup(label, symbols)
            if (out.size >= MAX_GROUPS) break
        }
        return out
    }

    /** 新建分组：空名／重名／已满时原样返回。 */
    fun addGroup(config: EmojiCustomConfig, label: String): EmojiCustomConfig {
        val name = label.trim()
        if (name.isEmpty()) return config
        if (config.groups.any { it.label == name }) return config
        if (config.groups.size >= MAX_GROUPS) return config
        return config.copy(groups = config.groups + EmojiCustomGroup(name, emptyList()))
    }

    /** 改分组名：空名／重名／越界时原样返回。 */
    fun renameGroup(config: EmojiCustomConfig, index: Int, label: String): EmojiCustomConfig {
        if (index !in config.groups.indices) return config
        val name = label.trim()
        if (name.isEmpty()) return config
        if (config.groups.withIndex().any { (i, g) -> i != index && g.label == name }) {
            return config
        }
        if (config.groups[index].label == name) return config
        val groups = config.groups.toMutableList()
        groups[index] = groups[index].copy(label = name)
        return config.copy(groups = groups)
    }

    /** 删分组：越界原样返回；删到空即无配置（调用方按 null 存盘）。 */
    fun deleteGroup(config: EmojiCustomConfig, index: Int): EmojiCustomConfig {
        if (index !in config.groups.indices) return config
        val groups = config.groups.toMutableList().also { it.removeAt(index) }
        return config.copy(groups = groups)
    }

    /** 移分组：任一下标越界或同位时原样返回。 */
    fun moveGroup(config: EmojiCustomConfig, fromIndex: Int, toIndex: Int): EmojiCustomConfig {
        val groups = config.groups
        if (fromIndex !in groups.indices || toIndex !in groups.indices) return config
        if (fromIndex == toIndex) return config
        val next = groups.toMutableList()
        val item = next.removeAt(fromIndex)
        next.add(toIndex, item)
        return config.copy(groups = next)
    }

    /** 加符号：空白／重复／已满／越界时原样返回。 */
    fun addSymbol(config: EmojiCustomConfig, groupIndex: Int, symbol: String): EmojiCustomConfig {
        if (groupIndex !in config.groups.indices) return config
        val item = symbol.trim()
        if (item.isEmpty()) return config
        val group = config.groups[groupIndex]
        if (item in group.symbols) return config
        if (group.symbols.size >= MAX_SYMBOLS_PER_GROUP) return config
        val groups = config.groups.toMutableList()
        groups[groupIndex] = group.copy(symbols = group.symbols + item)
        return config.copy(groups = groups)
    }

    /** 删符号：越界原样返回。 */
    fun removeSymbol(
        config: EmojiCustomConfig,
        groupIndex: Int,
        symbolIndex: Int,
    ): EmojiCustomConfig {
        if (groupIndex !in config.groups.indices) return config
        val group = config.groups[groupIndex]
        if (symbolIndex !in group.symbols.indices) return config
        val symbols = group.symbols.toMutableList().also { it.removeAt(symbolIndex) }
        val groups = config.groups.toMutableList()
        groups[groupIndex] = group.copy(symbols = symbols)
        return config.copy(groups = groups)
    }

    /** 移符号：任一下标越界或同位时原样返回。 */
    fun moveSymbol(
        config: EmojiCustomConfig,
        groupIndex: Int,
        fromIndex: Int,
        toIndex: Int,
    ): EmojiCustomConfig {
        if (groupIndex !in config.groups.indices) return config
        val symbols = config.groups[groupIndex].symbols
        if (fromIndex !in symbols.indices || toIndex !in symbols.indices) return config
        if (fromIndex == toIndex) return config
        val next = symbols.toMutableList()
        val item = next.removeAt(fromIndex)
        next.add(toIndex, item)
        val groups = config.groups.toMutableList()
        groups[groupIndex] = config.groups[groupIndex].copy(symbols = next)
        return config.copy(groups = groups)
    }

    fun serialize(config: EmojiCustomConfig): String =
        kotlinx.serialization.json.Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("groups", JsonArray(config.groups.map { group ->
                    buildJsonObject {
                        put("label", JsonPrimitive(group.label))
                        put(
                            "symbols",
                            JsonArray(group.symbols.map { JsonPrimitive(it) }),
                        )
                    }
                }))
            },
        )

    /**
     * 解析：无记录／损坏／非对象／空表时回 null（＝无自定义，走内置／皮肤板）。
     */
    fun parse(raw: String?): EmojiCustomConfig? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
                ?: return null
            val groups = (root["groups"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val label = (obj["label"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                val symbols = (obj["symbols"] as? JsonArray).orEmpty().mapNotNull { item ->
                    (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                }
                EmojiCustomGroup(label, symbols)
            }
            val normalized = normalize(groups)
            if (normalized.isEmpty()) null else EmojiCustomConfig(normalized)
        } catch (e: Exception) {
            null
        }
    }
}

/** 用户自定义 Emoji 键盘存储：null＝无自定义（走内置／皮肤板）。 */
interface EmojiCustomStore {
    fun load(): EmojiCustomConfig?
    fun save(config: EmojiCustomConfig?)
}

/** 内存实现（单测用）。 */
class InMemoryEmojiCustomStore(
    initial: EmojiCustomConfig? = null,
) : EmojiCustomStore {
    private var stored = initial
    override fun load(): EmojiCustomConfig? = stored
    override fun save(config: EmojiCustomConfig?) {
        stored = config
    }
}

/** 偏好实现：与最近使用共用 `emoji` 偏好文件（键不同）。 */
class SharedPrefsEmojiCustomStore(context: Context) : EmojiCustomStore {

    private val prefs = context.getSharedPreferences(
        EmojiCustomOps.PREFS_NAME, Context.MODE_PRIVATE,
    )

    override fun load(): EmojiCustomConfig? =
        EmojiCustomOps.parse(prefs.getString(EmojiCustomOps.KEY_CUSTOM_KEYBOARD, null))

    override fun save(config: EmojiCustomConfig?) {
        val editor = prefs.edit()
        if (config == null || config.groups.isEmpty()) {
            editor.remove(EmojiCustomOps.KEY_CUSTOM_KEYBOARD)
        } else {
            editor.putString(
                EmojiCustomOps.KEY_CUSTOM_KEYBOARD,
                EmojiCustomOps.serialize(config),
            )
        }
        editor.apply()
    }
}
