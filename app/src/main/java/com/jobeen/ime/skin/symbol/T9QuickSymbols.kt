// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * T9 快捷符号（9 键快捷符号槽，用户级覆盖，与 UserSymbolBar 同模式）：
 *
 * - 默认 6 符号：， 。 ？ ！ 、 …；上限 12 槽（弹窗单行放得下
 *   8 格左右，见 T9Keyboard 注释；9–12 格在窄屏上会被 PopupOverflow
 *   夹到屏内，边缘格需大滑动才能够到——已知缺口，用户仍可存 12 格）。
 * - 生效：T9 句号键长按弹窗（Popup.Keyboard），滑动选择后上屏；
 *   设置页改动后下次打开键盘生效（弹窗随布局一次建成，不跟随
 *   热刷新，与侧栏符号的即时刷新不同）。
 * - 提交原样提交（不按标点模式换算：弹窗随布局一次建成，模式在
 *   之后切换时无法重算；存什么出什么，默认全角符号与默认全角
 *   模式一致；半角模式用户可存半角符号）。
 * - 本文件纯逻辑＋偏好读写，不碰 View；单测直接覆盖。
 */
data class T9QuickSymbolsConfig(
    val symbols: List<String> = emptyList(),
)

object T9QuickSymbolsOps {

    /** 快捷符号上限（12 槽）。 */
    const val MAX_SLOTS = 12

    /** 默认 6 符号（首次使用即带；顺序即弹窗显示顺序）。 */
    val DEFAULT_SYMBOLS: List<String> = listOf(
        "，", "。", "？", "！", "、", "…",
    )

    /** 默认配置（6 默认符号）。 */
    fun defaultConfig(): T9QuickSymbolsConfig =
        T9QuickSymbolsConfig(symbols = DEFAULT_SYMBOLS)

    /**
     * 归一化：逐项去空白、空项丢弃、重复取首次出现、上限截断。
     * 顺序即用户添加顺序（弹窗显示顺序与之一致）。
     */
    fun normalize(symbols: List<String>): List<String> {
        val out = ArrayList<String>(symbols.size)
        for (raw in symbols) {
            val symbol = raw.trim()
            if (symbol.isEmpty()) continue
            if (symbol in out) continue
            out += symbol
            if (out.size >= MAX_SLOTS) break
        }
        return out
    }

    /** 添加：空白／重复／已满时原样返回（调用方不写盘）。 */
    fun add(config: T9QuickSymbolsConfig, symbol: String): T9QuickSymbolsConfig {
        val next = normalize(config.symbols + symbol)
        return if (next == config.symbols) config else config.copy(symbols = next)
    }

    /** 删除指定下标：越界原样返回。 */
    fun removeAt(config: T9QuickSymbolsConfig, index: Int): T9QuickSymbolsConfig {
        if (index !in config.symbols.indices) return config
        val next = config.symbols.toMutableList().also { it.removeAt(index) }
        return config.copy(symbols = next)
    }

    /**
     * 删一项；删到空即恢复默认 6 符号（与 UserSymbolBar 同口径）：
     * 删空不留空列表，设置页删最后一项后立刻回默认。
     */
    fun removeAtOrRestoreDefault(config: T9QuickSymbolsConfig, index: Int): T9QuickSymbolsConfig {
        val next = removeAt(config, index)
        return if (next.symbols.isEmpty()) defaultConfig() else next
    }

    /** 移动指定下标到目标下标：任一下标越界或同位时原样返回。 */
    fun move(config: T9QuickSymbolsConfig, fromIndex: Int, toIndex: Int): T9QuickSymbolsConfig {
        val symbols = config.symbols
        if (fromIndex !in symbols.indices || toIndex !in symbols.indices) return config
        if (fromIndex == toIndex) return config
        val next = symbols.toMutableList()
        val item = next.removeAt(fromIndex)
        next.add(toIndex, item)
        return config.copy(symbols = next)
    }

    fun serialize(config: T9QuickSymbolsConfig): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("symbols", JsonArray(config.symbols.map { JsonPrimitive(it) }))
            },
        )

    /**
     * 解析：无记录／损坏／非对象／已存空列时回落默认 6 符号；
     * 未知字段容忍。
     */
    fun parse(raw: String?): T9QuickSymbolsConfig {
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
            if (normalized.isEmpty()) defaultConfig() else T9QuickSymbolsConfig(symbols = normalized)
        } catch (e: Exception) {
            defaultConfig()
        }
    }
}

/** T9 快捷符号存储：加载失败即默认 6 符号。 */
interface T9QuickSymbolsStore {
    fun load(): T9QuickSymbolsConfig
    fun save(config: T9QuickSymbolsConfig)
}

/** 内存实现（默认即 6 默认符号，与偏好实现首 load 一致；单测用）。 */
class InMemoryT9QuickSymbolsStore(
    initial: T9QuickSymbolsConfig = T9QuickSymbolsOps.defaultConfig(),
) : T9QuickSymbolsStore {
    private var stored = initial
    override fun load(): T9QuickSymbolsConfig = stored
    override fun save(config: T9QuickSymbolsConfig) {
        stored = config
    }
}

/**
 * 偏好实现：与用户符号栏共用 `symbol_bar` 偏好文件（键不同），
 * 配置以 JSON 串存一键。
 */
class SharedPrefsT9QuickSymbolsStore(context: Context) : T9QuickSymbolsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): T9QuickSymbolsConfig =
        T9QuickSymbolsOps.parse(prefs.getString(KEY_CONFIG, null))

    override fun save(config: T9QuickSymbolsConfig) {
        prefs.edit().putString(KEY_CONFIG, T9QuickSymbolsOps.serialize(config)).apply()
    }

    companion object {
        private const val PREFS_NAME = "symbol_bar"
        private const val KEY_CONFIG = "t9_quick_symbols"
    }
}
