// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 符号栏规格（重点候选 8-6，皮肤顶层 `symbolBar` 节点）：
 *
 * - symbols：可配符号键；每键 1 点按（tap）＋最多 4 角长按
 *   （corners，顺序：左上／右上／左下／右下；缺角即无该角）；
 * - closePosition／typeSwitchPosition：关闭钮与类型切换钮位置，
 *   left／right 可换（非法／缺失 → 默认关闭右、切换左）；
 * - 类型切换的候选类型取现有符号分类（Symbol.Symbol 的分类
 *   label），规格本身不枚举类型；当前类型由 SymbolBarState 记住。
 *
 * 解析口径：非法键项逐项丢弃（只记数，调用方转 issue）；symbols
 * 缺失或全丢 → 整个规格为 null（无自定义栏，走现有符号键盘）。
 * 角超 4 个时保留前 4 个（多余截断）。
 */
enum class BarSide(val jsonName: String) {
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun byJsonName(name: String?): BarSide? =
            entries.firstOrNull { it.jsonName == name }
    }
}

data class SymbolBarKey(
    val tap: String,
    val corners: List<String>,
)

data class SymbolBarSpec(
    val keys: List<SymbolBarKey>,
    val closePosition: BarSide,
    val typeSwitchPosition: BarSide,
)

object SymbolBarParser {

    /** @return 规格（null = 无自定义栏）与丢弃的键项数。 */
    fun parse(node: JsonElement?): Pair<SymbolBarSpec?, Int> {
        val obj = node as? JsonObject ?: return null to 0
        val symbolsNode = obj["symbols"] as? JsonArray ?: return null to 0
        val keys = ArrayList<SymbolBarKey>()
        var dropped = 0
        for (element in symbolsNode) {
            val key = parseKey(element) ?: run { dropped++; continue }
            keys += key
        }
        if (keys.isEmpty()) return null to dropped
        return SymbolBarSpec(
            keys = keys,
            closePosition = BarSide.byJsonName(
                (obj["closePosition"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            ) ?: BarSide.RIGHT,
            typeSwitchPosition = BarSide.byJsonName(
                (obj["typeSwitchPosition"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            ) ?: BarSide.LEFT,
        ) to dropped
    }

    private fun parseKey(element: JsonElement): SymbolBarKey? {
        val obj = element as? JsonObject ?: return null
        val tap = (obj["tap"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() } ?: return null
        val corners = (obj["corners"] as? JsonArray)
            ?.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotEmpty() }
            }
            ?.take(4)
            .orEmpty()
        return SymbolBarKey(tap, corners)
    }
}

/**
 * 符号栏类型循环（接线轮 R5，纯函数）：类型切换按现有符号
 * 分类 label 顺序循环，末尾绕回首项；当前类型不在表内时
 * 回首项；空表返回 null（调用方不切换）。
 */
object SymbolBarTypeCycler {

    fun next(labels: List<String>, current: String?): String? {
        if (labels.isEmpty()) return null
        val index = labels.indexOf(current)
        return if (index < 0) labels[0] else labels[(index + 1) % labels.size]
    }
}

/**
 * 符号栏类型记忆存储（重点候选 8-6）：
 * 类型记住、打开态不记住——类型经本接口持久化（接线层注入
 * 偏好实现），打开态只存在于 SymbolBarState 实例内。
 */
interface SymbolBarTypeStore {
    fun load(): String?
    fun save(type: String?)
}

/** 内存实现（默认；接线层可替换为偏好实现以跨进程记住类型）。 */
class InMemorySymbolBarTypeStore : SymbolBarTypeStore {
    private var stored: String? = null
    override fun load(): String? = stored
    override fun save(type: String?) {
        stored = type
    }
}

/**
 * 符号栏状态：每次新建实例时打开态恒为关闭（不记住），类型从
 * 存储恢复（记住）；切换类型即写存储。
 */
class SymbolBarState(
    private val store: SymbolBarTypeStore = InMemorySymbolBarTypeStore(),
) {
    var currentType: String? = store.load()
        private set

    var isOpen: Boolean = false
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    fun switchType(type: String) {
        currentType = type
        store.save(type)
    }
}
