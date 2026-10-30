// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlin.math.floor

/**
 * 长按符号格解析（重点候选 8-6，纯函数）：
 *
 * 格子由运行层按某键的长按候选构造（cells 行主序，null = 空位格）：
 * - 锚点对齐：锚格（anchorIndex）中心对齐按键中心；
 * - 位移原点：手指落点对应默认高亮格（defaultIndex），位移按格
 *   距换算步数（灵敏度 sensitivity：步距 = 格宽／灵敏度；抖动
 *   jitterPx：位移小于抖动不换格）；
 * - 无间隙死区：格间间隙不参与判定（位移步数制）；目标格为空时
 *   取最近非空邻格（切比雪夫环由近及远、环内行主序），全空则取消；
 * - 超界取消：目标格出界即取消（返回 null），回界内恢复；
 * - 边缘自动移入：布局时整格钳制进可见区（格比屏宽时左对齐）；
 * - 震动：本模块无副作用，高亮变化时调用方震动一次（比较上次
 *   highlightFor 结果即可判定变化）。
 *
 * 4 角选择（符号栏键长按）：对角位移才选角（dx、dy 均须达到抖动），
 * 象限定角；未配的角不选（返回 null）。
 */
data class SymbolGridSpec(
    val cells: List<String?>,
    val columns: Int,
    val anchorIndex: Int,
    val defaultIndex: Int,
    val cellSizePx: Float,
    val sensitivity: Float,
    val jitterPx: Float,
) {
    val rows: Int get() = (cells.size + columns - 1) / columns

    companion object {
        /** 非法规格返回 null（调用方回退默认格，绝不崩）。 */
        fun create(
            cells: List<String?>,
            columns: Int,
            anchorIndex: Int,
            defaultIndex: Int,
            cellSizePx: Float,
            sensitivity: Float,
            jitterPx: Float,
        ): SymbolGridSpec? {
            if (cells.isEmpty() || columns <= 0) return null
            if (anchorIndex !in cells.indices || defaultIndex !in cells.indices) return null
            if (!cellSizePx.isFinite() || cellSizePx <= 0f) return null
            if (!sensitivity.isFinite() || sensitivity <= 0f) return null
            if (!jitterPx.isFinite() || jitterPx < 0f) return null
            return SymbolGridSpec(
                cells, columns, anchorIndex, defaultIndex, cellSizePx, sensitivity, jitterPx,
            )
        }
    }
}

data class SymbolGridLayout(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val columns: Int,
    val rows: Int,
)

object SymbolGridResolver {

    /** 锚格中心对齐按键中心，再整格钳制进可见区。 */
    fun layoutGrid(
        spec: SymbolGridSpec,
        anchorCenterX: Float,
        anchorCenterY: Float,
        screenWidth: Float,
        screenHeight: Float,
    ): SymbolGridLayout {
        val gridWidth = spec.columns * spec.cellSizePx
        val gridHeight = spec.rows * spec.cellSizePx
        val anchorCol = spec.anchorIndex % spec.columns
        val anchorRow = spec.anchorIndex / spec.columns
        val rawLeft = anchorCenterX - (anchorCol + 0.5f) * spec.cellSizePx
        val rawTop = anchorCenterY - (anchorRow + 0.5f) * spec.cellSizePx
        val left = if (gridWidth >= screenWidth) 0f else rawLeft.coerceIn(0f, screenWidth - gridWidth)
        val top = if (gridHeight >= screenHeight) 0f else rawTop.coerceIn(0f, screenHeight - gridHeight)
        return SymbolGridLayout(left, top, gridWidth, gridHeight, spec.columns, spec.rows)
    }

    /** 位移 → 高亮格下标；出界／全空 → null（取消）。 */
    fun highlightFor(spec: SymbolGridSpec, dx: Float, dy: Float): Int? {
        val stepPx = spec.cellSizePx / spec.sensitivity
        val stepsX = steps(dx, spec.jitterPx, stepPx)
        val stepsY = steps(dy, spec.jitterPx, stepPx)
        val defaultCol = spec.defaultIndex % spec.columns
        val defaultRow = spec.defaultIndex / spec.columns
        val targetCol = defaultCol + stepsX
        val targetRow = defaultRow + stepsY
        if (targetCol !in 0 until spec.columns || targetRow !in 0 until spec.rows) {
            return null
        }
        val target = targetRow * spec.columns + targetCol
        if (spec.cells.getOrNull(target) != null) return target
        // 目标格为空：最近非空邻格（切比雪夫环由近及远、环内行主序）
        val maxRing = maxOf(spec.columns, spec.rows)
        for (ring in 1..maxRing) {
            for (row in targetRow - ring..targetRow + ring) {
                for (col in targetCol - ring..targetCol + ring) {
                    if (maxOf(kotlin.math.abs(row - targetRow), kotlin.math.abs(col - targetCol)) != ring) {
                        continue
                    }
                    if (col !in 0 until spec.columns || row !in 0 until spec.rows) continue
                    val index = row * spec.columns + col
                    if (spec.cells.getOrNull(index) != null) return index
                }
            }
        }
        return null
    }

    private fun steps(d: Float, jitterPx: Float, stepPx: Float): Int {
        val abs = kotlin.math.abs(d)
        if (abs < jitterPx) return 0
        val steps = 1 + floor((abs - jitterPx) / stepPx).toInt()
        return if (d < 0) -steps else steps
    }
}

/** 符号栏键 4 角选择：corners 顺序左上／右上／左下／右下。 */
object CornerSelector {

    /** 对角位移才选角；位移不足或该角未配 → null。 */
    fun select(dx: Float, dy: Float, jitterPx: Float, cornerCount: Int): Int? {
        if (kotlin.math.abs(dx) < jitterPx || kotlin.math.abs(dy) < jitterPx) return null
        val index = when {
            dx < 0 && dy < 0 -> 0
            dx > 0 && dy < 0 -> 1
            dx < 0 && dy > 0 -> 2
            else -> 3
        }
        return index.takeIf { it < cornerCount }
    }
}
