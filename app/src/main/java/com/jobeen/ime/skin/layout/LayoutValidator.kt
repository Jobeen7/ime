package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.KeySlot
import com.jobeen.ime.skin.model.SizeFraction
import com.jobeen.ime.skin.model.KeyStyleParser
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinKeyMappingsParser
import com.jobeen.ime.skin.model.SkinValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** 校验问题的处置（B3 详设 §2.5 矩阵写死）。 */
enum class LayoutDisposition {
    /** 结构类错误：整布局无效（导入时点 = 整包拒绝；运行时点 = 引用方回退）。 */
    INVALID_LAYOUT,

    /** 单字段错误：该字段回退到缺省/回退链下一级，逐项明示，布局仍有效。 */
    FIELD_FALLBACK,

    /** 单槽位/单元素动作无效：只丢该槽位（或长按数组的该元素），逐项明示。 */
    SLOT_DROPPED,
}

/**
 * 一条校验问题：字段路径（如 `rows[2].keys[5].widthWeight`）+ 原因码
 * + 处置。同一份清单供导入报告、编辑器红标、诊断日志三处消费。
 * 绝不携带 label/动作 value 原文（诊断日志红线，B1 §13）。
 */
data class LayoutIssue(
    val path: String,
    val reason: String,
    val disposition: LayoutDisposition,
)

sealed interface LayoutValidation {
    data class Valid(val layout: LayoutModel, val issues: List<LayoutIssue>) : LayoutValidation
    data class Invalid(val issues: List<LayoutIssue>) : LayoutValidation
}

/**
 * 布局校验器（纯函数，B3 详设 §2.5）：导入、快照解析、编辑器保存
 * 三处共用同一实现，不许各写一套。
 *
 * 分界写死：结构类错误（硬限/权重/跨键/stableId/tap）→ 整布局
 * 无效；单键可选字段错误（label/meta.name/可选动作槽）→ 逐字段
 * 回退或丢槽并明示。未知字段/未知段一律忽略（原始树在文件层
 * 保留，round-trip 无损）。
 */
object LayoutValidator {

    const val MAX_ROWS = 8
    const val MAX_KEYS_PER_ROW = 12
    const val MAX_TOTAL_KEYS = 64
    const val MIN_WEIGHT = 0.05
    const val MAX_WEIGHT = 20.0
    const val MIN_ROW_WEIGHT_SUM = 0.05
    const val MAX_ROW_WEIGHT_SUM = 200.0
    const val DEFAULT_WEIGHT = 1.0

    /** 浮点边界比较容差（权重和恰在边界值时不因二进制误差误判）。 */
    private const val EPS = 1e-9

    /**
     * @param tree layout.json 原始树；null（解析失败）按整布局无效处理。
     * @param expectedId 入库 id（索引/目录名）：meta.id 必须与之一致（铁律 3）。
     */
    /**
     * @param expectedId 入库 id（索引/目录名）：meta.id 必须与之一致（铁律 3）。
     * @param allowMissingTap 内置布局文件专用（2026-10-28）：tap 可缺省，
     * 缺省 = 沿用出厂键行为（模型 tap 为 null）；自创布局仍必填。
     */
    fun validate(tree: JsonElement?, expectedId: String, allowMissingTap: Boolean = false): LayoutValidation {
        val issues = mutableListOf<LayoutIssue>()
        fun invalid(path: String, reason: String) {
            issues += LayoutIssue(path, reason, LayoutDisposition.INVALID_LAYOUT)
        }
        fun fallback(path: String, reason: String) {
            issues += LayoutIssue(path, reason, LayoutDisposition.FIELD_FALLBACK)
        }
        fun slotDropped(path: String, reason: String) {
            issues += LayoutIssue(path, reason, LayoutDisposition.SLOT_DROPPED)
        }

        val root = tree as? JsonObject
        if (root == null) {
            invalid("", if (tree == null) "unparseable" else "not_an_object")
            return LayoutValidation.Invalid(issues)
        }

        // ---- JSON 上限（定稿十一章，与导入时点整包拒绝同口径，§2.5-14） ----
        if (SkinJson.depth(root) > SkinValidator.MAX_JSON_DEPTH) {
            invalid("", "json_depth_exceeded")
        }
        if (SkinJson.maxStringLength(root) > SkinValidator.MAX_STRING_LENGTH) {
            invalid("", "json_string_too_long")
        }

        // ---- formatVersion（§2.5-1） ----
        val formatVersion = (root["formatVersion"] as? JsonPrimitive).strictIntOrNull()
        if (formatVersion != SkinValidator.SUPPORTED_FORMAT_VERSION) {
            invalid("formatVersion", "format_version_invalid")
        }

        // ---- meta（§2.5-2 / §2.1） ----
        var layoutName: String? = null
        val metaNode = root["meta"]
        val meta = metaNode as? JsonObject
        if (meta == null) {
            invalid("meta", if (metaNode == null) "meta_id_missing" else "meta_not_object")
        } else {
            val metaId = (meta["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (metaId == null) {
                invalid("meta.id", "meta_id_missing")
            } else if (metaId != expectedId) {
                invalid("meta.id", "meta_id_mismatch")
            }
            val nameNode = meta["name"]
            if (nameNode == null) {
                fallback("meta.name", "name_missing")
            } else {
                val raw = (nameNode as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (raw == null) {
                    fallback("meta.name", "name_not_string")
                } else {
                    layoutName = raw
                }
            }
        }
        val resolvedName = layoutName ?: expectedId

        // ---- 布局专用高度百分比（2026-10-28 编辑器布局改版） ----
        // 文件顶层 heightPercent，域 50–200；缺省 = 不指定；类型错/
        // 越界只字段回退（null），不牵连整布局（高度回退皮肤级/App 设置）。
        var layoutHeightPercent: Double? = null
        val heightNode = root["heightPercent"]
        if (heightNode != null) {
            val parsed = (heightNode as? JsonPrimitive)?.strictDoubleOrNull()
            if (parsed == null || !parsed.isFinite() || parsed < 50.0 || parsed > 200.0) {
                fallback("heightPercent", "height_percent_invalid")
            } else {
                layoutHeightPercent = parsed
            }
        }

        // ---- 横屏补丁声明（2026-11-02 工具项 6）：文件顶层 patchOf，
        // 指向本布局作为横屏补丁的竖屏布局 id；仅快照工厂的横屏
        // 绑定路径认领。形态非法（非字符串/空白/自指）只字段回退
        // （null + issue）；工厂横屏路径见原始声明仍在时按展开失败
        // 处理（回退竖屏布局，永不渲染半截布局）。
        var patchOf: String? = null
        val patchNode = root["patchOf"]
        if (patchNode != null) {
            val raw = (patchNode as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (raw.isNullOrBlank() || raw == expectedId) {
                fallback(
                    "patchOf",
                    if (raw == expectedId) "patch_of_self" else "patch_of_invalid",
                )
            } else {
                patchOf = raw
            }
        }

        // ---- rows 结构与硬限（§2.5-3/4/5） ----
        val rowsNode = root["rows"]
        val rowsArray = rowsNode as? JsonArray
        if (rowsArray == null) {
            invalid("rows", if (rowsNode == null) "rows_missing" else "rows_not_array")
            return LayoutValidation.Invalid(issues)
        }
        if (rowsArray.isEmpty()) invalid("rows", "rows_empty")
        if (rowsArray.size > MAX_ROWS) invalid("rows", "too_many_rows")

        val seenStableIds = mutableSetOf<String>()
        var totalKeys = 0
        val parsedRows = mutableListOf<List<LayoutKeyModel>?>()
        for ((rowIndex, rowNode) in rowsArray.withIndex()) {
            parsedRows += validateRow(
                rowNode = rowNode,
                rowIndex = rowIndex,
                totalRows = rowsArray.size,
                seenStableIds = seenStableIds,
                allowMissingTap = allowMissingTap,
                invalid = ::invalid,
                fallback = ::fallback,
                slotDropped = ::slotDropped,
            )
            totalKeys += countRowKeys(rowNode)
        }
        if (totalKeys > MAX_TOTAL_KEYS) invalid("rows", "too_many_keys")

        // ---- 跨行占位冲突（L1，§2.5-10 / §2.3）：矩形相交才冲突；
        // 冲突 span 字段级降级为普通键（布局保持有效），见
        // checkSpanConflicts。降级后的行用于最终模型。
        val resolvedRows = checkSpanConflicts(parsedRows, ::fallback)

        return if (issues.none { it.disposition == LayoutDisposition.INVALID_LAYOUT }) {
            LayoutValidation.Valid(
                layout = LayoutModel(
                    layoutId = expectedId,
                    name = resolvedName,
                    rows = resolvedRows.map { checkNotNull(it) },
                    heightPercent = layoutHeightPercent,
                    patchOf = patchOf,
                ),
                issues = issues,
            )
        } else {
            LayoutValidation.Invalid(issues)
        }
    }

    private fun countRowKeys(rowNode: JsonElement): Int =
        ((rowNode as? JsonObject)?.get("keys") as? JsonArray)?.size ?: 0

    private fun validateRow(
        rowNode: JsonElement,
        rowIndex: Int,
        totalRows: Int,
        seenStableIds: MutableSet<String>,
        allowMissingTap: Boolean,
        invalid: (String, String) -> Unit,
        fallback: (String, String) -> Unit,
        slotDropped: (String, String) -> Unit,
    ): List<LayoutKeyModel>? {
        val rowPath = "rows[$rowIndex]"
        val row = rowNode as? JsonObject
        if (row == null) {
            invalid(rowPath, "row_not_object")
            return null
        }
        val keysNode = row["keys"]
        val keysArray = keysNode as? JsonArray
        if (keysArray == null) {
            invalid("$rowPath.keys", if (keysNode == null) "keys_missing" else "keys_not_array")
            return null
        }
        if (keysArray.isEmpty()) invalid("$rowPath.keys", "keys_empty")
        if (keysArray.size > MAX_KEYS_PER_ROW) invalid("$rowPath.keys", "too_many_keys_in_row")

        val keys = mutableListOf<LayoutKeyModel>()
        var weightSum = 0.0
        var landscapeWeightSum = 0.0
        var weightsOk = true
        for ((keyIndex, keyNode) in keysArray.withIndex()) {
            val key = validateKey(
                keyNode = keyNode,
                path = "$rowPath.keys[$keyIndex]",
                rowIndex = rowIndex,
                totalRows = totalRows,
                seenStableIds = seenStableIds,
                allowMissingTap = allowMissingTap,
                invalid = invalid,
                fallback = fallback,
                slotDropped = slotDropped,
            )
            if (key == null) {
                weightsOk = false
            } else {
                keys += key
                weightSum += key.widthWeight
                landscapeWeightSum += key.landscapeWidthWeight ?: key.widthWeight
            }
        }
        // ---- 行权重和（§2.5-8）：双精度和须有限且在 [0.05, 200] ----
        if (weightsOk && keysArray.isNotEmpty()) {
            val portraitSumOk = weightSum.isFinite() &&
                weightSum >= MIN_ROW_WEIGHT_SUM - EPS &&
                weightSum <= MAX_ROW_WEIGHT_SUM + EPS
            if (!portraitSumOk) {
                invalid(rowPath, "row_weight_sum_invalid")
            }
            // 横屏有效权重和（2026-10-27 并入项）：越界只记 issue，
            // 不废布局——横屏笔误不应牵连竖屏可用的布局；构建期
            // 仍按比例归一化，宽度只会偏窄/偏宽。竖屏和已越界时
            // 整行已无效，不再叠加横屏 issue（避免同一行双重定性）。
            if (portraitSumOk && (!landscapeWeightSum.isFinite() ||
                landscapeWeightSum < MIN_ROW_WEIGHT_SUM - EPS ||
                landscapeWeightSum > MAX_ROW_WEIGHT_SUM + EPS)
            ) {
                fallback(rowPath, "landscape_row_weight_sum_invalid")
            }
        }
        return keys
    }

    /** @return 有效键模型；键本身结构无效（stableId/tap/权重/跨键）时返回 null。 */
    private fun validateKey(
        keyNode: JsonElement,
        path: String,
        rowIndex: Int,
        totalRows: Int,
        seenStableIds: MutableSet<String>,
        allowMissingTap: Boolean,
        invalid: (String, String) -> Unit,
        fallback: (String, String) -> Unit,
        slotDropped: (String, String) -> Unit,
    ): LayoutKeyModel? {
        val key = keyNode as? JsonObject
        if (key == null) {
            invalid(path, "key_not_object")
            return null
        }
        var structurallyValid = true

        // ---- stableId（§2.5-6）：缺失/非字符串/空白/文件内重复 → 整布局无效 ----
        val stableId = (key["stableId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (stableId.isNullOrBlank()) {
            invalid("$path.stableId", "stable_id_missing")
            structurallyValid = false
        } else if (!seenStableIds.add(stableId)) {
            invalid("$path.stableId", "stable_id_duplicate")
            structurallyValid = false
        }

        // ---- widthWeight（§2.5-7）：缺省 1.0；非有限数或越界不静默修复 ----
        // 审计 A 项：另接受对端分数写法 "a/b" 与 {"percentage": n}，
        // 换算为数值权重后进同一区间校验与构建期归一化（分数转
        // 权重无损：构建期只用相对比例，行和不变量由构建器守恒）。
        var weight = DEFAULT_WEIGHT
        val weightNode = key["widthWeight"]
        if (weightNode != null) {
            val parsed = parseWeight(weightNode)
            if (parsed == null || !parsed.isFinite() ||
                parsed < MIN_WEIGHT - EPS || parsed > MAX_WEIGHT + EPS
            ) {
                invalid("$path.widthWeight", "width_weight_invalid")
                structurallyValid = false
            } else {
                weight = parsed
            }
        }

        // ---- landscapeWidthWeight（2026-10-27 并入项）：缺省跟竖屏；
        // 无效只字段回退（跟竖屏）不牵连整布局——横屏笔误不应废掉竖屏可用的布局。
        var landscapeWeight: Double? = null
        val landscapeNode = key["landscapeWidthWeight"]
        if (landscapeNode != null) {
            val parsed = parseWeight(landscapeNode)
            if (parsed == null || !parsed.isFinite() ||
                parsed < MIN_WEIGHT - EPS || parsed > MAX_WEIGHT + EPS
            ) {
                fallback("$path.landscapeWidthWeight", "landscape_width_weight_invalid")
            } else {
                landscapeWeight = parsed
            }
        }

        // ---- style / styleOverrides（2026-10-27 并入项）：无效字段级回退 ----
        var styleRef: String? = null
        val styleNode = key["style"]
        if (styleNode != null) {
            val raw = (styleNode as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (raw.isNullOrEmpty()) {
                fallback("$path.style", "style_not_string")
            } else {
                styleRef = raw
            }
        }
        var styleOverrides: Map<com.jobeen.ime.skin.model.KeyStyleRole, String>? = null
        val styleOverridesNode = key["styleOverrides"]
        if (styleOverridesNode != null) {
            val obj = styleOverridesNode as? JsonObject
            if (obj == null) {
                fallback("$path.styleOverrides", "style_overrides_not_object")
            } else {
                val (roles, warnings) = KeyStyleParser.parseRoles(stableId ?: path, obj)
                for (warning in warnings) {
                    fallback(
                        "$path.styleOverrides" +
                            (warning.role?.let { ".${it.jsonName}" } ?: ""),
                        warning.reason,
                    )
                }
                if (roles.isNotEmpty()) styleOverrides = roles
            }
        }

        // ---- rowSpan（§2.5-9）：整数且 ∈ [1, 起始行起算的剩余行数] ----
        var rowSpan = 1
        val spanNode = key["rowSpan"]
        if (spanNode != null) {
            val parsed = (spanNode as? JsonPrimitive).strictDoubleOrNull()
            val remaining = totalRows - rowIndex
            if (parsed == null || !parsed.isFinite() || parsed % 1.0 != 0.0 ||
                parsed < 1.0 || parsed > remaining.toDouble()
            ) {
                invalid("$path.rowSpan", "row_span_invalid")
                structurallyValid = false
            } else {
                rowSpan = parsed.toInt()
            }
        }

        // ---- alignSpanEnd：缺省 false；类型错逐字段回退（不牵连整键） ----
        var alignSpanEnd = false
        val alignNode = key["alignSpanEnd"]
        if (alignNode != null) {
            val primitive = alignNode as? JsonPrimitive
            val parsed = primitive?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            if (parsed == null) {
                fallback("$path.alignSpanEnd", "align_span_end_not_boolean")
            } else {
                alignSpanEnd = parsed
            }
        }

        // ---- spanCol/spanColEnd（L1）：可选整数列区间；非法只字段
        // 回落旧口径（两值同丢），永不判整布局无效。0 ≤ spanCol <
        // spanColEnd；上界不限（逻辑列网，渲染期按行内键数钳制）。
        var spanCol: Int? = null
        var spanColEnd: Int? = null
        val spanColNode = key["spanCol"]
        val spanColEndNode = key["spanColEnd"]
        if (spanColNode != null || spanColEndNode != null) {
            // 与 rowSpan 同口径：接受整数值 double（2.0），拒绝 1.5／字符串。
            fun strictCol(node: JsonElement?): Int? =
                (node as? JsonPrimitive)?.strictDoubleOrNull()
                    ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
            val parsedCol = strictCol(spanColNode)
            val parsedEnd = strictCol(spanColEndNode)
            if (parsedCol == null || parsedEnd == null ||
                parsedCol < 0 || parsedEnd <= parsedCol
            ) {
                fallback("$path.spanCol", "span_col_invalid")
            } else {
                spanCol = parsedCol
                spanColEnd = parsedEnd
            }
        }

        // ---- tap（§2.5-11）：自创键必填且必须有效（无出厂行为可回退，
        // 升格整布局无效）；内置布局文件可缺省（allowMissingTap），
        // 缺省 = 沿用出厂键行为，模型 tap 为 null。
        var tap: SkinAction? = null
        val tapNode = key["tap"]
        if (tapNode == null) {
            if (!allowMissingTap) {
                invalid("$path.tap", "tap_missing")
                structurallyValid = false
            }
        } else {
            val parsed = SkinKeyMappingsParser.parseSingleAction(KeySlot.TAP, tapNode)
            when {
                parsed.action == null -> {
                    invalid(
                        "$path.tap",
                        "tap_invalid:${parsed.rejectReason?.name?.lowercase() ?: "unknown"}",
                    )
                    structurallyValid = false
                }
                parsed.action == SkinAction.SequencePlaceholder -> {
                    // sequence 占位不得作 tap（§6.6 先行落位；V10 后解析层
                    // 已直接以 sequence_removed 拒绝，不会走到本分支）
                    invalid("$path.tap", "tap_sequence_not_allowed")
                    structurallyValid = false
                }
                else -> tap = parsed.action
            }
        }

        // ---- label（§2.1 回退链）：文件 label → tap 文本值 → 空串，均逐项明示 ----
        var label: String? = null
        val labelNode = key["label"]
        if (labelNode != null) {
            val raw = (labelNode as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (raw == null) {
                fallback("$path.label", "label_not_string")
            } else {
                label = raw
            }
        }
        if (label == null) {
            label = (tap as? SkinAction.Text)?.value ?: ""
            fallback("$path.label", "label_missing")
        }

        // ---- 可选动作槽（§2.5-12）：无效只丢该槽/该元素 ----
        val swipeUp = parseOptionalSlot(key, "swipeUp", KeySlot.SWIPE_UP, path, slotDropped)
        val swipeDown = parseOptionalSlot(key, "swipeDown", KeySlot.SWIPE_DOWN, path, slotDropped)
        // 工具项 11：布局键槽位与 keyMappings 对齐（槽位复用口径
        // 与 keyMappings 解析一致：状态槽用 TAP，方向槽用对应方向）。
        val swipeLeft = parseOptionalSlot(key, "swipeLeft", KeySlot.SWIPE_LEFT, path, slotDropped)
        val swipeRight = parseOptionalSlot(key, "swipeRight", KeySlot.SWIPE_RIGHT, path, slotDropped)
        val preeditTap = parseOptionalSlot(key, "preeditTap", KeySlot.TAP, path, slotDropped)
        val preeditSwipeUp = parseOptionalSlot(key, "preeditSwipeUp", KeySlot.SWIPE_UP, path, slotDropped)
        val preeditSwipeDown = parseOptionalSlot(key, "preeditSwipeDown", KeySlot.SWIPE_DOWN, path, slotDropped)
        val preeditSwipeLeft = parseOptionalSlot(key, "preeditSwipeLeft", KeySlot.SWIPE_LEFT, path, slotDropped)
        val preeditSwipeRight = parseOptionalSlot(key, "preeditSwipeRight", KeySlot.SWIPE_RIGHT, path, slotDropped)
        val uppercasedTap = parseOptionalSlot(key, "uppercasedTap", KeySlot.TAP, path, slotDropped)
        val repeat = parseOptionalSlot(key, "repeat", KeySlot.TAP, path, slotDropped)
        val longPress = parseLongPressSlot(key, path, slotDropped)

        if (!structurallyValid) return null
        return LayoutKeyModel(
            stableId = checkNotNull(stableId),
            label = label,
            widthWeight = weight,
            rowSpan = rowSpan,
            alignSpanEnd = alignSpanEnd,
            spanCol = spanCol,
            spanColEnd = spanColEnd,
            tap = tap,
            swipeUp = swipeUp,
            swipeDown = swipeDown,
            longPress = longPress,
            swipeLeft = swipeLeft,
            swipeRight = swipeRight,
            preeditTap = preeditTap,
            preeditSwipeUp = preeditSwipeUp,
            preeditSwipeDown = preeditSwipeDown,
            preeditSwipeLeft = preeditSwipeLeft,
            preeditSwipeRight = preeditSwipeRight,
            uppercasedTap = uppercasedTap,
            repeat = repeat,
            landscapeWidthWeight = landscapeWeight,
            styleRef = styleRef,
            styleOverrides = styleOverrides,
        )
    }

    private fun parseOptionalSlot(
        key: JsonObject,
        field: String,
        slot: KeySlot,
        keyPath: String,
        slotDropped: (String, String) -> Unit,
    ): SkinAction? {
        val node = key[field] ?: return null
        val parsed = SkinKeyMappingsParser.parseSingleAction(slot, node)
        return when {
            parsed.action == null -> {
                slotDropped(
                    "$keyPath.$field",
                    parsed.rejectReason?.name?.lowercase() ?: "unknown",
                )
                null
            }
            parsed.action == SkinAction.SequencePlaceholder -> {
                // 占位保留不生效（B2 同口径）：槽位置空，文件节点原样保留
                slotDropped("$keyPath.$field", "sequence_placeholder")
                null
            }
            else -> parsed.action
        }
    }

    private fun parseLongPressSlot(
        key: JsonObject,
        keyPath: String,
        slotDropped: (String, String) -> Unit,
    ): List<SkinAction>? {
        val node = key["longPress"] ?: return null
        val parsed = SkinKeyMappingsParser.parseLongPressActions(node)
        for (reason in parsed.rejectReasons) {
            slotDropped("$keyPath.longPress", reason.name.lowercase())
        }
        var actions = parsed.actions ?: return null
        if (parsed.hasSequence || actions.any { it == SkinAction.SequencePlaceholder }) {
            slotDropped("$keyPath.longPress", "sequence_placeholder")
            actions = actions.filter { it != SkinAction.SequencePlaceholder }
        }
        return actions.ifEmpty { null }
    }

    /**
     * 跨行占位冲突（L1，§2.5-10 / §2.3）：矩形相交判定——行区间重叠
     * 且 列区间重叠（或同占右边缘）才冲突；冲突不再判整布局无效，
     * 改为字段级处理：后出现的冲突 span 降级为 rowSpan=1（保留在
     * 起始行作普通键、宽度不变，列信息清零），记 FIELD_FALLBACK
     * issue `span_conflict_downgraded`。降级后的键不再参与后续冲突
     * 判定。降级行之外的覆盖行 authored 和低于 1.0，差额由构建器
     * 空白键吸收（渲染总和仍为 1.0，无溢出）。
     *
     * 列区间：显式 spanCol/spanColEnd 用文件值；缺省沿用
     * alignSpanEnd 旧口径（左 → [0,1)，右 → [n,n+1)，n = 起始行
     * 行内键数）。右边缘 span（spanCol ≥ 起始行行内键数，含旧口径
     * 右 span）几何上同占行右缘：即使两 span 的 [n,n+1) 区间因
     * 起始行键数不同而错开，仍判冲突。
     *
     * @return 降级后的行（与入参同结构；null 行原样保留）。
     */
    private fun checkSpanConflicts(
        rows: List<List<LayoutKeyModel>?>,
        fallback: (String, String) -> Unit,
    ): List<List<LayoutKeyModel>?> {
        data class Span(
            val rowIndex: Int,
            val keyIndex: Int,
            val startRow: Int,
            val endRow: Int,
            val colStart: Int,
            val colEnd: Int,
            /** 右边缘 span：几何上同占行右缘（见上）。 */
            val rightEdge: Boolean,
            val path: String,
        )
        val mutableRows = rows.map { it?.toMutableList() }
        fun inRowCount(rowIndex: Int): Int =
            mutableRows[rowIndex]?.count { it.rowSpan == 1 } ?: 0
        val spans = mutableListOf<Span>()
        for ((rowIndex, row) in mutableRows.withIndex()) {
            row ?: continue
            for ((keyIndex, key) in row.withIndex()) {
                if (key.rowSpan <= 1) continue
                val n = inRowCount(rowIndex)
                val colStart = key.spanCol ?: if (key.alignSpanEnd) n else 0
                val colEnd = key.spanColEnd ?: (colStart + 1)
                spans += Span(
                    rowIndex = rowIndex,
                    keyIndex = keyIndex,
                    startRow = rowIndex,
                    endRow = rowIndex + key.rowSpan - 1,
                    colStart = colStart,
                    colEnd = colEnd,
                    rightEdge = colStart >= n,
                    path = "rows[$rowIndex].keys[$keyIndex].rowSpan",
                )
            }
        }
        val active = spans.toMutableList()
        var i = 0
        while (i < active.size) {
            val a = active[i]
            var j = i + 1
            while (j < active.size) {
                val b = active[j]
                val rowsOverlap = a.startRow <= b.endRow && b.startRow <= a.endRow
                val colsOverlap = a.colStart < b.colEnd && b.colStart < a.colEnd
                val bothRightEdge = a.rightEdge && b.rightEdge
                if (rowsOverlap && (colsOverlap || bothRightEdge)) {
                    // 后出现的降级为普通键（rowSpan=1，列信息清零）。
                    val row = mutableRows[b.rowIndex]!!
                    row[b.keyIndex] = row[b.keyIndex].copy(
                        rowSpan = 1, spanCol = null, spanColEnd = null,
                    )
                    fallback(b.path, "span_conflict_downgraded")
                    active.removeAt(j)
                    // 不 j++：移除后下一项顶替当前位置。
                } else {
                    j++
                }
            }
            i++
        }
        return mutableRows.map { it?.toList() }
    }

    /** 严格数值读取：JSON 字符串不算数值（与 SkinValidator 同口径）。 */
    private fun JsonPrimitive?.strictDoubleOrNull(): Double? =
        if (this == null || isString) null else doubleOrNull

    /**
     * 宽度权重读取：数值沿用严格口径；字符串/对象走对端分数
     * 解析（"a/b"、{"percentage": n}；纯数字字符串不算数，仍按
     * 严格读取口径无效）。其余形态返回 null，调用方按原处置
     * （竖屏无效整布局、横屏字段回退）。
     */
    private fun parseWeight(node: JsonElement): Double? =
        when (node) {
            is JsonPrimitive -> if (node.isString) {
                SizeFraction.parse(node)
            } else {
                node.strictDoubleOrNull()
            }
            is JsonObject -> SizeFraction.parse(node)
            else -> null
        }

    private fun JsonPrimitive?.strictIntOrNull(): Int? =
        if (this == null || isString) null else intOrNull
}
