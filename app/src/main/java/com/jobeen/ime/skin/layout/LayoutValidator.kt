package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.KeySlot
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
    fun validate(tree: JsonElement?, expectedId: String): LayoutValidation {
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
                invalid = ::invalid,
                fallback = ::fallback,
                slotDropped = ::slotDropped,
            )
            totalKeys += countRowKeys(rowNode)
        }
        if (totalKeys > MAX_TOTAL_KEYS) invalid("rows", "too_many_keys")

        // ---- 跨行占位冲突（§2.5-10 / §2.3）：同侧覆盖区间重叠 → 整布局无效 ----
        checkSpanConflicts(parsedRows, ::invalid)

        return if (issues.none { it.disposition == LayoutDisposition.INVALID_LAYOUT }) {
            LayoutValidation.Valid(
                layout = LayoutModel(
                    layoutId = expectedId,
                    name = resolvedName,
                    rows = parsedRows.map { checkNotNull(it) },
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
        var weightsOk = true
        for ((keyIndex, keyNode) in keysArray.withIndex()) {
            val key = validateKey(
                keyNode = keyNode,
                path = "$rowPath.keys[$keyIndex]",
                rowIndex = rowIndex,
                totalRows = totalRows,
                seenStableIds = seenStableIds,
                invalid = invalid,
                fallback = fallback,
                slotDropped = slotDropped,
            )
            if (key == null) {
                weightsOk = false
            } else {
                keys += key
                weightSum += key.widthWeight
            }
        }
        // ---- 行权重和（§2.5-8）：双精度和须有限且在 [0.05, 200] ----
        if (weightsOk && keysArray.isNotEmpty()) {
            if (!weightSum.isFinite() ||
                weightSum < MIN_ROW_WEIGHT_SUM - EPS ||
                weightSum > MAX_ROW_WEIGHT_SUM + EPS
            ) {
                invalid(rowPath, "row_weight_sum_invalid")
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
        var weight = DEFAULT_WEIGHT
        val weightNode = key["widthWeight"]
        if (weightNode != null) {
            val parsed = (weightNode as? JsonPrimitive).strictDoubleOrNull()
            if (parsed == null || !parsed.isFinite() ||
                parsed < MIN_WEIGHT - EPS || parsed > MAX_WEIGHT + EPS
            ) {
                invalid("$path.widthWeight", "width_weight_invalid")
                structurallyValid = false
            } else {
                weight = parsed
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

        // ---- tap（§2.5-11）：必填且必须有效；自创键无出厂行为可回退，升格整布局无效 ----
        var tap: SkinAction? = null
        val tapNode = key["tap"]
        if (tapNode == null) {
            invalid("$path.tap", "tap_missing")
            structurallyValid = false
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
        val longPress = parseLongPressSlot(key, path, slotDropped)

        if (!structurallyValid) return null
        return LayoutKeyModel(
            stableId = checkNotNull(stableId),
            label = label,
            widthWeight = weight,
            rowSpan = rowSpan,
            alignSpanEnd = alignSpanEnd,
            tap = checkNotNull(tap),
            swipeUp = swipeUp,
            swipeDown = swipeDown,
            longPress = longPress,
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

    private fun checkSpanConflicts(
        rows: List<List<LayoutKeyModel>?>,
        invalid: (String, String) -> Unit,
    ) {
        data class Span(val startRow: Int, val endRow: Int, val alignEnd: Boolean, val path: String)
        val spans = mutableListOf<Span>()
        for ((rowIndex, row) in rows.withIndex()) {
            row ?: continue
            for ((keyIndex, key) in row.withIndex()) {
                if (key.rowSpan > 1) {
                    spans += Span(
                        startRow = rowIndex,
                        endRow = rowIndex + key.rowSpan - 1,
                        alignEnd = key.alignSpanEnd,
                        path = "rows[$rowIndex].keys[$keyIndex].rowSpan",
                    )
                }
            }
        }
        for (i in spans.indices) {
            for (j in i + 1 until spans.size) {
                val a = spans[i]
                val b = spans[j]
                if (a.alignEnd == b.alignEnd && a.startRow <= b.endRow && b.startRow <= a.endRow) {
                    invalid(b.path, "span_conflict")
                }
            }
        }
    }

    /** 严格数值读取：JSON 字符串不算数值（与 SkinValidator 同口径）。 */
    private fun JsonPrimitive?.strictDoubleOrNull(): Double? =
        if (this == null || isString) null else doubleOrNull

    private fun JsonPrimitive?.strictIntOrNull(): Int? =
        if (this == null || isString) null else intOrNull
}
