// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.skin.model.WhitelistAction
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * App 内工具栏编辑的用户级覆盖层（2026-10-29 工具栏编辑轮）。
 *
 * 分层（已决）：用户编辑 ＞ 皮肤工具栏 ＞ 出厂默认。用户编辑默认按
 * 皮肤 id 分键存储（换皮肤不串台，回切恢复）；皮肤未定义处（无用户
 * 编辑且皮肤无工具栏）用出厂默认五项。
 *
 * 存储形态：单文件 `toolbar_user.json`（应用私有目录），不碰皮肤文件
 * （皮肤文件永不改动）；图片（自定义按钮图、自定义工具栏背景图）存
 * `toolbar_images/` 下，文件内只记 basename（禁路径穿越）。
 * 本文件纯逻辑＋文件读写，不碰 Android 框架（Context 只在调用方
 * 取 filesDir 时出现），可直接单测；损坏文件视为无用户配置并上报
 * corrupt 标志（调用方记日志，只记异常类名，绝不记文件内容）。
 *
 * 空表语义：某皮肤的按钮列为空（删光或全停用）＝无覆盖，回落皮肤／
 * 默认，工具栏永不因删光而空白；悬空引用（池外 id、已删自定义钮／
 * 栏）逐项跳过，不拖垮整列。
 */

/** 自定义按钮手势槽（7 槽：点按／长按／双击／四向滑动；双击是槽位不是新动作）。 */
enum class CustomButtonSlot(val jsonName: String) {
    TAP("tap"),
    LONG_PRESS("longPress"),
    DOUBLE_TAP("doubleTap"),
    SWIPE_UP("swipeUp"),
    SWIPE_DOWN("swipeDown"),
    SWIPE_LEFT("swipeLeft"),
    SWIPE_RIGHT("swipeRight"),
}

/** 自定义按钮显示形态：文字／表情／图片（图片走应用私有图目录）。 */
enum class CustomDisplayKind(val jsonName: String) {
    TEXT("text"),
    EMOJI("emoji"),
    IMAGE("image"),
}

/** 动作绑定：21 项白名单动作＋可选值（值规则沿用白名单本身）。 */
data class ToolbarActionBinding(
    val action: WhitelistAction,
    val value: String? = null,
)

/** 自定义按钮定义（id 形如 `custom:<8 位 hex>`）。 */
data class ToolbarCustomButton(
    val id: String,
    val kind: CustomDisplayKind,
    val display: String,
    val imageFile: String?,
    val iconSizeDp: Int,
    val widthDp: Int,
    val backgroundArgb: Int?,
    val bindings: Map<CustomButtonSlot, ToolbarActionBinding>,
    /** 字号（sp）：null＝跟随图标大小（旧文件与旧行为一致）。 */
    val fontSizeSp: Float? = null,
    /** 边框颜色：null＝无边框。 */
    val borderArgb: Int? = null,
    /** 边框粗细（dp）：0＝无边框。 */
    val borderWidthDp: Float = 0f,
    /** 按下颜色：null＝默认按压涟漪（旧行为）。 */
    val pressedArgb: Int? = null,
) {
    /** 渲染字号：自定义字号优先，否则跟随图标大小。 */
    fun effectiveTextSizeDp(): Float = fontSizeSp ?: iconSizeDp.toFloat()
}

/** 自定义工具栏背景：纯色／透明／图片（三选，见 brief §5）。 */
sealed interface ToolbarBackground {
    data class Color(val argb: Int) : ToolbarBackground
    data object Transparent : ToolbarBackground
    data class Image(val file: String) : ToolbarBackground
}

/** 自定义工具栏配置（id 形如 `toolbar:<8 位 hex>`）。 */
data class ToolbarCustomConfig(
    val id: String,
    val name: String,
    val buttons: List<ToolbarButtonEntry>,
    val background: ToolbarBackground,
)

/** 按钮列一项：引用串（池 id 或 `custom:<id>`）＋启用开关。 */
data class ToolbarButtonEntry(
    val ref: String,
    val enabled: Boolean = true,
)

/** 某皮肤的用户态：按钮列＋生效中的自定义栏（null＝用按钮列）。 */
data class ToolbarSkinState(
    val buttons: List<ToolbarButtonEntry> = emptyList(),
    val activeToolbarId: String? = null,
)

/** 用户工具栏全量数据（文件根）。hideToolbar 为全局开关（不分皮肤）：开＝不渲染工具栏区。 */
data class ToolbarUserData(
    val version: Long = 1L,
    val skins: Map<String, ToolbarSkinState> = emptyMap(),
    val customButtons: Map<String, ToolbarCustomButton> = emptyMap(),
    val customToolbars: Map<String, ToolbarCustomConfig> = emptyMap(),
    val hideToolbar: Boolean = false,
)

/** 生效按钮（渲染层消费）：池钮＝ PanelAction，自定义钮＝定义原样。 */
sealed interface EffectiveToolbarButton {
    data class Pool(val id: String, val action: PanelAction) : EffectiveToolbarButton
    data class Custom(val def: ToolbarCustomButton) : EffectiveToolbarButton
}

/** 生效工具栏：按钮列（仅启用项）＋背景（null＝常规栏背景）。 */
data class EffectiveToolbar(
    val buttons: List<EffectiveToolbarButton>,
    val background: ToolbarBackground?,
)

object ToolbarUserStore {
    const val FILE_NAME = "toolbar_user.json"
    const val IMAGE_DIR = "toolbar_images"

    /** 无皮肤生效时的分键（内置主题态）。 */
    const val BUILTIN_SKIN_KEY = "builtin"

    const val MAX_BUTTONS = ToolbarConfig.MAX_ITEMS

    const val MIN_ICON_DP = 16
    const val MAX_ICON_DP = 48
    const val DEFAULT_ICON_DP = 24
    const val MIN_WIDTH_DP = 24
    const val MAX_WIDTH_DP = 160
    const val DEFAULT_WIDTH_DP = 48

    const val MIN_FONT_SP = 10f
    const val MAX_FONT_SP = 32f
    const val DEFAULT_FONT_SP = 14f
    const val MIN_BORDER_DP = 0f
    const val MAX_BORDER_DP = 4f
    /** 首次拉起边框粗细时未选颜色的默认边框色（中性灰）。 */
    const val DEFAULT_BORDER_ARGB = 0xFF9E9E9E.toInt()

    fun newCustomButtonId(): String = "custom:" + UUID.randomUUID().toString().substring(0, 8)
    fun newCustomToolbarId(): String = "toolbar:" + UUID.randomUUID().toString().substring(0, 8)

    fun isCustomRef(ref: String): Boolean = ref.startsWith("custom:")

    /**
     * 图片文件名消毒：只接受 basename（禁 `/`、`\`、`..`、空串），
     * 其余一律拒绝（返回 null），防路径穿越写出私有目录。
     */
    fun sanitizeImageFile(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return name
    }

    /** 校验一条动作绑定：返回问题串，null＝合法（值按规则归一化后返回绑定）。 */
    fun validateBinding(
        slot: CustomButtonSlot,
        binding: ToolbarActionBinding,
    ): Pair<ToolbarActionBinding?, String?> {
        val action = binding.action
        if (action == WhitelistAction.VOICE_INPUT && slot != CustomButtonSlot.LONG_PRESS) {
            return null to "voice_input 仅长按"
        }
        return when (action.valueRule) {
            WhitelistAction.ValueRule.REQUIRED -> {
                if (binding.value.isNullOrBlank()) {
                    null to "${action.formatName} 需要值"
                } else {
                    binding.copy(value = binding.value.trim()) to null
                }
            }
            WhitelistAction.ValueRule.FORBIDDEN -> binding.copy(value = null) to null
        }
    }

    /** 校验自定义按钮定义：返回问题表（空＝合法）。 */
    fun validateCustomButton(def: ToolbarCustomButton): List<String> {
        val problems = mutableListOf<String>()
        if (!def.id.startsWith("custom:") || def.id.length <= "custom:".length) {
            problems += "id 非法"
        }
        when (def.kind) {
            CustomDisplayKind.TEXT, CustomDisplayKind.EMOJI -> {
                if (def.display.isBlank()) problems += "显示文字为空"
            }
            CustomDisplayKind.IMAGE -> {
                if (sanitizeImageFile(def.imageFile) == null) problems += "图片文件非法"
            }
        }
        if (def.iconSizeDp !in MIN_ICON_DP..MAX_ICON_DP) problems += "图标大小超范围"
        if (def.widthDp !in MIN_WIDTH_DP..MAX_WIDTH_DP) problems += "按钮宽度超范围"
        def.fontSizeSp?.let { if (it !in MIN_FONT_SP..MAX_FONT_SP) problems += "字号超范围" }
        if (def.borderWidthDp !in MIN_BORDER_DP..MAX_BORDER_DP) problems += "边框粗细超范围"
        for ((slot, binding) in def.bindings) {
            validateBinding(slot, binding).second?.let { problems += it }
        }
        return problems
    }

    /**
     * 表单→定义：归一化绑定（值去空白、非法绑定剔除并记问题）＋整定义校验。
     * 问题非空时返回 null 定义（调用方只展示问题，不保存）；图片／文字互斥
     * 与页式表单同口径（图片钮 display 置空、非图片钮 imageFile 置空）。
     */
    fun buildCustomButton(
        id: String,
        kind: CustomDisplayKind,
        display: String,
        imageFile: String?,
        iconSizeDp: Int,
        widthDp: Int,
        backgroundArgb: Int?,
        fontSizeSp: Float?,
        borderArgb: Int?,
        borderWidthDp: Float,
        pressedArgb: Int?,
        bindings: Map<CustomButtonSlot, ToolbarActionBinding>,
    ): Pair<ToolbarCustomButton?, List<String>> {
        val normalized = mutableMapOf<CustomButtonSlot, ToolbarActionBinding>()
        val problems = mutableListOf<String>()
        for ((slot, binding) in bindings) {
            val (clean, problem) = validateBinding(slot, binding)
            if (problem != null) problems += problem
            if (clean != null) normalized[slot] = clean
        }
        val def = ToolbarCustomButton(
            id = id,
            kind = kind,
            display = if (kind == CustomDisplayKind.IMAGE) "" else display,
            imageFile = if (kind == CustomDisplayKind.IMAGE) imageFile else null,
            iconSizeDp = iconSizeDp,
            widthDp = widthDp,
            backgroundArgb = backgroundArgb,
            fontSizeSp = fontSizeSp,
            borderArgb = borderArgb,
            borderWidthDp = borderWidthDp,
            pressedArgb = pressedArgb,
            bindings = normalized,
        )
        problems += validateCustomButton(def)
        return if (problems.isEmpty()) def to emptyList() else null to problems
    }

    /** 添加对话框行：新建行在首，其后池行（已添加标灰），最后未进列表的自定义行。 */
    sealed interface ToolbarAddRow {
        data object NewCustom : ToolbarAddRow
        data class Pool(val id: String, val label: String, val added: Boolean) : ToolbarAddRow
        data class Custom(val id: String, val display: String, val isImage: Boolean) : ToolbarAddRow
    }

    fun toolbarAddRows(
        list: List<ToolbarButtonEntry>,
        customButtons: Map<String, ToolbarCustomButton>,
    ): List<ToolbarAddRow> {
        val inList = list.map { it.ref }.toSet()
        val rows = mutableListOf<ToolbarAddRow>(ToolbarAddRow.NewCustom)
        ToolbarConfig.POOL.forEach { item ->
            rows += ToolbarAddRow.Pool(item.id, item.label, item.id in inList)
        }
        customButtons.values.filter { it.id !in inList }.forEach { def ->
            rows += ToolbarAddRow.Custom(def.id, def.display, def.kind == CustomDisplayKind.IMAGE)
        }
        return rows
    }

    /** 校验按钮列：上限 9、池 id 须在池内、池 id 不重复（自定义引用不判重，由定义表保证）。 */
    fun validateButtons(
        buttons: List<ToolbarButtonEntry>,
        customButtons: Map<String, ToolbarCustomButton>,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (buttons.size > MAX_BUTTONS) problems += "按钮超上限 $MAX_BUTTONS"
        val seenPool = mutableSetOf<String>()
        for (entry in buttons) {
            if (isCustomRef(entry.ref)) {
                if (entry.ref !in customButtons) problems += "自定义钮缺失：${entry.ref}"
            } else {
                if (ToolbarConfig.item(entry.ref) == null) problems += "未知池按钮：${entry.ref}"
                if (!seenPool.add(entry.ref)) problems += "池按钮重复：${entry.ref}"
            }
        }
        return problems
    }

    /** 校验自定义栏：名非空、按钮列合法。 */
    fun validateCustomToolbar(
        config: ToolbarCustomConfig,
        customButtons: Map<String, ToolbarCustomButton>,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (config.name.isBlank()) problems += "栏名为空"
        if (config.background is ToolbarBackground.Image &&
            sanitizeImageFile(config.background.file) == null
        ) {
            problems += "背景图片文件非法"
        }
        problems += validateButtons(config.buttons, customButtons)
        return problems
    }

    /**
     * 抉择（纯函数）：某皮肤的用户生效工具栏；null＝该皮肤无用户配置
     * （调用方回落皮肤／默认）。悬空引用逐项跳过；栏悬空、列空、
     * 全停用皆视为无配置。
     */
    fun resolve(skinId: String, data: ToolbarUserData): EffectiveToolbar? {
        val state = data.skins[skinId] ?: return null
        val activeId = state.activeToolbarId
        if (activeId != null) {
            val config = data.customToolbars[activeId] ?: return null
            val buttons = resolveEntries(config.buttons, data)
            if (buttons.isEmpty()) return null
            return EffectiveToolbar(buttons, config.background)
        }
        if (state.buttons.isEmpty()) return null
        val buttons = resolveEntries(state.buttons, data)
        if (buttons.isEmpty()) return null
        return EffectiveToolbar(buttons, null)
    }

    private fun resolveEntries(
        entries: List<ToolbarButtonEntry>,
        data: ToolbarUserData,
    ): List<EffectiveToolbarButton> {
        val out = mutableListOf<EffectiveToolbarButton>()
        for (entry in entries) {
            if (!entry.enabled) continue
            if (isCustomRef(entry.ref)) {
                val def = data.customButtons[entry.ref] ?: continue
                out += EffectiveToolbarButton.Custom(def)
            } else {
                val item = ToolbarConfig.item(entry.ref) ?: continue
                out += EffectiveToolbarButton.Pool(entry.ref, item.action)
            }
        }
        return out
    }

    // ---- JSON 编解码（手动，与皮肤 JsonObject 风格一致；未知字段容忍） ----

    fun toJson(data: ToolbarUserData): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(data.version))
        put("hideToolbar", JsonPrimitive(data.hideToolbar))
        put("skins", buildJsonObject {
            for ((skinId, state) in data.skins) {
                put(skinId, buildJsonObject {
                    put("buttons", JsonArray(state.buttons.map { entry ->
                        buildJsonObject {
                            put("ref", JsonPrimitive(entry.ref))
                            put("enabled", JsonPrimitive(entry.enabled))
                        }
                    }))
                    state.activeToolbarId?.let { put("activeToolbarId", JsonPrimitive(it)) }
                })
            }
        })
        put("customButtons", buildJsonObject {
            for ((id, def) in data.customButtons) {
                put(id, buildJsonObject {
                    put("kind", JsonPrimitive(def.kind.jsonName))
                    put("display", JsonPrimitive(def.display))
                    def.imageFile?.let { put("imageFile", JsonPrimitive(it)) }
                    put("iconSizeDp", JsonPrimitive(def.iconSizeDp))
                    put("widthDp", JsonPrimitive(def.widthDp))
                    def.backgroundArgb?.let { put("backgroundArgb", JsonPrimitive(it)) }
                    def.fontSizeSp?.let { put("fontSizeSp", JsonPrimitive(it)) }
                    def.borderArgb?.let { put("borderArgb", JsonPrimitive(it)) }
                    if (def.borderWidthDp > 0f) put("borderWidthDp", JsonPrimitive(def.borderWidthDp))
                    def.pressedArgb?.let { put("pressedArgb", JsonPrimitive(it)) }
                    put("bindings", buildJsonObject {
                        for ((slot, binding) in def.bindings) {
                            put(slot.jsonName, buildJsonObject {
                                put("action", JsonPrimitive(binding.action.formatName))
                                binding.value?.let { put("value", JsonPrimitive(it)) }
                            })
                        }
                    })
                })
            }
        })
        put("customToolbars", buildJsonObject {
            for ((id, config) in data.customToolbars) {
                put(id, buildJsonObject {
                    put("name", JsonPrimitive(config.name))
                    put("buttons", JsonArray(config.buttons.map { entry ->
                        buildJsonObject {
                            put("ref", JsonPrimitive(entry.ref))
                            put("enabled", JsonPrimitive(entry.enabled))
                        }
                    }))
                    put("background", when (val bg = config.background) {
                        is ToolbarBackground.Color -> buildJsonObject {
                            put("kind", JsonPrimitive("color"))
                            put("argb", JsonPrimitive(bg.argb))
                        }
                        is ToolbarBackground.Transparent -> buildJsonObject {
                            put("kind", JsonPrimitive("transparent"))
                        }
                        is ToolbarBackground.Image -> buildJsonObject {
                            put("kind", JsonPrimitive("image"))
                            put("file", JsonPrimitive(bg.file))
                        }
                    })
                })
            }
        })
    }

    data class LoadResult(
        val data: ToolbarUserData,
        val corrupt: Boolean,
        val errorClass: String?,
    )

    /** 读文件：缺失＝空数据；损坏＝空数据＋corrupt 标志（调用方记日志，只记类名）。 */
    fun load(file: File): LoadResult {
        if (!file.exists()) return LoadResult(ToolbarUserData(), false, null)
        return try {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(file.readText())
            val root = parsed as? JsonObject
                ?: return LoadResult(ToolbarUserData(), true, "NotObject")
            LoadResult(fromJson(root), false, null)
        } catch (e: Exception) {
            LoadResult(ToolbarUserData(), true, e.javaClass.simpleName)
        }
    }

    private fun fromJson(root: JsonObject): ToolbarUserData {
        val version = root["version"]?.let {
            (it as? JsonPrimitive)?.longOrNull
        } ?: 1L
        val customButtons = mutableMapOf<String, ToolbarCustomButton>()
        val customButtonsObj = root["customButtons"] as? JsonObject ?: JsonObject(emptyMap())
        for ((id, element) in customButtonsObj) {
            val obj = element as? JsonObject ?: continue
            val kind = CustomDisplayKind.entries.firstOrNull {
                it.jsonName == (obj["kind"] as? JsonPrimitive)?.contentOrNull
            } ?: continue
            val bindings = mutableMapOf<CustomButtonSlot, ToolbarActionBinding>()
            val bindingsObj = obj["bindings"] as? JsonObject ?: JsonObject(emptyMap())
            for ((slotName, bindingElement) in bindingsObj) {
                val slot = CustomButtonSlot.entries.firstOrNull { it.jsonName == slotName }
                    ?: continue
                val bindingObj = bindingElement as? JsonObject ?: continue
                val action = WhitelistAction.fromFormatName(
                    (bindingObj["action"] as? JsonPrimitive)?.contentOrNull ?: "",
                ) ?: continue
                val raw = ToolbarActionBinding(
                    action,
                    (bindingObj["value"] as? JsonPrimitive)?.contentOrNull,
                )
                val (clean, _) = validateBinding(slot, raw)
                if (clean != null) bindings[slot] = clean
            }
            val def = ToolbarCustomButton(
                id = id,
                kind = kind,
                display = (obj["display"] as? JsonPrimitive)?.contentOrNull ?: "",
                imageFile = (obj["imageFile"] as? JsonPrimitive)?.contentOrNull,
                iconSizeDp = (obj["iconSizeDp"] as? JsonPrimitive)?.intOrNull
                    ?: DEFAULT_ICON_DP,
                widthDp = (obj["widthDp"] as? JsonPrimitive)?.intOrNull
                    ?: DEFAULT_WIDTH_DP,
                backgroundArgb = (obj["backgroundArgb"] as? JsonPrimitive)?.intOrNull,
                fontSizeSp = (obj["fontSizeSp"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull(),
                borderArgb = (obj["borderArgb"] as? JsonPrimitive)?.intOrNull,
                borderWidthDp = (obj["borderWidthDp"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 0f,
                pressedArgb = (obj["pressedArgb"] as? JsonPrimitive)?.intOrNull,
                bindings = bindings,
            )
            if (validateCustomButton(def).isEmpty()) customButtons[id] = def
        }
        val customToolbars = mutableMapOf<String, ToolbarCustomConfig>()
        val toolbarsObj = root["customToolbars"] as? JsonObject ?: JsonObject(emptyMap())
        for ((id, element) in toolbarsObj) {
            val obj = element as? JsonObject ?: continue
            val bgObj = obj["background"] as? JsonObject
            val background = when ((bgObj?.get("kind") as? JsonPrimitive)?.contentOrNull) {
                "color" -> (bgObj["argb"] as? JsonPrimitive)?.intOrNull?.let {
                    ToolbarBackground.Color(it)
                } ?: continue
                "transparent" -> ToolbarBackground.Transparent
                "image" -> (bgObj["file"] as? JsonPrimitive)?.contentOrNull?.let {
                    ToolbarBackground.Image(it)
                } ?: continue
                else -> continue
            }
            val config = ToolbarCustomConfig(
                id = id,
                name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                buttons = parseEntries(obj["buttons"]),
                background = background,
            )
            if (validateCustomToolbar(config, customButtons).isEmpty()) {
                customToolbars[id] = config
            }
        }
        val skins = mutableMapOf<String, ToolbarSkinState>()
        val skinsObj = root["skins"] as? JsonObject ?: JsonObject(emptyMap())
        for ((skinId, element) in skinsObj) {
            val obj = element as? JsonObject ?: continue
            val state = ToolbarSkinState(
                buttons = parseEntries(obj["buttons"]),
                activeToolbarId = (obj["activeToolbarId"] as? JsonPrimitive)?.contentOrNull,
            )
            // 悬空生效栏视为无配置（抉择期同样回落）；列内悬空引用在抉择期逐项跳过。
            skins[skinId] = state
        }
        val hideToolbar = (root["hideToolbar"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ToolbarUserData(version, skins, customButtons, customToolbars, hideToolbar)
    }

    private fun parseEntries(element: kotlinx.serialization.json.JsonElement?): List<ToolbarButtonEntry> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val ref = (obj["ref"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            ToolbarButtonEntry(ref, enabled)
        }
    }

    /** 写文件：版本号＋1、临时文件＋原子改名；失败返回 false（调用方记日志）。 */
    fun save(file: File, data: ToolbarUserData): Boolean {
        return try {
            val next = data.copy(version = data.version + 1)
            val text = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(), toJson(next),
            )
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (file.exists() && !file.delete()) return false
            tmp.renameTo(file)
        } catch (e: Exception) {
            false
        }
    }

    // ---- 500ms 防抖保存（与覆盖层存储同口径；测试与退出路径用 flush） ----

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    private var pendingFile: File? = null
    private var pendingData: ToolbarUserData? = null

    @Synchronized
    fun saveDebounced(file: File, data: ToolbarUserData) {
        pending?.cancel(false)
        pendingFile = file
        pendingData = data
        pending = scheduler.schedule({
            val f: File?
            val d: ToolbarUserData?
            synchronized(this) {
                f = pendingFile
                d = pendingData
                pendingFile = null
                pendingData = null
            }
            if (f != null && d != null) save(f, d)
        }, 500, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun flush() {
        pending?.cancel(false)
        val f = pendingFile
        val d = pendingData
        pendingFile = null
        pendingData = null
        if (f != null && d != null) save(f, d)
    }
}
