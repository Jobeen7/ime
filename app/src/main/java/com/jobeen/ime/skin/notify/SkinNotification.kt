// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.notify

import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 状态驱动通知（重点候选 8-1，轻量变体）：
 *
 * 皮肤顶层 `notifications` 数组的一项。通知源变化时，若与
 * 某项的 source＋matchKey＋matchValue 全部匹配，该项即锁定到
 * 其 targetStyle（见 NotificationState 的锁定语义），对使用该
 * 样式的键（含工具栏键：工具栏键以按钮 id 作 targetStyle 匹配）
 * 改背景样式、前景样式、动作、上滑动作、下滑动作。
 *
 * - 样式值与 keyStyles 同语言：令牌路径或字面色，应用期解析，
 *   非法值应用期回退（解析期只要求非空字符串）。
 * - 动作值与 keyMappings 同语言：{"type":"text","value":...} 或
 *   {"type":"command","action":...[, "value":...]}，须过白名单；
 *   voice_input 在此无槽位语义，拒绝（字段级丢弃）。
 * - PREEDIT 源只表示“预编辑文本变化了”，matchKey／matchValue
 *   必须双空（预编辑文本绝不进入匹配与日志）。
 *
 * 运行时接线（本轮未接，见交回）：窗口层把五类事件喂给
 * NotificationState，覆写装配器在 copyWith 取 latchedFor
 * （styleRef／工具栏按钮 id）叠加；事件与状态只记源名与
 * 目标名，绝不记预编辑文本、候选词、剪贴板。
 */
enum class NotificationSource(val jsonName: String) {
    RIME_OPTION("rime_option"),
    SCHEME("scheme"),
    KEYBOARD_ACTION("keyboard_action"),
    RETURN_KEY_TYPE("return_key_type"),
    PREEDIT("preedit"),
    ;

    companion object {
        fun byJsonName(name: String): NotificationSource? =
            entries.firstOrNull { it.jsonName == name }
    }
}

data class SkinNotification(
    val source: NotificationSource,
    val matchKey: String?,
    val matchValue: String?,
    val targetStyle: String,
    val backgroundStyle: String?,
    val foregroundStyle: String?,
    val action: SkinAction?,
    val swipeUpAction: SkinAction?,
    val swipeDownAction: SkinAction?,
) {
    fun matches(event: NotificationEvent): Boolean {
        if (event.source != source) return false
        if (matchKey != null && matchKey != event.key) return false
        if (matchValue != null && matchValue != event.value) return false
        return true
    }
}

/** 通知事件（源＋键＋值；PREEDIT 事件键值双空，只表示“变了”）。 */
data class NotificationEvent(
    val source: NotificationSource,
    val key: String?,
    val value: String?,
)

/**
 * 锁定匹配状态：最近一次命中的通知按目标锁定；失配事件不清除、
 * 不回退（撤销靠另一项命中同一目标的通知）。同事件多项命中时
 * 文件顺序第一项胜出；目标互不干扰。
 */
class NotificationState(notifications: List<SkinNotification>) {

    private val rules: List<SkinNotification> = notifications
    private val latched = LinkedHashMap<String, SkinNotification>()

    /** 事件命中返回 true（锁定更新）；失配返回 false（状态原样）。 */
    fun onEvent(event: NotificationEvent): Boolean {
        val hit = rules.firstOrNull { it.matches(event) } ?: return false
        latched[hit.targetStyle] = hit
        return true
    }

    fun latchedFor(targetStyle: String): SkinNotification? = latched[targetStyle]

    fun snapshot(): Map<String, SkinNotification> = latched.toMap()
}

object SkinNotificationParser {

    /** @return 解析出的通知表与丢弃项数（坏项只记数，调用方转 issue）。 */
    fun parse(node: JsonArray): Pair<List<SkinNotification>, Int> {
        val out = ArrayList<SkinNotification>()
        var dropped = 0
        for (element in node) {
            val obj = element as? JsonObject ?: run { dropped++; continue }
            val parsed = parseEntry(obj) ?: run { dropped++; continue }
            out += parsed
        }
        return out to dropped
    }

    private fun parseEntry(obj: JsonObject): SkinNotification? {
        val sourceName = obj["source"].stringContentOrNull() ?: return null
        val source = NotificationSource.byJsonName(sourceName) ?: return null
        val matchKey = obj["matchKey"].stringContentOrNull()?.takeIf { it.isNotEmpty() }
        val matchValue = obj["matchValue"].stringContentOrNull()?.takeIf { it.isNotEmpty() }
        if (source == NotificationSource.PREEDIT && (matchKey != null || matchValue != null)) {
            return null // 预编辑文本绝不进入匹配
        }
        val targetStyle = obj["targetStyle"].stringContentOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val backgroundStyle = obj["backgroundStyle"].stringContentOrNull()
            ?.takeIf { it.isNotEmpty() }
        val foregroundStyle = obj["foregroundStyle"].stringContentOrNull()
            ?.takeIf { it.isNotEmpty() }
        return SkinNotification(
            source = source,
            matchKey = matchKey,
            matchValue = matchValue,
            targetStyle = targetStyle,
            backgroundStyle = backgroundStyle,
            foregroundStyle = foregroundStyle,
            action = parseAction(obj["action"]),
            swipeUpAction = parseAction(obj["swipeUpAction"]),
            swipeDownAction = parseAction(obj["swipeDownAction"]),
        )
    }

    /** 动作字段解析（与 keyMappings 同语言；非法只丢本字段，不拒整项）。 */
    private fun parseAction(node: JsonElement?): SkinAction? {
        val obj = node as? JsonObject ?: return null
        return when (obj["type"].stringContentOrNull()) {
            "text" -> {
                val value = obj["value"].stringContentOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: return null
                SkinAction.Text(value)
            }
            "command" -> {
                val formatName = obj["action"].stringContentOrNull() ?: return null
                val action = WhitelistAction.fromFormatName(formatName) ?: return null
                if (action == WhitelistAction.VOICE_INPUT) return null
                val value = obj["value"].stringContentOrNull()
                    ?.takeIf { it.isNotEmpty() }
                when (action.valueRule) {
                    WhitelistAction.ValueRule.REQUIRED -> {
                        if (value == null) return null
                        if (action == WhitelistAction.SWITCH_LAYOUT &&
                            value !in WhitelistAction.LAYOUT_TARGETS &&
                            !value.startsWith(WhitelistAction.CUSTOM_LAYOUT_PREFIX)
                        ) {
                            return null
                        }
                        SkinAction.Command(action, value)
                    }
                    WhitelistAction.ValueRule.FORBIDDEN -> {
                        if (value != null) return null
                        SkinAction.Command(action)
                    }
                }
            }
            else -> null
        }
    }

    private fun JsonElement?.stringContentOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
