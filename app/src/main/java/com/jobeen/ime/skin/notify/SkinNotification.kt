// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.notify

import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
 * - PREEDIT 源只表示预编辑空／非空状态变化：matchKey 必须空，
 *   matchValue 只接受 "empty"／"nonempty"（空与否的布尔信号，
 *   预编辑文本本身绝不进入匹配与日志）。
 * - 锁定（locked，默认 false）：命中后样式锁定到目标；失配时
 *   恢复（状态源新事件与已锁定规则失配即清除锁定），锁定项
 *   显式开启时失配也不清除。
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
    /**
     * 组字态键面文字（对端 whenPreeditChanged.text）；null = 不改
     * 文字（常态 label／动态文字照常）。命中时装配期以占位替换
     * 后的本值盖主显文本（优先于动态文字与静态 label）。
     */
    val label: String? = null,
    /** 锁定：true 时失配也不清除锁定；默认 false（失配恢复）。 */
    val locked: Boolean = false,
) {
    fun matches(event: NotificationEvent): Boolean {
        if (event.source != source) return false
        if (source == NotificationSource.SCHEME) {
            // P1：方案以 ID 或名二选一匹配（事件 key＝ID，value＝名）。
            if (matchKey != null && matchKey != event.key && matchKey != event.value) {
                return false
            }
        } else {
            if (matchKey != null && matchKey != event.key) return false
        }
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
 * 锁定匹配状态：最近一次命中的通知按目标锁定。同事件多项命中时
 * 文件顺序第一项胜出；目标互不干扰。
 *
 * 失配恢复（A3）：状态源（方案／rime 选项／回车类型／预编辑）
 * 的新事件描述该源当前状态，已锁定规则若与新事件失配即清除
 * （除非锁定），样式恢复原始；瞬时键盘动作无持久状态可失配，
 * 其锁定只靠另一项命中同一目标撤销。rime 选项事件只覆盖同名
 * 选项（各选项独立），其余状态源事件覆盖该源全部规则。
 */
class NotificationState(notifications: List<SkinNotification>) {

    private val rules: List<SkinNotification> = notifications
    private val latched = LinkedHashMap<String, SkinNotification>()

    /**
     * 事件命中或清除锁定返回 true；既无命中又无清除、或命中项
     * 已是当前锁定值（重复锁定）时返回 false——即返回值本身
     * 就是“锁定表是否真正变化”，调用方不再需要快照比对。
     * （旧口径：命中即返回 true，重复锁定靠调用方快照过滤；
     * 2026-11-03 起精确化，唯一主调用方 SkinRuntimeState.onEvent
     * 的对外语义不变，见其注释。）
     */
    fun onEvent(event: NotificationEvent): Boolean {
        var changed = false
        if (event.source != NotificationSource.KEYBOARD_ACTION) {
            val it = latched.entries.iterator()
            while (it.hasNext()) {
                val (target, rule) = it.next()
                if (rule.source != event.source || rule.locked) continue
                if (event.source == NotificationSource.RIME_OPTION) {
                    // 选项事件只覆盖同名选项；空 matchKey 通配规则不
                    // 自动清除（单选项事件无法证伪“任一选项取该值”）。
                    if (rule.matchKey == null || rule.matchKey != event.key) continue
                }
                if (!rule.matches(event)) {
                    it.remove()
                    changed = true
                }
            }
        }
        val hit = rules.firstOrNull { it.matches(event) }
        if (hit != null && latched[hit.targetStyle] != hit) {
            latched[hit.targetStyle] = hit
            changed = true
        }
        return changed
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
        if (source == NotificationSource.PREEDIT) {
            // A4：matchKey 仍禁（文本绝不进入匹配）；matchValue 只接受
            // 空／非空布尔信号，其余值（疑似文本内容）整项丢弃。
            if (matchKey != null) return null
            if (matchValue != null && matchValue != "empty" && matchValue != "nonempty") {
                return null
            }
        }
        val targetStyle = obj["targetStyle"].stringContentOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val backgroundStyle = obj["backgroundStyle"].stringContentOrNull()
            ?.takeIf { it.isNotEmpty() }
        val foregroundStyle = obj["foregroundStyle"].stringContentOrNull()
            ?.takeIf { it.isNotEmpty() }
        val locked = (obj["locked"] as? JsonPrimitive)?.booleanOrNull ?: false
        val label = obj["label"].stringContentOrNull()?.takeIf { it.isNotEmpty() }
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
            label = label,
            locked = locked,
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
                        // P3 新增动作的值格式校验（非法只丢本字段）：
                        // send_keys 须为可解析组合列，send_key_event
                        // 须为可解析组合，delay 须为 0–1000 毫秒数。
                        when (action) {
                            WhitelistAction.SEND_KEYS -> {
                                if (NotificationActionNames.parseSendKeysValue(value) == null) {
                                    return null
                                }
                            }
                            WhitelistAction.SEND_KEY_EVENT -> {
                                if (NotificationActionNames.parseKeyCombo(value) == null) {
                                    return null
                                }
                            }
                            WhitelistAction.DELAY -> {
                                val ms = value.toIntOrNull()
                                if (ms == null || ms !in 0..1000) return null
                            }
                            else -> {}
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
