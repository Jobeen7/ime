// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 长按符号网格（hintSymbolsGridStyle，对端网格版长按模型）：
 *
 * - symbolRows：行 × 列的符号样式名表；`~`（JSON null，或字符串
 *   "~"）为空单元格——不渲染、不可选中、不形成死区（选择判定
 *   落到最近非空格）；各行长度可不同，列数 = 最长行长度，短行
 *   末尾视为空单元格。
 * - size：单个单元格尺寸；缺省 = 按键可视区域尺寸（渲染期取）。
 * - spacing：单元格间距，缺省 0。
 * - insets：面板内边距，缺省 0。
 * - anchor：锚点格坐标（row/col 从 0 起）；该格中心与按键中心
 *   对齐（环绕布局靠中心空格实现）；缺省时面板整体水平居中
 *   显示在按键正上方（与旧版位置一致）。
 * - offset：锚点对齐后的面板整体微调，缺省 (0, 0)，y 正向下。
 * - selected：弹出时默认高亮格，同时是相对位移的起始格；缺省
 *   时弹出后无高亮，位移未超阈值即释放 = 取消。
 * - moveStep：位移灵敏度（横／纵各多少点换一格）；缺省 = 单元格
 *   步进（size + spacing）；横纵必须同时配置才生效，最小 1。
 * - moveThreshold：触发位移选择的最小移动距离，缺省 10。
 * - backgroundStyle / selectedBackgroundStyle /
 *   checkIfOverflowsParentHeight：与旧版同名参数同义。
 *
 * 与旧版的关系（双兼容）：长按触发时网格优先；未配置或配置
 * 无效时回落旧版 hintSymbolsStyle（见 [resolveHintGrid]）。
 * 本文件纯模型＋纯选择引擎，不触碰 View；面板挂载（手势跟踪、
 * 震动触发、边缘收容的视图侧）为后续项。
 * 纯函数，可直接单测。
 */
data class HintSymbolsGridStyle(
    val symbolRows: List<List<String?>>,
    val size: HintBubbleSize? = null,
    val spacingHorizontal: Float = 0f,
    val spacingVertical: Float = 0f,
    val insets: AreaInsets = AreaInsets(),
    val anchorRow: Int? = null,
    val anchorCol: Int? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val selectedRow: Int? = null,
    val selectedCol: Int? = null,
    val moveStepHorizontal: Float? = null,
    val moveStepVertical: Float? = null,
    val moveThreshold: Float = DEFAULT_MOVE_THRESHOLD,
    val backgroundStyle: String? = null,
    val selectedBackgroundStyle: String? = null,
    val checkIfOverflowsParentHeight: Boolean = true,
) {
    /** 列数 = 最长行长度。 */
    val columnCount: Int get() = symbolRows.maxOfOrNull { it.size } ?: 0
    /** 行数。 */
    val rowCount: Int get() = symbolRows.size

    /** 取格内容（越界／短行末尾皆为空）。 */
    fun cellAt(row: Int, col: Int): String? {
        if (row !in symbolRows.indices) return null
        val rowList = symbolRows[row]
        if (col !in rowList.indices) return null
        return rowList[col]
    }

    /** 是否为空网格（解析期已拦，此为防御）。 */
    val isEmpty: Boolean get() = symbolRows.all { row -> row.all { it == null } }

    companion object {
        const val DEFAULT_MOVE_THRESHOLD = 10f
    }
}

/** 长按网格样式的纯解析（非法字段级回退，永不抛错）。 */
object HintSymbolsGridParser {

    /** 解析网格样式节点：symbolRows 为空／缺失／全空时返回 null。 */
    fun parseStyle(node: JsonElement?): HintSymbolsGridStyle? {
        val obj = node as? JsonObject ?: return null
        val rowsNode = obj["symbolRows"] as? JsonArray ?: return null
        if (rowsNode.isEmpty()) return null
        val rows = mutableListOf<List<String?>>()
        for (rowNode in rowsNode) {
            val rowArray = rowNode as? JsonArray ?: return null
            val row = rowArray.map { cell ->
                when {
                    cell is JsonPrimitive && cell.isString -> {
                        val text = cell.content
                        if (text == "~" || text.isBlank()) null else text
                    }
                    cell is JsonPrimitive && !cell.isString -> null
                    else -> null
                }
            }
            rows += row
        }
        if (rows.all { row -> row.all { it == null } }) return null
        val anchor = parseCell(obj["anchor"])
        val selected = parseCell(obj["selected"])
        val moveStepH = parseStep(obj["moveStep"], "horizontal")
        val moveStepV = parseStep(obj["moveStep"], "vertical")
        // 横纵必须同时配置才生效（对端口径），否则双双置空。
        val (stepH, stepV) = if (moveStepH != null && moveStepV != null) {
            moveStepH to moveStepV
        } else {
            null to null
        }
        val spacing = obj["spacing"] as? JsonObject
        val offset = obj["offset"] as? JsonObject
        val threshold = (obj["moveThreshold"] as? JsonPrimitive)?.floatOrNull
            ?.takeIf { it >= 0f } ?: HintSymbolsGridStyle.DEFAULT_MOVE_THRESHOLD
        return HintSymbolsGridStyle(
            symbolRows = rows,
            size = HintSymbolsParser.parseSize(obj["size"]),
            spacingHorizontal = spacing?.floatEdge("horizontal") ?: 0f,
            spacingVertical = spacing?.floatEdge("vertical") ?: 0f,
            insets = HintSymbolsParser.parseInsets(obj["insets"]),
            anchorRow = anchor?.first,
            anchorCol = anchor?.second,
            offsetX = offset?.floatEdge("x") ?: 0f,
            offsetY = offset?.floatEdge("y") ?: 0f,
            selectedRow = selected?.first,
            selectedCol = selected?.second,
            moveStepHorizontal = stepH,
            moveStepVertical = stepV,
            moveThreshold = threshold,
            backgroundStyle = obj.stringField("backgroundStyle"),
            selectedBackgroundStyle = obj.stringField("selectedBackgroundStyle"),
            checkIfOverflowsParentHeight = (obj["checkIfOverflowsParentHeight"] as? JsonPrimitive)
                ?.booleanOrNull ?: true,
        )
    }

    private fun parseCell(node: JsonElement?): Pair<Int, Int>? {
        val obj = node as? JsonObject ?: return null
        val row = (obj["row"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
        val col = (obj["col"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
        return row to col
    }

    private fun parseStep(node: JsonElement?, axis: String): Float? {
        val obj = node as? JsonObject ?: return null
        return (obj[axis] as? JsonPrimitive)?.floatOrNull
            ?.takeIf { it.isFinite() && it >= 1f }
    }

    private fun JsonObject.floatEdge(name: String): Float? =
        (get(name) as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it >= 0f }

    private fun JsonObject.stringField(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/**
 * 网格相对位移选择引擎（纯函数；视图层只负责把手指位移喂进来、
 * 把返回的高亮格画出来、高亮变化时震动一次）：
 *
 * - 起始格：selected → anchor → 距按键中心最近的非空格（无几何
 *   输入时以网格中心格为按键中心近似，见 [initialCell]）；
 * - 位移未超阈值（两方向皆 ≤ moveThreshold）：维持初始态；
 * - 位移按 moveStep 换算为格步数（向零截断），从起始格出发；
 * - 目标格为空／短行缺口：高亮距离最近的非空格（曼哈顿距离，
 *   行优先平局取先行），间隙与空格不形成死区；
 * - 换算位置越界：清除高亮（返回 null），释放 = 取消；移回即
 *   按同一函数重算、可重新选中（函数无状态，移回自然恢复）；
 * - 面板显示位置（anchor/offset）只影响视觉，不影响选择判定。
 */
object HintGridSelection {

    /** 起始格：selected → anchor → 中心格最近的非空格。 */
    fun initialCell(style: HintSymbolsGridStyle): Pair<Int, Int>? {
        style.selectedRow?.let { row ->
            style.selectedCol?.let { col ->
                if (style.cellAt(row, col) != null) return row to col
            }
        }
        style.anchorRow?.let { row ->
            style.anchorCol?.let { col ->
                nearestNonEmpty(style, row, col)?.let { return it }
            }
        }
        val centerRow = style.rowCount / 2
        val centerCol = style.columnCount / 2
        return nearestNonEmpty(style, centerRow, centerCol)
    }

    /**
     * 按手指相对位移（dx/dy，点）算高亮格。
     *
     * @param origin 起始格（[initialCell] 的结果；null = 网格全空时无格）。
     * @param cellStepX/Y 单元格步进（size + spacing；调用方在渲染期
     *   传入实测步进，缺省 size 时传按键可视宽／高＋间距）。
     * @return 高亮格坐标；null = 无高亮（初始无高亮且未超阈值，或
     *   换算位置越界）。调用方以返回值变化判定是否震动。
     */
    fun selectByDisplacement(
        style: HintSymbolsGridStyle,
        origin: Pair<Int, Int>?,
        dx: Float,
        dy: Float,
        cellStepX: Float,
        cellStepY: Float,
    ): Pair<Int, Int>? {
        if (origin == null) return null
        if (abs(dx) <= style.moveThreshold && abs(dy) <= style.moveThreshold) {
            return originIfNonEmpty(style, origin)
        }
        val stepX = style.moveStepHorizontal?.takeIf { it > 0f } ?: cellStepX.takeIf { it > 0f }
            ?: return originIfNonEmpty(style, origin)
        val stepY = style.moveStepVertical?.takeIf { it > 0f } ?: cellStepY.takeIf { it > 0f }
            ?: return originIfNonEmpty(style, origin)
        val targetRow = origin.first + (dy / stepY).toInt()
        val targetCol = origin.second + (dx / stepX).toInt()
        if (targetRow !in 0 until style.rowCount || targetCol !in 0 until style.columnCount) {
            return null
        }
        return nearestNonEmpty(style, targetRow, targetCol)
    }

    private fun originIfNonEmpty(
        style: HintSymbolsGridStyle,
        origin: Pair<Int, Int>,
    ): Pair<Int, Int>? = if (style.cellAt(origin.first, origin.second) != null) origin else
        nearestNonEmpty(style, origin.first, origin.second)

    /** 距 (row, col) 最近的非空格（曼哈顿距离；平局先行后列）。 */
    fun nearestNonEmpty(style: HintSymbolsGridStyle, row: Int, col: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        for (r in 0 until style.rowCount) {
            for (c in 0 until style.columnCount) {
                if (style.cellAt(r, c) == null) continue
                val dist = abs(r - row) + abs(c - col)
                if (dist < bestDist) {
                    bestDist = dist
                    best = r to c
                }
            }
        }
        return best
    }

    /**
     * 边缘自动收容（纯几何）：面板左上角 (x, y) 在键盘区
     * (kbW × kbH) 内平移至完整显示；面板大于键盘时贴左上。
     */
    fun containPanel(
        x: Float,
        y: Float,
        panelW: Float,
        panelH: Float,
        kbW: Float,
        kbH: Float,
    ): Pair<Float, Float> {
        val cx = if (panelW >= kbW) 0f else min(max(x, 0f), kbW - panelW)
        val cy = if (panelH >= kbH) 0f else min(max(y, 0f), kbH - panelH)
        return cx to cy
    }
}

/**
 * 长按模型解析口径：网格优先于旧版单行。
 * 网格样式名非空 → 网格；否则旧版样式名（可空）。
 */
fun resolveHintGrid(
    gridStyleName: String?,
    legacyStyleName: String?,
): HintGridChoice = when {
    !gridStyleName.isNullOrBlank() -> HintGridChoice.Grid(gridStyleName)
    !legacyStyleName.isNullOrBlank() -> HintGridChoice.Legacy(legacyStyleName)
    else -> HintGridChoice.None
}

/** 长按面板模型选择结果。 */
sealed interface HintGridChoice {
    data class Grid(val styleName: String) : HintGridChoice
    data class Legacy(val styleName: String) : HintGridChoice
    data object None : HintGridChoice
}
