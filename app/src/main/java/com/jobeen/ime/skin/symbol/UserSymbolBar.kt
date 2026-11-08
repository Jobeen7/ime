// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 用户自定义常用符号栏（施工单 10 H2-5，CyIME R11）：
 *
 * - 皮肤 SymbolBar 只有皮肤定义、无用户自定义入口；本栏是用户级
 *   覆盖：用户自填常用符号列＋置顶开关，存在偏好里，不碰皮肤文件。
 * - 置顶语义：置顶开且符号列非空时，该列固定在符号面板最顶部
 *   （皮肤栏／子条／横条之上）；置顶关或列空时不显示。
 * - 本文件纯逻辑＋偏好读写，不碰 View；单测直接覆盖。
 */
data class UserSymbolBarConfig(
    val symbols: List<String> = emptyList(),
    val pinned: Boolean = true,
)

object UserSymbolBarOps {

    /** 自定义符号上限（条内一格一符号，超限截断）。 */
    const val MAX_SYMBOLS = 20

    /**
     * 归一化：逐项去空白、空项丢弃、重复取首次出现、上限截断。
     * 顺序即用户添加顺序（条内显示顺序与之一致）。
     */
    fun normalize(symbols: List<String>): List<String> {
        val out = ArrayList<String>(symbols.size)
        for (raw in symbols) {
            val symbol = raw.trim()
            if (symbol.isEmpty()) continue
            if (symbol in out) continue
            out += symbol
            if (out.size >= MAX_SYMBOLS) break
        }
        return out
    }

    /** 添加：空白／重复／已满时原样返回（调用方不写盘）。 */
    fun add(config: UserSymbolBarConfig, symbol: String): UserSymbolBarConfig {
        val next = normalize(config.symbols + symbol)
        return if (next == config.symbols) config else config.copy(symbols = next)
    }

    /** 删除指定下标：越界原样返回。 */
    fun removeAt(config: UserSymbolBarConfig, index: Int): UserSymbolBarConfig {
        if (index !in config.symbols.indices) return config
        val next = config.symbols.toMutableList().also { it.removeAt(index) }
        return config.copy(symbols = next)
    }

    /** 移动指定下标到目标下标：任一下标越界或同位时原样返回。 */
    fun move(config: UserSymbolBarConfig, fromIndex: Int, toIndex: Int): UserSymbolBarConfig {
        val symbols = config.symbols
        if (fromIndex !in symbols.indices || toIndex !in symbols.indices) return config
        if (fromIndex == toIndex) return config
        val next = symbols.toMutableList()
        val item = next.removeAt(fromIndex)
        next.add(toIndex, item)
        return config.copy(symbols = next)
    }

    fun setPinned(config: UserSymbolBarConfig, pinned: Boolean): UserSymbolBarConfig =
        if (config.pinned == pinned) config else config.copy(pinned = pinned)

    /**
     * 置顶生效条：置顶开且符号列非空时返回该列，否则 null（不显示）。
     * 调用方只在非 null 时建条。
     */
    fun topStrip(config: UserSymbolBarConfig): List<String>? =
        if (config.pinned && config.symbols.isNotEmpty()) config.symbols else null

    fun serialize(config: UserSymbolBarConfig): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("pinned", JsonPrimitive(config.pinned))
                put("symbols", JsonArray(config.symbols.map { JsonPrimitive(it) }))
            },
        )

    /** 解析：损坏／非对象／字段缺失时回落默认（空列、置顶开），未知字段容忍。 */
    fun parse(raw: String?): UserSymbolBarConfig {
        if (raw.isNullOrBlank()) return UserSymbolBarConfig()
        return try {
            val root = Json.parseToJsonElement(raw) as? JsonObject
                ?: return UserSymbolBarConfig()
            val pinned = (root["pinned"] as? JsonPrimitive)?.booleanOrNull ?: true
            val symbols = (root["symbols"] as? JsonArray)
                ?.mapNotNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                }
                .orEmpty()
            UserSymbolBarConfig(symbols = normalize(symbols), pinned = pinned)
        } catch (e: Exception) {
            UserSymbolBarConfig()
        }
    }
}

/** 用户自定义符号栏存储：加载失败即默认（空列、置顶开）。 */
interface UserSymbolBarStore {
    fun load(): UserSymbolBarConfig
    fun save(config: UserSymbolBarConfig)
}

/** 内存实现（默认；单测用）。 */
class InMemoryUserSymbolBarStore(
    initial: UserSymbolBarConfig = UserSymbolBarConfig(),
) : UserSymbolBarStore {
    private var stored = initial
    override fun load(): UserSymbolBarConfig = stored
    override fun save(config: UserSymbolBarConfig) {
        stored = config
    }
}

/**
 * 偏好实现：与符号栏类型记忆共用 `symbol_bar` 偏好文件（键不同），
 * 配置以 JSON 串存一键。
 */
class SharedPrefsUserSymbolBarStore(context: Context) : UserSymbolBarStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): UserSymbolBarConfig =
        UserSymbolBarOps.parse(prefs.getString(KEY_CONFIG, null))

    override fun save(config: UserSymbolBarConfig) {
        prefs.edit().putString(KEY_CONFIG, UserSymbolBarOps.serialize(config)).apply()
    }

    companion object {
        private const val PREFS_NAME = "symbol_bar"
        private const val KEY_CONFIG = "user_symbols"
    }
}
