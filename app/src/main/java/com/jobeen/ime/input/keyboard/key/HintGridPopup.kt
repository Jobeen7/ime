package com.jobeen.ime.input.keyboard.key

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.jobeen.ime.skin.model.HintGridSelection
import com.jobeen.ime.skin.runtime.HintSymbolsResolver
import splitties.dimensions.dp

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
 * - 选择状态只由调用方经 [selectCell]/[clearSelection] 驱动，
 *   本类不做位移换算（换算归 [HintGridSelection]）。
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
    fun show(
        anchor: View,
        keyboardView: View,
        panel: HintSymbolsResolver.GridPanel,
        cellWidthPx: Int,
        cellHeightPx: Int,
        spacingHPx: Int,
        spacingVPx: Int,
        insetLeftPx: Int,
        insetTopPx: Int,
        insetRightPx: Int,
        insetBottomPx: Int,
        textColor: Int,
        bgColor: Int,
        selectedBgColor: Int,
        initialSelectedCell: Pair<Int, Int>?,
    ) {
        popupWindow?.dismiss()

        val style = panel.style
        val columnCount = style.columnCount
        val rowCount = style.rowCount
        selectedColor = selectedBgColor
        cellStepXPx = (cellWidthPx + spacingHPx).toFloat()
        cellStepYPx = (cellHeightPx + spacingVPx).toFloat()
        selectedCell = initialSelectedCell?.takeIf { (row, col) ->
            row in 0 until rowCount && col in 0 until columnCount &&
                panel.rows.getOrNull(row)?.getOrNull(col) != null
        }

        val popupRadius = context.dp(8f)
        val cellRadius = context.dp(5f)
        val cont = container ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }.also { container = it }
        cont.setPadding(insetLeftPx, insetTopPx, insetRightPx, insetBottomPx)
        val cbg = containerBg ?: GradientDrawable().also { containerBg = it }
        cbg.setColor(bgColor)
        cbg.cornerRadius = popupRadius
        if (cont.background !== cbg) cont.background = cbg

        // 形状变化才重建行／格视图，文案／颜色逐格刷新
        if (rowViews.size != rowCount || cellViews.firstOrNull()?.size != columnCount) {
            cont.removeAllViews()
            val rows = ArrayList<LinearLayout>(rowCount)
            val cells = ArrayList<List<TextView>>(rowCount)
            val bgs = ArrayList<List<GradientDrawable>>(rowCount)
            repeat(rowCount) { row ->
                val rowView = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val cellList = ArrayList<TextView>(columnCount)
                val bgList = ArrayList<GradientDrawable>(columnCount)
                repeat(columnCount) { col ->
                    val cellView = TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                        setIncludeFontPadding(false)
                    }
                    cellList.add(cellView)
                    bgList.add(GradientDrawable().apply { cornerRadius = cellRadius })
                    rowView.addView(
                        cellView,
                        LinearLayout.LayoutParams(cellWidthPx, cellHeightPx).apply {
                            if (col > 0) marginStart = spacingHPx
                        },
                    )
                }
                cells.add(cellList)
                bgs.add(bgList)
                rows.add(rowView)
                cont.addView(
                    rowView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (row > 0) topMargin = spacingVPx
                    },
                )
            }
            rowViews = rows
            cellViews = cells
            cellBgDrawables = bgs
        }
        selectable = panel.rows.map { row ->
            List(columnCount) { col -> row.getOrNull(col) != null }
        }
        cellFaceColors = panel.rows.map { row ->
            List(columnCount) { col -> row.getOrNull(col)?.faceColor }
        }
        // 格尺寸变化时刷新已有格的 LayoutParams（形状复用不重建）
        rowViews.forEachIndexed { row, rowView ->
            if (row > 0) {
                val rowParams = rowView.layoutParams as LinearLayout.LayoutParams
                if (rowParams.topMargin != spacingVPx) {
                    rowParams.topMargin = spacingVPx
                    rowView.layoutParams = rowParams
                }
            }
            cellViews[row].forEachIndexed { col, cellView ->
                val params = cellView.layoutParams as LinearLayout.LayoutParams
                if (params.width != cellWidthPx || params.height != cellHeightPx ||
                    params.marginStart != if (col > 0) spacingHPx else 0
                ) {
                    params.width = cellWidthPx
                    params.height = cellHeightPx
                    if (col > 0) params.marginStart = spacingHPx
                    cellView.layoutParams = params
                }
                val cell = panel.rows.getOrNull(row)?.getOrNull(col)
                cellView.text = cell?.label ?: ""
                cellView.setTextColor(cell?.textColor ?: textColor)
                cellView.visibility = if (cell == null) View.INVISIBLE else View.VISIBLE
                val faceColor = cellFaceColors[row][col]
                if (faceColor == null) {
                    cellView.background = null
                } else {
                    cellBgDrawables[row][col].setColor(faceColor)
                    cellView.background = cellBgDrawables[row][col]
                }
            }
        }
        applyCellBackgrounds()

        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        cont.measure(measureSpec, measureSpec)
        val totalWidth = cont.measuredWidth
        val totalHeight = cont.measuredHeight

        anchor.getLocationInWindow(loc)
        val anchorCenterX = loc[0] + anchor.width / 2
        val anchorCenterY = loc[1] + anchor.height / 2
        val anchorTop = loc[1]
        val gap = context.dp(6)
        val offsetX = context.dp(style.offsetX)
        val offsetY = context.dp(style.offsetY)
        var popupX: Float
        var popupY: Float
        val anchorRow = style.anchorRow
        val anchorCol = style.anchorCol
        if (anchorRow != null && anchorCol != null &&
            anchorRow in 0 until rowCount && anchorCol in 0 until columnCount
        ) {
            // 锚点格中心与按键中心对齐（锚点格可为空格，几何照算）
            val anchorCellCenterX = insetLeftPx +
                anchorCol * (cellWidthPx + spacingHPx) + cellWidthPx / 2f
            val anchorCellCenterY = insetTopPx +
                anchorRow * (cellHeightPx + spacingVPx) + cellHeightPx / 2f
            popupX = anchorCenterX - anchorCellCenterX + offsetX
            popupY = anchorCenterY - anchorCellCenterY + offsetY
        } else {
            popupX = anchorCenterX - totalWidth / 2f + offsetX
            popupY = anchorTop - totalHeight - gap + offsetY
        }
        if (style.checkIfOverflowsParentHeight &&
            keyboardView.width > 0 && keyboardView.height > 0
        ) {
            keyboardView.getLocationInWindow(kbLoc)
            val contained = HintGridSelection.containPanel(
                popupX - kbLoc[0], popupY - kbLoc[1],
                totalWidth.toFloat(), totalHeight.toFloat(),
                keyboardView.width.toFloat(), keyboardView.height.toFloat(),
            )
            popupX = contained.first + kbLoc[0]
            popupY = contained.second + kbLoc[1]
        }

        val wbg = windowBg ?: GradientDrawable().also { windowBg = it }
        wbg.setColor(bgColor)
        wbg.cornerRadius = popupRadius
        val pw = popupWindow ?: PopupWindow(cont, totalWidth, totalHeight, false).apply {
            isOutsideTouchable = false
            isTouchable = false
            elevation = context.dp(8f)
        }.also { popupWindow = it }
        if (pw.contentView !== cont) pw.contentView = cont
        pw.setBackgroundDrawable(wbg)
        pw.width = totalWidth
        pw.height = totalHeight
        pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, popupX.toInt(), popupY.toInt())
    }

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
