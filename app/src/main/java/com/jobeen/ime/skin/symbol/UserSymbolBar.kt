// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context
import com.jobeen.ime.data.PunctuationMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 用户自定义常用符号栏（施工单 10 H2-5，CyIME R11；2026-11-12 符号栏重做）：
 *
 * - 皮肤 SymbolBar 只有皮肤定义、无用户自定义入口；本栏是用户级
 *   覆盖：用户自填常用符号列，存在偏好里，不碰皮肤文件。
 * - 列表即左侧竖栏显示内容：设置页改了，竖栏下次打开符号面板即跟变。
 * - 置顶已删（2026-11-12）：新设计里列表本身就是面板那排符号，
 *   没东西可置顶；旧数据里的 pinned 字段解析时直接忽略。
 * - 默认 16 符号：首次使用（无保存记录）即带，不再是空表。
 * - 本文件纯逻辑＋偏好读写，不碰 View；单测直接覆盖。
 */
data class UserSymbolBarConfig(
    val symbols: List<String> = emptyList(),
)

object UserSymbolBarOps {

    /** 自定义符号上限（条内一格一符号，超限截断）。 */
    const val MAX_SYMBOLS = 20

    /** 默认 16 符号（首次使用即带；顺序即竖栏显示顺序）。 */
    val DEFAULT_SYMBOLS: List<String> = listOf(
        "，", "。", "？", "！", "…", "～", "、", "=", "+",
        "-", "*", "/", "%", "#", "@", "&",
    )

    /** 默认配置（16 默认符号）。 */
    fun defaultConfig(): UserSymbolBarConfig =
        UserSymbolBarConfig(symbols = DEFAULT_SYMBOLS)

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

    /**
     * 删一项；删到空即恢复默认 16 符号（WO30 §3(7)，与 parse 的
     * 空列口径一致）：删空不留空列表，设置页删最后一项后立刻回默认。
     */
    fun removeAtOrRestoreDefault(config: UserSymbolBarConfig, index: Int): UserSymbolBarConfig {
        val next = removeAt(config, index)
        return if (next.symbols.isEmpty()) defaultConfig() else next
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

    fun serialize(config: UserSymbolBarConfig): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("symbols", JsonArray(config.symbols.map { JsonPrimitive(it) }))
            },
        )

    /**
     * 解析：无记录／损坏／非对象／已存空列时回落默认 16 符号；旧数据里的
     * pinned 字段直接忽略（不读、不报错）；未知字段容忍。
     * 已存的空符号列视为已删空，恢复默认（WO30 §3(7)，不留空列表）。
     */
    fun parse(raw: String?): UserSymbolBarConfig {
        if (raw.isNullOrBlank()) return defaultConfig()
        return try {
            val root = Json.parseToJsonElement(raw) as? JsonObject
                ?: return defaultConfig()
            val symbols = (root["symbols"] as? JsonArray)
                ?.mapNotNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                }
                .orEmpty()
            val normalized = normalize(symbols)
            if (normalized.isEmpty()) defaultConfig() else UserSymbolBarConfig(symbols = normalized)
        } catch (e: Exception) {
            defaultConfig()
        }
    }

    /**
     * 按标点模式换算符号栏显示／提交的符号（WO30 §3(9)）：
     * 半角模式出半角标点，全角模式出全角标点；非标点原样。
     * 换算口径用 PunctuationMode.CharacterSet（与模式系统同款）；
     * 「…」↔「...」按九宫格旧全角／半角标点表配对（T9Keyboard 旧
     * punctuations）；「、」无单字符半角形，两模式都原样。
     */
    fun displaySymbol(symbol: String, mode: PunctuationMode): String {
        return when (mode) {
            PunctuationMode.HalfWidth -> when (symbol) {
                "…" -> "..."
                else -> PunctuationMode.CharacterSet.halfWidth(symbol)
            }
            PunctuationMode.FullWidth -> when (symbol) {
                "..." -> "…"
                else -> PunctuationMode.CharacterSet.fullWidth(symbol)
            }
        }
    }

    /**
     * 左竖栏显示／提交列（WO30 §3(9) 续）：逐项按标点模式换算，顺序
     * 不变；九宫格竖栏与符号面板竖栏共用本函数，显示与提交同一列。
     * 「、」两模式都原样（无单字符半角形）；非标点原样。
     */
    fun sidePanelSymbols(symbols: List<String>, mode: PunctuationMode): List<String> =
        symbols.map { displaySymbol(it, mode) }
}

/** 用户自定义符号栏存储：加载失败即默认 16 符号。 */
interface UserSymbolBarStore {
    fun load(): UserSymbolBarConfig
    fun save(config: UserSymbolBarConfig)
}

/** 内存实现（默认即 16 默认符号，与偏好实现首 load 一致；单测用）。 */
class InMemoryUserSymbolBarStore(
    initial: UserSymbolBarConfig = UserSymbolBarOps.defaultConfig(),
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
