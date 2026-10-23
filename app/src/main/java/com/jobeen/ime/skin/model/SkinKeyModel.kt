package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 键覆写强类型模型（定稿三.5 / 四章；Batch 2 详设 §3）：
 * 皮肤 JSON 的动作节点只有三种形态，解析为密封类型 [SkinAction]，
 * 禁字符串/对象混杂；白名单为封闭枚举 [WhitelistAction]。
 *
 * 解析口径（详设 §3.1）：单个绑定无效只剔该绑定（该键该槽回退
 * 出厂行为）并逐项记录 [KeyBindingRejection]，不拖垮整段/整包。
 * 本文件纯函数：键 ID 登记判定经参数注入，可直接单测。
 */

/** 覆写槽位（定稿四.5）。jsonName = 皮肤文件中的字段名。 */
enum class KeySlot(val jsonName: String) {
    TAP("tap"),
    SWIPE_UP("swipeUp"),
    SWIPE_DOWN("swipeDown"),
    LONG_PRESS("longPress"),
}

/**
 * 命令动作白名单（封闭集；定稿四.1–四.3 + delete_forward +
 * 确认记录九冻结的补充格式名）。新增条目须先改定稿/裁定。
 */
enum class WhitelistAction(
    val formatName: String,
    val valueRule: ValueRule,
) {
    COMMIT_DIRECT("commit_direct", ValueRule.REQUIRED),
    LITERAL_NEWLINE("literal_newline", ValueRule.FORBIDDEN),
    ENTER("enter", ValueRule.FORBIDDEN),
    DELETE("delete", ValueRule.FORBIDDEN),
    DELETE_FORWARD("delete_forward", ValueRule.FORBIDDEN),
    SPACE("space", ValueRule.FORBIDDEN),
    CURSOR_LEFT("cursor_left", ValueRule.FORBIDDEN),
    CURSOR_RIGHT("cursor_right", ValueRule.FORBIDDEN),
    ROTATE_SCHEMA("rotate_schema", ValueRule.FORBIDDEN),
    OPEN_SCHEMA_PICKER("open_schema_picker", ValueRule.FORBIDDEN),
    SWITCH_SYSTEM_IME("switch_system_ime", ValueRule.FORBIDDEN),
    OPEN_SYSTEM_IME_PICKER("open_system_ime_picker", ValueRule.FORBIDDEN),
    HIDE_KEYBOARD("hide_keyboard", ValueRule.FORBIDDEN),
    CAPS("caps", ValueRule.FORBIDDEN),
    SWITCH_LAYOUT("switch_layout", ValueRule.REQUIRED),
    RESUME("resume", ValueRule.FORBIDDEN),
    CLEAR("clear", ValueRule.FORBIDDEN),
    UNDO("undo", ValueRule.FORBIDDEN),
    REDO("redo", ValueRule.FORBIDDEN),
    VOICE_INPUT("voice_input", ValueRule.FORBIDDEN),
    ;

    enum class ValueRule { REQUIRED, FORBIDDEN }

    /** 绑定位限制（定稿四.5）：voice_input 仅长按，其余任意槽。 */
    val allowedSlots: Set<KeySlot>
        get() = if (this == VOICE_INPUT) setOf(KeySlot.LONG_PRESS) else KeySlot.entries.toSet()

    companion object {
        fun fromFormatName(name: String): WhitelistAction? =
            entries.firstOrNull { it.formatName == name }

        /** switch_layout 的合法目标 = 出厂六布局 NAME（详设 §3.2）。 */
        val LAYOUT_TARGETS: Set<String> = setOf("Qwerty", "T9", "T15", "Number", "Symbol", "Emoji")

        /** 自创布局运行时名前缀（B3 §3.3）：switch_layout 目标 `custom:<layoutId>`。 */
        const val CUSTOM_LAYOUT_PREFIX = "custom:"

        /**
         * switch_layout 合法目标全集（B3 §3.3 待核 2 定死）：出厂六布局，
         * 或自创布局运行时名（前缀 + 非空 layoutId）。其余一律 VALUE_INVALID。
         */
        fun isLayoutTarget(value: String?): Boolean {
            if (value == null) return false
            if (value in LAYOUT_TARGETS) return true
            return value.startsWith(CUSTOM_LAYOUT_PREFIX) &&
                value.removePrefix(CUSTOM_LAYOUT_PREFIX).isNotBlank()
        }
    }
}

/** 强类型动作（详设 §3.1）。 */
sealed interface SkinAction {
    /** 喂 Rime 组字（参与 Caps，不经标点转换；详设 §3.5）。 */
    data class Text(val value: String) : SkinAction

    /** 白名单命令；value 仅 REQUIRED 类动作携带。 */
    data class Command(
        val action: WhitelistAction,
        val value: String? = null,
    ) : SkinAction

    /**
     * sequence 占位（历史类型，保留仅为模型兼容）：Batch 3 裁定
     * 删除该占位（详设 §6.6）后，解析器对 type=sequence 一律以
     * SEQUENCE_REMOVED 拒绝，不再产生本类型；文件中已有的
     * sequence 节点按未知节点原样往返、不执行不剥除。装配器对
     * 本类型的既有处理（恒等/过滤）保留，防御以旧版构建的内存
     * 模型流入。
     */
    data object SequencePlaceholder : SkinAction
}

/** 绑定剔除原因（详设 §八枚举；日志与校验报告共用，绝不携带原文）。 */
enum class BindingRejectReason {
    UNKNOWN_ID,
    ANCHOR_NOT_OVERRIDABLE,
    UNKNOWN_ACTION,
    BAD_SLOT,
    VALUE_MISSING,
    VALUE_FORBIDDEN,
    VALUE_INVALID,
    VOICE_NOT_EXCLUSIVE,
    STRUCTURE,

    /** type=sequence：占位已按 Batch 3 裁定删除（详设 §6.6），一律拒绝。 */
    SEQUENCE_REMOVED,
}

/** 一条被剔除的绑定/字段（结构化记录：快照工厂据此发诊断日志）。 */
data class KeyBindingRejection(
    val keyId: String,
    /** null = 键级（整条/ label / sound 字段）。 */
    val slot: KeySlot?,
    val reason: BindingRejectReason,
)

/**
 * 单键覆写（校验通过的强类型视图）。null 槽 = 该槽不覆写；
 * longPress 空列表在解析期不可能出现（剔空整槽回退出厂）。
 */
data class KeyOverride(
    val label: String? = null,
    val tap: SkinAction? = null,
    val swipeUp: SkinAction? = null,
    val swipeDown: SkinAction? = null,
    val longPress: List<SkinAction>? = null,
    /** 逐键音效（确认记录九）：资产逻辑 id；"" = 静音哨兵；null = 不覆写。 */
    val soundRef: String? = null,
)

/** 严格字符串读取：JSON 数字/布尔不算字符串（与 B1 严格数值读取同口径）。 */
private fun JsonElement?.stringContentOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** keyMappings 段解析器（纯函数；详设 §3.1/§3.3 校验口径全在此）。 */
object SkinKeyMappingsParser {

    /** label 长度上限（定稿九.1）。 */
    const val MAX_LABEL_LENGTH = 16

    data class ParseResult(
        val overrides: Map<String, KeyOverride>,
        val rejections: List<KeyBindingRejection>,
        /** sequence 占位出现的（键， 槽）清单：报告逐项明示保留不生效。 */
        val sequenceSlots: List<Pair<String, KeySlot>>,
    )

    fun parse(
        keyMappings: JsonObject,
        isOverridable: (String) -> Boolean,
        isAnchor: (String) -> Boolean,
    ): ParseResult {
        val overrides = LinkedHashMap<String, KeyOverride>()
        val rejections = mutableListOf<KeyBindingRejection>()
        val sequenceSlots = mutableListOf<Pair<String, KeySlot>>()
        for ((keyId, entryNode) in keyMappings) {
            if (isAnchor(keyId)) {
                rejections += KeyBindingRejection(
                    keyId, null, BindingRejectReason.ANCHOR_NOT_OVERRIDABLE,
                )
                continue
            }
            if (!isOverridable(keyId)) {
                rejections += KeyBindingRejection(keyId, null, BindingRejectReason.UNKNOWN_ID)
                continue
            }
            val entry = entryNode as? JsonObject
            if (entry == null) {
                rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
                continue
            }
            val label = parseLabel(keyId, entry, rejections)
            val soundRef = parseSoundRef(keyId, entry, rejections)
            val tap = parseSingle(keyId, KeySlot.TAP, entry["tap"], rejections, sequenceSlots)
            val swipeUp =
                parseSingle(keyId, KeySlot.SWIPE_UP, entry["swipeUp"], rejections, sequenceSlots)
            val swipeDown = parseSingle(
                keyId, KeySlot.SWIPE_DOWN, entry["swipeDown"], rejections, sequenceSlots,
            )
            val longPress = parseLongPress(keyId, entry["longPress"], rejections, sequenceSlots)
            if (label == null && soundRef == null && tap == null && swipeUp == null &&
                swipeDown == null && longPress == null
            ) {
                continue // 全字段被剔/未写：该键完全回退出厂，不进覆写表
            }
            overrides[keyId] = KeyOverride(
                label = label,
                tap = tap,
                swipeUp = swipeUp,
                swipeDown = swipeDown,
                longPress = longPress,
                soundRef = soundRef,
            )
        }
        return ParseResult(overrides, rejections, sequenceSlots)
    }

    /** 单动作节点解析结果（布局键槽位复用入口，B3 §2.2：与 keyMappings 同一语法）。 */
    data class SingleActionResult(
        val action: SkinAction?,
        /** 解析失败的原因；action 非空时恒为 null。 */
        val rejectReason: BindingRejectReason?,
    )

    /**
     * 解析单个动作节点（自创布局的 tap/swipeUp/swipeDown 槽位）。
     * 与 keyMappings 完全同一套规则（白名单/value 规则/槽位限制）。
     */
    fun parseSingleAction(slot: KeySlot, node: JsonElement): SingleActionResult {
        val rejections = mutableListOf<KeyBindingRejection>()
        val action = parseAction("", slot, node, rejections)
        return SingleActionResult(action, rejections.firstOrNull()?.reason)
    }

    /** 长按数组解析结果（布局键 longPress 槽位复用入口）。 */
    data class LongPressActionsResult(
        /** 有效动作数组；节点非数组/剔空时为 null（整槽回退）。 */
        val actions: List<SkinAction>?,
        /** 逐项剔除原因（含整槽结构错误 STRUCTURE）。 */
        val rejectReasons: List<BindingRejectReason>,
        /** 数组内出现过 sequence 占位（占位本身在 actions 中原样保留）。 */
        val hasSequence: Boolean,
    )

    /** 解析长按动作数组（逐元素规则 + voice_input 独占与 keyMappings 一致）。 */
    fun parseLongPressActions(node: JsonElement): LongPressActionsResult {
        val rejections = mutableListOf<KeyBindingRejection>()
        val sequenceSlots = mutableListOf<Pair<String, KeySlot>>()
        val actions = parseLongPress("", node, rejections, sequenceSlots)
        return LongPressActionsResult(
            actions = actions,
            rejectReasons = rejections.map { it.reason },
            hasSequence = sequenceSlots.isNotEmpty(),
        )
    }

    private fun parseLabel(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): String? {
        val node = entry["label"] ?: return null
        val raw = node.stringContentOrNull()
        if (raw == null || raw.length > MAX_LABEL_LENGTH) {
            // 字段级回退：只丢 label，动作绑定不受影响（定稿九.2）
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return raw
    }

    private fun parseSoundRef(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): String? {
        val node = entry["sound"] ?: return null
        val raw = node.stringContentOrNull()
        if (raw == null) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return raw // "" = 静音哨兵，非空 = 资产逻辑 id（接纳在快照构建期）
    }

    private fun parseSingle(
        keyId: String,
        slot: KeySlot,
        node: JsonElement?,
        rejections: MutableList<KeyBindingRejection>,
        sequenceSlots: MutableList<Pair<String, KeySlot>>,
    ): SkinAction? {
        if (node == null) return null
        val action = parseAction(keyId, slot, node, rejections) ?: return null
        if (action == SkinAction.SequencePlaceholder) sequenceSlots += keyId to slot
        return action
    }

    private fun parseLongPress(
        keyId: String,
        node: JsonElement?,
        rejections: MutableList<KeyBindingRejection>,
        sequenceSlots: MutableList<Pair<String, KeySlot>>,
    ): List<SkinAction>? {
        if (node == null) return null
        val array = node as? JsonArray
        if (array == null) {
            rejections += KeyBindingRejection(keyId, KeySlot.LONG_PRESS, BindingRejectReason.STRUCTURE)
            return null
        }
        val elements = mutableListOf<SkinAction>()
        for (element in array) {
            val action = parseAction(keyId, KeySlot.LONG_PRESS, element, rejections) ?: continue
            if (action == SkinAction.SequencePlaceholder) {
                sequenceSlots += keyId to KeySlot.LONG_PRESS
            }
            elements += action
        }
        // voice_input 独占（定稿四.5）：数组含它且不止一项 → 只剔 voice 项
        val hasVoice = elements.any {
            it is SkinAction.Command && it.action == WhitelistAction.VOICE_INPUT
        }
        if (hasVoice && elements.size != 1) {
            elements.removeAll {
                it is SkinAction.Command && it.action == WhitelistAction.VOICE_INPUT
            }
            rejections += KeyBindingRejection(
                keyId, KeySlot.LONG_PRESS, BindingRejectReason.VOICE_NOT_EXCLUSIVE,
            )
        }
        return elements.ifEmpty { null } // 剔空整槽回退出厂（详设 §3.1-4）
    }

    /** 解析单个动作节点；无效返回 null 并记录剔除原因。 */
    private fun parseAction(
        keyId: String,
        slot: KeySlot,
        node: JsonElement,
        rejections: MutableList<KeyBindingRejection>,
    ): SkinAction? {
        fun reject(reason: BindingRejectReason): SkinAction? {
            rejections += KeyBindingRejection(keyId, slot, reason)
            return null
        }
        val obj = node as? JsonObject ?: return reject(BindingRejectReason.STRUCTURE)
        val type = obj["type"].stringContentOrNull()
            ?: return reject(BindingRejectReason.UNKNOWN_ACTION)
        return when (type) {
            "text" -> {
                val value = obj["value"].stringContentOrNull()
                    ?: return reject(BindingRejectReason.VALUE_MISSING)
                SkinAction.Text(value)
            }
            // sequence 占位已删除（B3 裁定，详设 §6.6）：格式不保留
            // 该名，解析一律拒绝；文件节点本身在树层原样往返
            "sequence" -> return reject(BindingRejectReason.SEQUENCE_REMOVED)
            "command" -> {
                val actionName = obj["action"].stringContentOrNull()
                val action = actionName?.let { WhitelistAction.fromFormatName(it) }
                    ?: return reject(BindingRejectReason.UNKNOWN_ACTION)
                val valueNode = obj["value"]
                val value = if (valueNode == null) {
                    null
                } else {
                    valueNode.stringContentOrNull()
                        ?: return reject(BindingRejectReason.STRUCTURE)
                }
                when (action.valueRule) {
                    WhitelistAction.ValueRule.REQUIRED -> if (value == null) {
                        return reject(BindingRejectReason.VALUE_MISSING)
                    }
                    WhitelistAction.ValueRule.FORBIDDEN -> if (value != null) {
                        return reject(BindingRejectReason.VALUE_FORBIDDEN)
                    }
                }
                if (action == WhitelistAction.SWITCH_LAYOUT &&
                    !WhitelistAction.isLayoutTarget(value)
                ) {
                    return reject(BindingRejectReason.VALUE_INVALID)
                }
                if (slot !in action.allowedSlots) {
                    return reject(BindingRejectReason.BAD_SLOT)
                }
                SkinAction.Command(action, value)
            }
            else -> reject(BindingRejectReason.UNKNOWN_ACTION)
        }
    }
}
