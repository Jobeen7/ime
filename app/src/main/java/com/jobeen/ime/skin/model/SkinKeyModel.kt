package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.SwipeThresholds
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

/** 覆写槽位（定稿四.5 + G1 左右滑）。jsonName = 皮肤文件中的字段名。 */
enum class KeySlot(val jsonName: String) {
    TAP("tap"),
    SWIPE_UP("swipeUp"),
    SWIPE_DOWN("swipeDown"),
    SWIPE_LEFT("swipeLeft"),
    SWIPE_RIGHT("swipeRight"),
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
    /**
     * 命名方案直切换（2026-10-28 重点候选 8-3）：value = 方案 ID
     *（优先）或方案名；解析期只验非空字符串，ID/名解析在运行
     * 期（SchemaTargetResolver：ID 精确命中优先于名）。
     */
    SWITCH_SCHEMA("switch_schema", ValueRule.REQUIRED),
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
    /** G1 左右滑槽位（与上下滑同一白名单/槽位规则；voice_input 仅长按，见 allowedSlots）。 */
    val swipeLeft: SkinAction? = null,
    val swipeRight: SkinAction? = null,
    val longPress: List<SkinAction>? = null,
    /** 逐键音效（确认记录九）：资产逻辑 id；"" = 静音哨兵；null = 不覆写。 */
    val soundRef: String? = null,
    /** 单键样式引用（2026-10-27 并入项）：keyStyles 表中的样式名；null = 不引用。 */
    val styleRef: String? = null,
    /** 单键样式覆盖（2026-10-27 并入项）：逐角色取值（令牌路径或字面色），优先于引用样式。 */
    val styleOverrides: Map<KeyStyleRole, String>? = null,
    /**
     * G1 单键图片键面：包内图片资产逻辑 id；null = 不覆写。
     * 资产缺失/类型非法/超限时装配期逐字段回退颜色键面（永不拒整键），
     * 校验器（资产清单口径）与装配器（快照接纳口径）各守一道。
     */
    val image: String? = null,
    /**
     * G1 附加文字：字符串，用副文字角色渲染；"" 在解析期归一为
     * null（空值 = 无附加文字）；null = 不覆写。
     */
    val subLabel: String? = null,
    /**
     * 拟态第二行小字（2026-10-29 item-1）：顶部附加文字，与
     * [subLabel]（底部）同时出现；"" 归一为 null；null = 不覆写。
     * 渲染见 AltTextKeyView 顶部小字行。
     */
    val subLabelTop: String? = null,
    /** G1 逐键分档字号：角色 → 字号（皮肤级 textSizes 之上的逐键优先层）。 */
    val textSizes: Map<KeyTextRole, Float>? = null,
    /** G1 单键几何覆写（宽/高/内容中心/可视内缩）；null = 不覆写。 */
    val geometry: KeyGeometryOverride? = null,
    /** G1 前景多层叠加（按数组顺序渲染，后层压前层）；null/空 = 无叠加。 */
    val foregroundLayers: List<KeyForegroundLayer>? = null,
    /** G1 动态文字来源；null = 静态 label（构建期快照 + 运行时跟随，无值回退静态 label）。 */
    val dynamicText: DynamicTextSource? = null,
    /**
     * 长按弹出字符（参考项）：长按弹出 KeyboardPopup 候选条，
     * 每项提交为文本；null = 不覆写（沿用出厂弹窗/长按行为）。
     * 空数组解析期等价于 null（见 parsePopupChars）。
     */
    val popupChars: List<String>? = null,
    /**
     * 单键图标（参考项）：Material Symbols 名（见 MaterialSymbols
     * 注册表）；null = 不覆写。未知名不在解析期拒绝，装配期回退
     * （保留原文本/图片）并记日志，永不拒皮。
     */
    val icon: String? = null,
    /**
     * 按压态独立图标（重点候选 8-2）：Material Symbols 名；null = 未配，
     * 按压时回退常态图标。渲染切换要求常态图标已配（无常态图标时
     * 按压字形无可恢复的文本，装配期仍保留、渲染忽略）。
     */
    val pressedIcon: String? = null,
    /**
     * 按压态独立图片键面（重点候选 8-2）：包内图片资产逻辑 id；
     * null = 未配，按压时回退常态图片（或颜色键面）。接纳口径与
     * image 相同（快照清单在册才接纳，未接纳记日志回退）。
     */
    val pressedImage: String? = null,
    /**
     * 逐键滑动阈值（2026-10-28 截图 6 项）：dp；null = 不覆写
     * （走皮肤级/全局设置/默认 36dp）。非法值解析期丢弃。
     */
    val swipeThresholdDp: Float? = null,
)

/** G1 字段级解析警告（样式警告通道之外的字段回退记录：键、字段、原因）。 */
data class KeyFieldWarning(
    val keyId: String,
    val field: String,
    val reason: String,
)

/** 严格字符串读取：JSON 数字/布尔不算字符串（与 B1 严格数值读取同口径）。 */
private fun JsonElement?.stringContentOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** keyMappings 段解析器（纯函数；详设 §3.1/§3.3 校验口径全在此）。 */
object SkinKeyMappingsParser {

    /** label 长度上限（定稿九.1）。 */
    const val MAX_LABEL_LENGTH = 16

    /** 单个弹出字符长度上限（字符数；超长项逐项丢弃）。 */
    const val MAX_POPUP_CHAR_LENGTH = 8

    /** 单键弹出字符数量上限（超出项逐项丢弃）。 */
    const val MAX_POPUP_CHARS = 16

    data class ParseResult(
        val overrides: Map<String, KeyOverride>,
        val rejections: List<KeyBindingRejection>,
        /** sequence 占位出现的（键， 槽）清单：报告逐项明示保留不生效。 */
        val sequenceSlots: List<Pair<String, KeySlot>>,
        /** 样式字段解析警告（字段级回退记录，校验器转为 SkinIssue）。 */
        val styleWarnings: List<KeyStyleWarning> = emptyList(),
        /** G1 字段解析警告（image/subLabel/字号/几何/前景/动态文字，校验器转为 SkinIssue）。 */
        val fieldWarnings: List<KeyFieldWarning> = emptyList(),
    )

    fun parse(
        keyMappings: JsonObject,
        isOverridable: (String) -> Boolean,
        isAnchor: (String) -> Boolean,
    ): ParseResult {
        val overrides = LinkedHashMap<String, KeyOverride>()
        val rejections = mutableListOf<KeyBindingRejection>()
        val sequenceSlots = mutableListOf<Pair<String, KeySlot>>()
        val styleWarnings = mutableListOf<KeyStyleWarning>()
        val fieldWarnings = mutableListOf<KeyFieldWarning>()
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
            val swipeLeft = parseSingle(
                keyId, KeySlot.SWIPE_LEFT, entry["swipeLeft"], rejections, sequenceSlots,
            )
            val swipeRight = parseSingle(
                keyId, KeySlot.SWIPE_RIGHT, entry["swipeRight"], rejections, sequenceSlots,
            )
            val longPress = parseLongPress(keyId, entry["longPress"], rejections, sequenceSlots)
            val (styleRef, styleOverrides) = parseStyle(keyId, entry, rejections, styleWarnings)
            val image = parseImage(keyId, entry, rejections)
            val subLabel = parseSubLabel(keyId, entry, rejections)
            val subLabelTop = parseSubLabel(keyId, entry, rejections, "subLabelTop")
            val textSizes = parseTextSizes(keyId, entry, fieldWarnings)
            val geometry = parseGeometry(keyId, entry, fieldWarnings)
            val foregroundLayers = parseForegroundLayers(keyId, entry, fieldWarnings)
            val dynamicText = parseDynamicText(keyId, entry, rejections)
            val popupChars = parsePopupChars(keyId, entry, rejections)
            val icon = parseIcon(keyId, entry, rejections)
            val pressedIcon = parsePressedIcon(keyId, entry, rejections)
            val pressedImage = parsePressedImage(keyId, entry, rejections)
            val swipeThresholdDp = parseSwipeThreshold(keyId, entry, fieldWarnings)
            if (label == null && soundRef == null && tap == null && swipeUp == null &&
                swipeDown == null && swipeLeft == null && swipeRight == null &&
                longPress == null && styleRef == null && styleOverrides.isNullOrEmpty() &&
                image == null && subLabel == null && subLabelTop == null &&
                textSizes.isNullOrEmpty() &&
                (geometry == null || geometry.isEmpty) && foregroundLayers.isNullOrEmpty() &&
                dynamicText == null && popupChars == null && icon == null &&
                pressedIcon == null && pressedImage == null &&
                swipeThresholdDp == null
            ) {
                continue // 全字段被剔/未写：该键完全回退出厂，不进覆写表
            }
            overrides[keyId] = KeyOverride(
                label = label,
                tap = tap,
                swipeUp = swipeUp,
                swipeDown = swipeDown,
                swipeLeft = swipeLeft,
                swipeRight = swipeRight,
                longPress = longPress,
                soundRef = soundRef,
                styleRef = styleRef,
                styleOverrides = styleOverrides,
                image = image,
                subLabel = subLabel,
                subLabelTop = subLabelTop,
                textSizes = textSizes,
                geometry = geometry?.takeIf { !it.isEmpty },
                foregroundLayers = foregroundLayers?.takeIf { it.isNotEmpty() },
                dynamicText = dynamicText,
                popupChars = popupChars,
                icon = icon,
                pressedIcon = pressedIcon,
                pressedImage = pressedImage,
                swipeThresholdDp = swipeThresholdDp,
            )
        }
        return ParseResult(overrides, rejections, sequenceSlots, styleWarnings, fieldWarnings)
    }

    /**
     * G1 严格数值读取（与校验器同口径）：JSON 字符串不算数值。
     * 返回 null = 非数值/非有限值（调用方按字段级回退处理）。
     */
    private fun JsonElement?.strictFloatOrNull(): Float? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.content.toFloatOrNull() ?: return null
        return value.takeIf { it.isFinite() }
    }

    /** G1 单键图片资产 id：须为非空字符串，否则字段级回退（只丢 image）。 */
    private fun parseImage(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): String? {
        val node = entry["image"] ?: return null
        val raw = node.stringContentOrNull()
        if (raw == null || raw.isEmpty()) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return raw // 资产存在性/类型/尺寸由校验器清单口径与快照接纳口径判定
    }

    /**
     * G1 附加文字：须为字符串；"" 归一为 null（空值 = 无附加文字，
     * 不记拒绝）；超长按字段级回退（只丢该字段）。[field] 缺省
     * "subLabel"（底部），"subLabelTop" 为顶部第二行小字（同口径）。
     */
    private fun parseSubLabel(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
        field: String = "subLabel",
    ): String? {
        val node = entry[field] ?: return null
        val raw = node.stringContentOrNull()
        if (raw == null || raw.length > MAX_LABEL_LENGTH) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return raw.ifEmpty { null }
    }

    /** G1 逐键分档字号：对象（角色 → 数值 8..40）；非法角色/值逐项丢弃并记警告。 */
    private fun parseTextSizes(
        keyId: String,
        entry: JsonObject,
        warnings: MutableList<KeyFieldWarning>,
    ): Map<KeyTextRole, Float>? {
        val node = entry["textSizes"] ?: return null
        val obj = node as? JsonObject
        if (obj == null) {
            warnings += KeyFieldWarning(keyId, "textSizes", "text_sizes_not_object")
            return null
        }
        val sizes = LinkedHashMap<KeyTextRole, Float>()
        for ((name, valueNode) in obj) {
            val role = KeyTextRole.byJsonName(name)
            if (role == null) {
                warnings += KeyFieldWarning(keyId, "textSizes.$name", "unknown_text_role")
                continue
            }
            val value = valueNode.strictFloatOrNull()
            if (value == null || !KeyTextSizeLimits.isValid(value)) {
                warnings += KeyFieldWarning(keyId, "textSizes.$name", "text_size_invalid")
                continue
            }
            sizes[role] = value
        }
        return sizes.ifEmpty { null }
    }

    /** G1 单键几何覆写：对象，6 字段各按区间校验，非法单字段只丢该字段。 */
    private fun parseGeometry(
        keyId: String,
        entry: JsonObject,
        warnings: MutableList<KeyFieldWarning>,
    ): KeyGeometryOverride? {
        val node = entry["geometry"] ?: return null
        val obj = node as? JsonObject
        if (obj == null) {
            warnings += KeyFieldWarning(keyId, "geometry", "geometry_not_object")
            return null
        }
        fun ranged(name: String, range: ClosedFloatingPointRange<Float>): Float? {
            val value = obj[name]?.strictFloatOrNull()
            if (value == null) {
                if (obj.containsKey(name)) {
                    warnings += KeyFieldWarning(keyId, "geometry.$name", "geometry_value_invalid")
                }
                return null
            }
            if (value !in range) {
                warnings += KeyFieldWarning(keyId, "geometry.$name", "geometry_value_invalid")
                return null
            }
            return value
        }
        return KeyGeometryOverride(
            widthFactor = ranged("widthFactor", KeyGeometryOverride.WIDTH_RANGE),
            heightFactor = ranged("heightFactor", KeyGeometryOverride.HEIGHT_RANGE),
            contentCenterX = ranged("contentCenterX", KeyGeometryOverride.CENTER_RANGE),
            contentCenterY = ranged("contentCenterY", KeyGeometryOverride.CENTER_RANGE),
            hInsetDp = ranged("hInsetDp", KeyGeometryOverride.INSET_RANGE),
            vInsetDp = ranged("vInsetDp", KeyGeometryOverride.INSET_RANGE),
        )
    }

    /** G1 前景层：数组 ≤4 层；非法层整层丢弃并记警告，超限后层丢弃。 */
    private fun parseForegroundLayers(
        keyId: String,
        entry: JsonObject,
        warnings: MutableList<KeyFieldWarning>,
    ): List<KeyForegroundLayer>? {
        val node = entry["foregroundLayers"] ?: return null
        val array = node as? JsonArray
        if (array == null) {
            warnings += KeyFieldWarning(keyId, "foregroundLayers", "foreground_not_array")
            return null
        }
        val layers = mutableListOf<KeyForegroundLayer>()
        for ((index, element) in array.withIndex()) {
            if (layers.size >= KeyForegroundLayer.MAX_LAYERS) {
                warnings += KeyFieldWarning(
                    keyId, "foregroundLayers[$index]", "foreground_layer_excess",
                )
                continue
            }
            val layer = parseForegroundLayer(keyId, index, element, warnings) ?: continue
            layers += layer
        }
        return layers.ifEmpty { null }
    }

    private fun parseForegroundLayer(
        keyId: String,
        index: Int,
        element: JsonElement,
        warnings: MutableList<KeyFieldWarning>,
    ): KeyForegroundLayer? {
        val path = "foregroundLayers[$index]"
        val obj = element as? JsonObject
        if (obj == null) {
            warnings += KeyFieldWarning(keyId, path, "foreground_layer_not_object")
            return null
        }
        val type = obj["type"].stringContentOrNull()
        if (type != "text" && type != "image") {
            warnings += KeyFieldWarning(keyId, path, "foreground_layer_bad_type")
            return null
        }
        if (type == "text") {
            val text = obj["text"].stringContentOrNull()
            if (text == null || text.isEmpty() || text.length > KeyForegroundLayer.MAX_TEXT_LENGTH) {
                warnings += KeyFieldWarning(keyId, path, "foreground_text_invalid")
                return null
            }
            val size = obj["size"]?.strictFloatOrNull()
            if (obj.containsKey("size") && (size == null || !KeyTextSizeLimits.isValid(size))) {
                warnings += KeyFieldWarning(keyId, path, "foreground_size_invalid")
                return null
            }
            return KeyForegroundLayer(
                type = type,
                text = text,
                colorRaw = obj["color"].stringContentOrNull(),
                size = size,
                biasX = parseBias(keyId, obj, "biasX", path, warnings),
                biasY = parseBias(keyId, obj, "biasY", path, warnings),
            )
        }
        val image = obj["image"].stringContentOrNull()
        if (image == null || image.isEmpty()) {
            warnings += KeyFieldWarning(keyId, path, "foreground_image_invalid")
            return null
        }
        return KeyForegroundLayer(
            type = type,
            image = image,
            biasX = parseBias(keyId, obj, "biasX", path, warnings),
            biasY = parseBias(keyId, obj, "biasY", path, warnings),
        )
    }

    private fun parseBias(
        keyId: String,
        obj: JsonObject,
        name: String,
        path: String,
        warnings: MutableList<KeyFieldWarning>,
    ): Float? {
        if (!obj.containsKey(name)) return null
        val value = obj[name]?.strictFloatOrNull()
        if (value == null || value !in KeyGeometryOverride.CENTER_RANGE) {
            warnings += KeyFieldWarning(keyId, "$path.$name", "foreground_bias_invalid")
            return null
        }
        return value
    }

    /** G1 动态文字来源：须为 candidateFirst/composing 之一，否则字段级回退。 */
    private fun parseDynamicText(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): DynamicTextSource? {
        val node = entry["dynamicText"] ?: return null
        val raw = node.stringContentOrNull()
        val source = raw?.let { DynamicTextSource.byJsonName(it) }
        if (source == null) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return source
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

    /**
     * 单键样式字段解析（2026-10-27 并入项）：style 须为字符串，
     * styleOverrides 须为对象（角色 → 取值字符串）；无效字段级
     * 回退（只丢该字段，动作绑定不受影响），警告另行汇总。
     */
    private fun parseStyle(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
        styleWarnings: MutableList<KeyStyleWarning>,
    ): Pair<String?, Map<KeyStyleRole, String>?> {
        var styleRef: String? = null
        entry["style"]?.let { node ->
            val raw = node.stringContentOrNull()
            if (raw == null || raw.isEmpty()) {
                rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
                styleWarnings += KeyStyleWarning(keyId, null, "style_not_string")
            } else {
                styleRef = raw
            }
        }
        var styleOverrides: Map<KeyStyleRole, String>? = null
        entry["styleOverrides"]?.let { node ->
            val obj = node as? JsonObject
            if (obj == null) {
                rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
                styleWarnings += KeyStyleWarning(keyId, null, "style_overrides_not_object")
            } else {
                val (roles, warnings) = KeyStyleParser.parseRoles(keyId, obj)
                styleWarnings += warnings
                if (roles.isNotEmpty()) styleOverrides = roles
            }
        }
        return styleRef to styleOverrides
    }

    /**
     * 弹出字符解析（参考项）：节点须为数组；逐项规则——
     * 非字符串、空串、长度 > [MAX_POPUP_CHAR_LENGTH] 的项逐项丢弃
     * （各记一条 STRUCTURE 剔除）；数组只保留前 [MAX_POPUP_CHARS]
     * 项，超出的逐项丢弃；剔空/未写/空数组 → null（= 默认，不覆写）。
     * 弹出字符不参与动作白名单：提交时按纯文本处理。
     */
    private fun parsePopupChars(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): List<String>? {
        val node = entry["popupChars"] ?: return null
        val array = node as? JsonArray
        if (array == null) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        val kept = mutableListOf<String>()
        for (element in array) {
            val raw = element.stringContentOrNull()
            if (raw == null || raw.isEmpty() || raw.length > MAX_POPUP_CHAR_LENGTH) {
                rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
                continue
            }
            if (kept.size >= MAX_POPUP_CHARS) {
                rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
                continue
            }
            kept += raw
        }
        return kept.ifEmpty { null }
    }

    /**
     * 单键图标解析（参考项）：须为非空字符串，否则字段级
     * 丢弃（STRUCTURE）；名称是否在 Material Symbols 注册表内
     * 不在此判定——装配期未知回退并记日志，永不拒皮。
     */
    private fun parseIcon(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): String? {
        val node = entry["icon"] ?: return null
        val raw = node.stringContentOrNull()
        if (raw == null || raw.isEmpty()) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return raw
    }

    /** 按压态独立图标（重点候选 8-2）：与 icon 同口径（非空字符串，否则字段级回退）。 */
    private fun parsePressedIcon(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): String? {
        val node = entry["pressedIcon"] ?: return null
        val raw = node.stringContentOrNull()
        if (raw == null || raw.isEmpty()) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return raw
    }

    /** 按压态独立图片键面（重点候选 8-2）：与 image 同口径（非空字符串，否则字段级回退）。 */
    private fun parsePressedImage(
        keyId: String,
        entry: JsonObject,
        rejections: MutableList<KeyBindingRejection>,
    ): String? {
        val node = entry["pressedImage"] ?: return null
        val raw = node.stringContentOrNull()
        if (raw == null || raw.isEmpty()) {
            rejections += KeyBindingRejection(keyId, null, BindingRejectReason.STRUCTURE)
            return null
        }
        return raw // 资产存在性/类型/尺寸由校验器清单口径与快照接纳口径判定
    }

    /**
     * 逐键滑动阈值（2026-10-28 截图 6 项）：严格数值 + 区间校验，
     * 非法值记字段警告并丢弃（不拒整键、不影响其它字段）。
     */
    private fun parseSwipeThreshold(
        keyId: String,
        entry: JsonObject,
        warnings: MutableList<KeyFieldWarning>,
    ): Float? {
        val node = entry["swipeThresholdDp"] ?: return null
        val value = node.strictFloatOrNull()
        if (value == null || !value.isFinite() ||
            value < SwipeThresholds.MIN_DP || value > SwipeThresholds.MAX_DP
        ) {
            warnings += KeyFieldWarning(keyId, "swipeThresholdDp", "swipe_threshold_invalid")
            return null
        }
        return value
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
                if (action == WhitelistAction.SWITCH_SCHEMA &&
                    value.isNullOrBlank()
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
