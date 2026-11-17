package com.jobeen.ime.input.keyboard.key

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import splitties.dimensions.dp
import kotlin.math.max
import kotlin.math.min

/**
 * 长按网格面板视图（KeyboardPopup 的网格版复用范式）：
 * 容器／窗口／行／格视图按形状复用，颜色／文案只更新属性。
 *
 * - 行 × 列矩形格：短行末尾与空格皆为 INVISIBLE 占位格
 *   （保持网格对齐；对端“~不渲染”指无内容，不塌布局）；
 * - 格背景：未选中时为节点面色（无面色时透明），选中时为
 *   选中背景（选中色替换面色，不叠加——单背景槽位所限）；
 * - 位置：锚点格中心与按键中心对齐＋offset 微调；无锚点时
 *   整体居中于按键正上方；再按键盘区收容（checkIfOverflows
 *   开启且键盘尺寸已知时；关闭时保持算出位置）；
 * - 选择状态只由调用方经 [selectCell]/[clearSelection] 驱动。
 * （注：show() 随皮肤 v3 网格面板删除；本类仅保留状态与收容函数。）
 */
class HintGridPopup(private val context: Context) {

    private var popupWindow: PopupWindow? = null
    private var container: LinearLayout? = null
    private var containerBg: GradientDrawable? = null
    private var windowBg: GradientDrawable? = null
    private var rowViews: List<LinearLayout> = emptyList()
    private var cellViews: List<List<TextView>> = emptyList()
    private var cellBgDrawables: List<List<GradientDrawable>> = emptyList()
    private var cellFaceColors: List<List<Int?>> = emptyList()
    private var selectable: List<List<Boolean>> = emptyList()
    var selectedCell: Pair<Int, Int>? = null
        private set
    private var selectedColor: Int = 0
    private var cellStepXPx: Float = 0f
    private var cellStepYPx: Float = 0f

    private val selectedCellBg = GradientDrawable()
    private val loc = IntArray(2)
    private val kbLoc = IntArray(2)

    /** 单元格步进（格宽＋横间距；位移引擎的 cellStepX）。 */
    val cellStepX: Float get() = cellStepXPx

    /** 单元格步进（格高＋纵间距；位移引擎的 cellStepY）。 */
    val cellStepY: Float get() = cellStepYPx

    @Suppress("LongParameterList")

    fun dismiss() {
        popupWindow?.dismiss()
    }

    /** 选中格（无效格／空格返回 false，选中态不变）。 */
    fun selectCell(row: Int, col: Int): Boolean {
        if (selectable.getOrNull(row)?.getOrNull(col) != true) return false
        if (selectedCell == row to col) return true
        selectedCell = row to col
        applyCellBackgrounds()
        return true
    }

    /** 清除高亮（越界取消时的视觉态；下次 show() 重算初始选中）。 */
    fun clearSelection() {
        selectedCell = null
        applyCellBackgrounds()
    }

    private fun applyCellBackgrounds() {
        cellViews.forEachIndexed { row, cellRow ->
            cellRow.forEachIndexed { col, cellView ->
                if (cellView.background === selectedCellBg) {
                    // 摘下旧选中背景，恢复该格面色（无面色时透明）
                    val faceColor = cellFaceColors.getOrNull(row)?.getOrNull(col)
                    cellView.background = if (faceColor == null) null
                    else cellBgDrawables[row][col].apply { setColor(faceColor) }
                }
            }
        }
        selectedCellBg.setColor(selectedColor)
        selectedCellBg.cornerRadius = context.dp(5f)
        val selected = selectedCell
        if (selected != null) {
            cellViews.getOrNull(selected.first)?.getOrNull(selected.second)?.background =
                selectedCellBg
        }
    }

    fun isShowing(): Boolean = popupWindow?.isShowing == true
}

/**
 * 面板 containment（自 HintSymbolsGrid.HintGridSelection 迁入，
 * 值与口径一字未改；网格面板其余部分随皮肤 v3 死壳删除）。
 */
    /**
     * 边缘自动收容（纯几何）：面板左上角 (x, y) 在键盘区
     * (kbW × kbH) 内平移至完整显示；面板大于键盘时贴左上。
     */
private fun containPanel(
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
