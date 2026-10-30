package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 皮肤工具栏构成（2026-10-27 工具栏进皮肤分流）。
 *
 * skin.json 顶层 `toolbar` 节点（与 appearance / styles / keyMappings
 * 并列，未知节点原样往返口径不变）：
 * ```json
 * "toolbar": {
 *   "buttons": [{"id": "emoji", "icon": "tb-emoji"}, "copy", {"id": "voice"}],
 *   "expandIcon": "tb-expand",
 *   "collapseIcon": "tb-collapse"
 * }
 * ```
 * - `buttons`：中央区清单，可增删；元素为对象 `{id, icon?}`，或
 *   纯字符串简写（仅 id、无自定义图标）。`id` 必须取自既有工具栏
 *   按钮池（[isKnownButton] 判定，未知 id 视为非法）。
 * - `icon` / `expandIcon` / `collapseIcon`：两种写法，资产优先：
 *   (a) 包内图片资产逻辑 id（manifest IMAGE 资产，类别由清单
 *   path 扩展名判定，与 appearance.background 同口径）；(b) 非
 *   资产字符串 = App 内置图标名（Material Symbols 名，网页版
 *   编辑器即按此写两端与按钮图标）。本分流不自建符号注册表：
 *   符号名只原样保留进校验结果/快照，渲染期无注册表时回退
 *   该钮内置图标（与未知符号的回退一致）；符号注册表由图标
 *   主题分流在 [SkinToolbarIconResolver] / 渲染层接线。已知
 *   资产 id 但类别非图片时该图标丢弃并记警告，绝不拒整包。
 * - `expandIcon` = 最左侧槽（展开功能菜单），`collapseIcon` =
 *   最右侧槽（收起键盘）。历史别名 `leftIcon` / `rightIcon` 与
 *   正名同义，正名优先于别名。
 * - `buttons` 字段缺失 = 中央区不由皮肤定义（用出厂默认）；
 *   显式空数组 = 中央区留空（皮肤优先于出厂默认）。
 * - 数量上限（[MAX_BUTTONS]，与
 *   ToolbarConfig.MAX_ITEMS 同值，单测断言两者相等）。
 *
 * 纯解析：按钮池判定与资产类别经参数注入，可直接单测。
 */

/** 皮肤工具栏中央区一钮（校验后视图）。 */
data class SkinToolbarButton(
    val id: String,
    /** 自定义图标资产逻辑 id；null = 无资产图标（看 [iconSymbol]）。 */
    val iconAssetId: String?,
    /**
     * 内置图标名（Material Symbols 名，非资产字符串原样保留）；
     * null = 无符号图标。资产与符号至多其一（资产优先判定）。
     * 渲染期无符号注册表时回退该钮内置图标。
     */
    val iconSymbol: String? = null,
)

/** 工具栏逐项丢弃记录（导入报告与诊断日志共用，只记元数据）。 */
data class SkinToolbarDrop(
    /** 字段：buttons / expandIcon / collapseIcon。 */
    val field: String,
    /** 被丢弃的 id 或资产 id（无 id 时为 null，如类型错的整项）。 */
    val id: String?,
    val reason: String,
)

/** 工具栏校验结果：`buttons == null` 表示皮肤未定义中央区。 */
data class SkinToolbarSpec(
    val present: Boolean = false,
    val buttons: List<SkinToolbarButton>? = null,
    val expandIconAssetId: String? = null,
    val collapseIconAssetId: String? = null,
    /** 边缘符号名（与资产字段互斥，资产优先判定）。 */
    val expandIconSymbol: String? = null,
    val collapseIconSymbol: String? = null,
    val dropped: List<SkinToolbarDrop> = emptyList(),
)

/** 快照中的工具栏图标引用（内容寻址，解码在渲染构造期）。 */
data class SkinToolbarIconRef(
    val assetId: String,
    val sha256: String,
    /** 清单 path 扩展名（小写，无点）；空串 = 清单 path 无扩展名。 */
    val ext: String,
)

/** 快照中的工具栏中央区一钮。 */
data class SkinToolbarButtonSnapshot(
    val id: String,
    val icon: SkinToolbarIconRef?,
    /** 内置图标名（原样保留；渲染期无注册表时回退内置图标）。 */
    val symbol: String? = null,
)

/** 快照中的工具栏构成：`buttons == null` 表示未定义中央区。 */
data class SkinToolbarSnapshot(
    val present: Boolean = false,
    val buttons: List<SkinToolbarButtonSnapshot>? = null,
    val expandIcon: SkinToolbarIconRef? = null,
    val collapseIcon: SkinToolbarIconRef? = null,
    val expandSymbol: String? = null,
    val collapseSymbol: String? = null,
)

object SkinToolbarParser {

    /**
     * 中央区上限：与 ToolbarConfig.MAX_ITEMS 同值（见上）。
     * 2026-10-29 item-1 拟态项：7→9（一行 9 图标容得下）。
     */
    const val MAX_BUTTONS = 9

    /**
     * @param node `toolbar` 节点（null = 未定义）。
     * @param assetKind 资产逻辑 id → 类别；查无返回 null。
     * @param isKnownButton 按钮 id 是否在既有工具栏按钮池内。
     * @return 校验结果 + 逐项 issue（调用方并入导入报告）。
     */
    fun parse(
        node: kotlinx.serialization.json.JsonElement?,
        assetKind: (String) -> AssetKind?,
        isKnownButton: (String) -> Boolean,
    ): Pair<SkinToolbarSpec, List<SkinIssue>> {
        if (node == null) return SkinToolbarSpec() to emptyList()
        val obj = node as? JsonObject
        if (obj == null) {
            return SkinToolbarSpec() to listOf(
                SkinIssue("toolbar", SkinIssue.Kind.FIELD_FALLBACK, "toolbar_not_object"),
            )
        }
        val issues = mutableListOf<SkinIssue>()
        val dropped = mutableListOf<SkinToolbarDrop>()
        fun drop(field: String, id: String?, reason: String, kind: SkinIssue.Kind) {
            dropped += SkinToolbarDrop(field, id, reason)
            issues += SkinIssue("toolbar.$field", kind, reason)
        }

        // ---- 中央区 ----
        var buttons: List<SkinToolbarButton>? = null
        if (obj.containsKey("buttons")) {
            val parsed = mutableListOf<SkinToolbarButton>()
            val seen = LinkedHashSet<String>()
            val array = obj["buttons"] as? JsonArray
            if (array == null) {
                // 非数组 = 中央区未定义（回落出厂默认），而非留空。
                drop("buttons", null, "buttons_not_array", SkinIssue.Kind.FIELD_FALLBACK)
                buttons = null
            } else {
                for (element in array) {
                    if (parsed.size >= MAX_BUTTONS) {
                        val overId = when (element) {
                            is JsonPrimitive -> element.contentOrNull
                            is JsonObject -> (element["id"] as? JsonPrimitive)?.contentOrNull
                            else -> null
                        }
                        drop("buttons", overId, "toolbar_over_limit", SkinIssue.Kind.FIELD_FALLBACK)
                        continue
                    }
                    val (id, iconRaw) = when (element) {
                        is JsonPrimitive -> element.contentOrNull to null
                        is JsonObject -> {
                            (element["id"] as? JsonPrimitive)?.contentOrNull to
                                (element["icon"] as? JsonPrimitive)?.contentOrNull
                        }
                        else -> null to null
                    }
                    if (id.isNullOrBlank()) {
                        drop("buttons", null, "toolbar_button_id_invalid", SkinIssue.Kind.FIELD_FALLBACK)
                        continue
                    }
                    if (!isKnownButton(id)) {
                        drop("buttons", id, "toolbar_unknown_button", SkinIssue.Kind.FIELD_FALLBACK)
                        continue
                    }
                    if (!seen.add(id)) {
                        drop("buttons", id, "toolbar_duplicate_button", SkinIssue.Kind.FIELD_FALLBACK)
                        continue
                    }
                    // 图标写法（资产优先）：IMAGE 资产 → 资产；已知资产
                    // 但类别非图片 → 丢弃该图标并记警告（钮保留）；
                    // 非资产字符串 → 内置图标名（Material Symbols 名）
                    // 原样保留，不记缺失（符号有效性由注册表分流在
                    // 渲染期判定，未知名回退内置图标）。注意：拼错的
                    // 资产 id 与符号名在此无法区分，一律按符号保留，
                    // 渲染期未解析即回退内置图标并由注册表分流记日志。
                    val (iconAsset, iconSymbol) = when {
                        iconRaw.isNullOrBlank() -> null to null
                        assetKind(iconRaw) == AssetKind.IMAGE -> iconRaw to null
                        assetKind(iconRaw) != null -> {
                            drop("buttons", iconRaw, "toolbar_icon_asset_wrong_type", SkinIssue.Kind.RESOURCE_FALLBACK)
                            null to null
                        }
                        else -> null to iconRaw
                    }
                    // icon 字段非字符串时 iconRaw 为 null，已按“无图标”处理；
                    // 但节点存在且非字符串属类型错，补记一项（不丢钮）。
                    if (element is JsonObject && element.containsKey("icon") &&
                        element["icon"] !is JsonPrimitive
                    ) {
                        drop("buttons", id, "toolbar_icon_not_string", SkinIssue.Kind.FIELD_FALLBACK)
                    }
                    parsed += SkinToolbarButton(id, iconAsset, iconSymbol)
                }
                // 原数组非空却一项未留 = 全被逐项丢弃：中央区回落
                // 出厂默认，绝不因皮肤笔误把整条中央区抹空；显式
                // 空数组（parsed 空且原数组空）仍表示留空。
                buttons = if (array.isNotEmpty() && parsed.isEmpty()) null else parsed
            }
        }

        // ---- 左右边缘图标（正名优先，别名兜底） ----
        // 返回 (资产 id, 符号名)，口径与中央区图标一致。
        fun edgeIcon(canonical: String, alias: String): Pair<String?, String?> {
            val rawNode = obj[canonical] ?: obj[alias]
            if (rawNode == null) return null to null
            val raw = (rawNode as? JsonPrimitive)?.contentOrNull
            if (raw.isNullOrBlank()) {
                drop(canonical, null, "toolbar_edge_icon_invalid", SkinIssue.Kind.FIELD_FALLBACK)
                return null to null
            }
            return when (assetKind(raw)) {
                AssetKind.IMAGE -> raw to null
                null -> null to raw
                else -> {
                    drop(canonical, raw, "toolbar_icon_asset_wrong_type", SkinIssue.Kind.RESOURCE_FALLBACK)
                    null to null
                }
            }
        }
        val (expandIcon, expandSymbol) = edgeIcon("expandIcon", "leftIcon")
        val (collapseIcon, collapseSymbol) = edgeIcon("collapseIcon", "rightIcon")

        // 节点本身存在即 present；中央区/边缘是否生效各看自身
        // 是否为 null（buttons == null = 中央区未定义，走出厂默认）。
        val present = true
        return SkinToolbarSpec(
            present = present,
            buttons = buttons,
            expandIconAssetId = expandIcon,
            collapseIconAssetId = collapseIcon,
            expandIconSymbol = expandSymbol,
            collapseIconSymbol = collapseSymbol,
            dropped = dropped,
        ) to issues
    }
}
