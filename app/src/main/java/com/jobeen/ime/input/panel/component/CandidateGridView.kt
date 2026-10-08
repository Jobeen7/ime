package com.jobeen.ime.input.panel.component

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import com.jobeen.ime.base.util.slideUpCollapse
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.SidePanelKeyView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
class CandidateGridView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    var onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    var onSidePanelAction: ((KeyboardAction) -> Unit)? = null,
    var subscribePossibleCandidatePinYin: Boolean = true,
    var maxVisibleRow: Int = 5,
    var maxVisibleColumn: Int = 5,   // 保留并真正使用
) : ComponentView(context, colors) {

    // ── 布局数据类 ──
    private class WordPos(
        val row: Int, val xStart: Float, val width: Float,        // 分配后的显示宽度
        val extraWide: Boolean   // 是否超宽独占一行
    )

    private var allCandidates: List<EngineMessage.Candidate> = emptyList()
    var onCandidatesReordered: ((List<EngineMessage.Candidate>) -> Unit)? = null
    var onDragComplete: ((List<EngineMessage.Candidate>) -> Unit)? = null
    var onWordForget: ((EngineMessage.Candidate, Float, Float) -> Unit)? = null

    /**
     * 候选在本机是否可显示：只判定单个 CJK 表意字——用网格实际绘制的
     * Paint 查字体字形，设备字体无字形的扩展区生僻字（渲染成方框）判
     * 为不可显示。多字词、符号、emoji、字母数字一律不动（词组即使含
     * 个别生僻字也是可辨认的整体，且不在方框问题的主体范围内）。
     */
    fun isDisplayable(candidate: EngineMessage.Candidate): Boolean {
        val text = candidate.text
        if (text.codePointCount(0, text.length) != 1) return true
        if (text.codePointAt(0) < 0x3400) return true
        return gridCanvas.hasGlyph(text)
    }

    /** 引擎侧是否还有未取的候选（面板在每次整表/追加更新时下发） */
    var hasMoreCandidates: Boolean = false
    /** 滚动接近底部且 hasMoreCandidates 时触发，由面板转给引擎补取下一页 */
    var onNeedMoreCandidates: (() -> Unit)? = null
    /** 本批已发出补取请求、等列表更新后复位，避免同一位置反复触发 */
    private var needMoreRequested: Boolean = false
    private var needMoreCheckPosted: Boolean = false

    private val sidePanelKey = SidePanelKeyView(
        context, colors,
        KeyDef.Appearance.SidePannel(
            rowSpan = 4,
            visableRow = 5,
            margin = false,
            variant = KeyDef.Appearance.Variant.None,
            border = KeyDef.Appearance.Border.Off
        ),
    ).apply { setOnItemActionListener { action -> onSidePanelAction?.invoke(action) } }

    private val sidePanelPunctuationItems: List<KeyDef>

    // ── 画布核心 ──
    private val gridCanvas = object : View(context) {
        private var pressedIndex = -1
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private var rowH = 0f
        private var cellPad = 0f
        private var minWordSpace = 0f

        var positions = emptyList<WordPos>()
            private set

        // 布局期测得的文本宽度缓存：绘制/命中测试直接查表，
        // 不再每帧对每个候选重复 measureText
        private var textWidths = FloatArray(0)

        // 每行第一个候选在 positions 中的下标：绘制从可见行开始、命中测试只扫一行
        private var rowStartIndex = IntArray(0)

        private val rowScrollX = mutableMapOf<Int, Float>()
        // 当前手势的指针 id：只追踪第一根落指，其他指针不得串扰坐标与收尾
        private var activePointerId = -1

        private var downX = 0f
        private var horizontalDrag = -1

        private var visibleRows = 0
        private val scroller = OverScroller(context)
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
        private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
        private var velocityTracker: VelocityTracker? = null
        private var scrollOffsetY = 0f
        private var lastY = 0f
        private var downY = 0f
        private var downIndex = -1
        private var dragging = false
        private var longPressTriggered = false
        private var longPressMoved = false
        private var longPressIndex = -1
        private var dragIndex = -1
        private var dragTargetIndex = -1
        private var dragFingerX = 0f
        private var dragFingerY = 0f
        private val longPressRunnable = Runnable {
            longPressTriggered = true
            longPressIndex = if (!dragging && horizontalDrag < 0) downIndex else -1
            invalidate()
        }
        private var stretch = 0f
        private var stretchAnimator: ValueAnimator? = null
        private var shakePhase = 0f
        private var shakeRunnable: Runnable? = null
        private var shakeMaxAngle = 2f
        private val overScrollLimit get() = max(height * 0.45f, 1f)

        // 缓存：旧实现每次访问都全量扫描 positions 求最大行（拖动 MOVE 与
        // computeScroll 每帧多次访问）；布局或高度变化时才重算
        private var maxScrollCache: Float? = null

        private val maxScroll
            get(): Float {
                maxScrollCache?.let { return it }
                val totalRows = positions.maxOfOrNull { it.row }?.plus(1) ?: visibleRows
                return ((totalRows * rowH - height).coerceAtLeast(0f)).also { maxScrollCache = it }
            }

        fun updateColors(pc: KeyboardColors.ColorScheme.PanelColors) {
            bgPaint.color = pc.background
            textPaint.color = pc.candidateText
            sepPaint.color = pc.candidateDivider
            pressPaint.color = pc.candidateBackground
        }

        fun recomputeLayout() {
            maxScrollCache = null
            if (width <= 0 || allCandidates.isEmpty()) {
                positions = emptyList()
                textWidths = FloatArray(0)
                rowStartIndex = IntArray(0)
                return
            }

            val rowWidth = width.toFloat()
            // 文本宽度只在这里测一次，绘制与命中测试复用缓存
            textWidths = FloatArray(allCandidates.size) {
                textPaint.measureText(allCandidates[it].text)
            }
            // 每个候选词原始宽度 = 文本宽度 + 左右内边距
            val rawWidths = FloatArray(allCandidates.size) {
                textWidths[it] + cellPad * 2f
            }

            val tempPositions = mutableListOf<WordPos>()
            var i = 0
            var row = 0

            while (i < allCandidates.size) {
                val firstW = rawWidths[i]
                // 超宽词：独自一行，可水平滚动
                if (firstW > rowWidth) {
                    tempPositions.add(WordPos(row, 0f, firstW, true))
                    i++
                    row++
                    continue
                }

                // 收集一行内尽可能多的词，但不超过 maxVisibleColumn 个
                val lineIndices = mutableListOf(i)
                var accumulatedRaw = firstW
                i++
                while (i < allCandidates.size && lineIndices.size < maxVisibleColumn) {
                    val curW = rawWidths[i]
                    if (accumulatedRaw + minWordSpace + curW <= rowWidth) {
                        lineIndices.add(i)
                        accumulatedRaw += curW
                        i++
                    } else {
                        break
                    }
                }

                // 均匀分配剩余空间到每个词块
                val n = lineIndices.size
                val extraEach = (rowWidth - accumulatedRaw) / n
                val avgW = rowWidth / n
                // 预判膨胀后的总宽是否超标
                var inflatedTotal = 0f
                for (idx in lineIndices) {
                    inflatedTotal += if (rawWidths[idx] < avgW) avgW else rawWidths[idx]
                }
                val canInflate = inflatedTotal <= rowWidth

                var x = 0f
                for (idx in lineIndices) {
                    var w = rawWidths[idx] + extraEach
                    if (canInflate && rawWidths[idx] < avgW) {
                        w = avgW
                    }
                    tempPositions.add(WordPos(row, x, w, false))
                    x += w
                }
                row++
            }

            positions = tempPositions
            // 行首下标表：行数 = 最大行号 + 1
            val rowCount = (tempPositions.maxOfOrNull { it.row } ?: -1) + 1
            rowStartIndex = IntArray(rowCount) { positions.size }
            for (i in tempPositions.indices) {
                val r = tempPositions[i].row
                if (i < rowStartIndex[r]) rowStartIndex[r] = i
            }
            rowScrollX.clear()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            maxScrollCache = null
            val d = resources.displayMetrics.density
            textPaint.textSize = 17f * d
            cellPad = 6f * d
            minWordSpace = 8f * d   // 词间最小间距，保证分隔线可绘制
            sepPaint.strokeWidth = 1f * d
            updateColors(colors.panel)
            val minRowH = 32f * d
            visibleRows = (h / minRowH).toInt().coerceIn(1, maxVisibleRow)
            rowH = h.toFloat() / visibleRows
            recomputeLayout()
            resetScroll()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val rowWidth = width.toFloat()
            val h = height.toFloat()

            val firstRow = (scrollOffsetY / rowH).toInt().coerceAtLeast(0)
            val yOff = -(scrollOffsetY - firstRow * rowH) + stretch
            // 一帧内恒定：行基线偏移在循环外算一次（此前每候选重复求值）
            val textCenterDy = (textPaint.descent() + textPaint.ascent()) / 2f
            val densityF = resources.displayMetrics.density

            canvas.withSave {
                clipRect(0f, 0f, rowWidth, h)
                val startIdx = rowStartIndex.getOrElse(firstRow) { 0 }
                for (i in startIdx until positions.size) {
                    val pos = positions[i]
                    if (pos.row < firstRow) continue
                    val y = (pos.row - firstRow) * rowH + yOff
                    if (y > h) break

                    // 被拖动项仅隐藏内容，其右侧分割线仍需绘制
                    if (i != dragIndex) {
                        val c = allCandidates[i]
                        val tw = textWidths.getOrElse(i) { 0f }
                        val isTarget = i == dragTargetIndex && dragIndex >= 0

                        // 同一 sin 值两处用，只算一遍（此前每候选每帧算两次）
                        val shakeSin = if (dragIndex >= 0) {
                            kotlin.math.sin(shakePhase + i * 1.9f)
                        } else 0f
                        val shakeOff = shakeSin * 0.8f * densityF
                        val shakeAngle = shakeSin * shakeMaxAngle

                        if (pos.extraWide) {
                            val sx = rowScrollX[pos.row] ?: 0f
                            canvas.withSave {
                                clipRect(0f, y, rowWidth, y + rowH)
                                if (isTarget) {
                                    val d = resources.displayMetrics.density
                                    canvas.drawRoundRect(
                                        0f, y, rowWidth, y + rowH, 6f * d, 6f * d, pressPaint
                                    )
                                }
                                val textX = cellPad - sx + shakeOff
                                val textY = y + rowH / 2f - textCenterDy
                                if (shakeAngle != 0f) {
                                    val cx = textX + tw / 2f
                                    val cy = textY
                                    canvas.withRotation(shakeAngle, cx, cy) {
                                        drawText(c.text, textX, textY, textPaint)
                                    }
                                } else {
                                    drawText(c.text, textX, textY, textPaint)
                                }
                            }
                        } else {
                            val rectLeft = pos.xStart + shakeOff
                            val rectRight = pos.xStart + pos.width + shakeOff
                            if (isTarget) {
                                val d = resources.displayMetrics.density
                                canvas.drawRoundRect(
                                    rectLeft, y, rectRight, y + rowH, 6f * d, 6f * d, pressPaint
                                )
                            }
                            val txtLeft = pos.xStart + shakeOff + (pos.width - tw) / 2f
                            val textX = txtLeft.coerceAtLeast(rectLeft)
                            val textY = y + rowH / 2f - textCenterDy
                            if (shakeAngle != 0f) {
                                val cx = textX + tw / 2f
                                val cy = textY
                                canvas.withRotation(shakeAngle, cx, cy) {
                                    drawText(c.text, textX, textY, textPaint)
                                }
                            } else {
                                canvas.drawText(c.text, textX, textY, textPaint)
                            }
                        }
                    }

                    if (i + 1 < positions.size && !pos.extraWide) {
                        val nextPos = positions[i + 1]
                        if (nextPos.row == pos.row) {
                            val sepX = pos.xStart + pos.width
                            val cy = y + rowH / 2f
                            val lh = textPaint.textSize * 0.8f
                            canvas.drawLine(sepX, cy - lh / 2f, sepX, cy + lh / 2f, sepPaint)
                        }
                    }
                }
            }

            if (dragIndex >= 0 && dragIndex in allCandidates.indices) {
                val c = allCandidates[dragIndex]
                val dragW = textWidths.getOrElse(dragIndex) { 0f } + cellPad * 2
                val dragLeft = (dragFingerX - dragW / 2).coerceIn(0f, width - dragW)
                val dragTop = (dragFingerY - rowH / 2).coerceIn(0f, height - rowH)

                val d = resources.displayMetrics.density
                val oldColor = bgPaint.color
                bgPaint.color = sepPaint.color
                val oldAlpha = bgPaint.alpha
                bgPaint.alpha = 160
                canvas.drawRoundRect(
                    dragLeft, dragTop, dragLeft + dragW, dragTop + rowH, 6f * d, 6f * d, bgPaint
                )
                bgPaint.alpha = oldAlpha
                bgPaint.color = oldColor

                canvas.drawText(
                    c.text,
                    dragLeft + cellPad,
                    dragTop + rowH / 2f - textCenterDy,
                    textPaint
                )
            }

            // 补取判定不在绘制调用栈内直接回调 listener：post 到帧后执行，
            // 数据请求与绘制事务解耦，避免改绘制逻辑时踩回调重入
            if (!needMoreCheckPosted) {
                needMoreCheckPosted = true
                post {
                    needMoreCheckPosted = false
                    checkNeedMore()
                }
            }
        }

        /**
         * 分页补取触发：滚动进入距底一行半以内、且引擎侧还有未取候选时
         * 请求下一页。绘制末尾统一判定，覆盖拖动与 fling 两条滚动路径；
         * needMoreRequested 防同一批次内重复请求，列表更新时复位。
         */
        private fun checkNeedMore() {
            if (!hasMoreCandidates || needMoreRequested) return
            val limit = maxScroll
            // 内容填不满一屏（limit=0）时同样要补取：首屏候选恰好一屏高
            // 时用户根本滚不动，不能等"滚动到底"才触发。补取回来列表
            // 更新会复位请求标志并重绘，此处再判定——如此续取到内容
            // 可滚动或引擎到底（hasMoreCandidates 转 false）为止
            if (limit <= 0f || scrollOffsetY >= limit - rowH * 1.5f) {
                needMoreRequested = true
                onNeedMoreCandidates?.invoke()
            }
        }

        // 字形覆盖只随字体变；本视图生命周期内 textPaint 只改颜色/字号、
        // 从不换字体，结果可按文本缓存。面板每轮整表过滤都会逐字走到
        // 这里，常用字第二次起直接命中缓存，不再走字体回退查询
        private val glyphCache = HashMap<String, Boolean>()

        /** 用实际绘制的 Paint 查字形（系统默认字体链，与真实渲染一致） */
        fun hasGlyph(text: String): Boolean {
            if (glyphCache.size > 4096) glyphCache.clear()
            return glyphCache.getOrPut(text) { textPaint.hasGlyph(text) }
        }

        private fun hitTest(x: Float, y: Float): Int {
            if (positions.isEmpty() || rowH <= 0f) return -1
            // 由纵坐标直接定位行，只扫该行候选（原实现每个 MOVE 事件全量线性扫描）
            val adjustedY = y + scrollOffsetY
            val row = (adjustedY / rowH).toInt()
            if (row < 0 || row >= rowStartIndex.size) return -1
            var i = rowStartIndex[row]
            if (i >= positions.size) return -1
            while (i < positions.size && positions[i].row == row) {
                val pos = positions[i]
                if (pos.extraWide) return i
                if (x in pos.xStart..(pos.xStart + pos.width)) return i
                i++
            }
            return -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (activePointerId != -1) return true
                    activePointerId = event.getPointerId(0)
                    parent.requestDisallowInterceptTouchEvent(true)
                    if (!scroller.isFinished) scroller.abortAnimation()

                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    downY = event.y
                    lastY = event.y
                    downX = event.x
                    downIndex = hitTest(event.x, event.y)
                    pressedIndex = downIndex
                    horizontalDrag = -1
                    dragging = false
                    longPressTriggered = false
                    longPressMoved = false
                    longPressIndex = -1
                    invalidate()
                    postDelayed(
                        longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val pIdx = event.findPointerIndex(activePointerId)
                    if (pIdx < 0) return true
                    val ex = event.getX(pIdx)
                    val ey = event.getY(pIdx)
                    val dy = lastY - ey
                    val dx = ex - downX
                    val absDy = abs(ey - downY)
                    val absDx = abs(ex - downX)

                    if (dragIndex >= 0) {
                        dragFingerX = ex
                        dragFingerY = ey
                        val hit = hitTest(ex, ey)
                        dragTargetIndex = if (hit >= 0 && hit != dragIndex) hit else dragIndex
                        invalidate()
                        lastY = ey
                        return true
                    }

                    if (horizontalDrag >= 0) {
                        val pos = positions.getOrNull(horizontalDrag)
                        if (pos != null && pos.extraWide) {
                            val tw = textWidths.getOrElse(horizontalDrag) { 0f }
                            val maxSx = (tw - (width - cellPad * 2f)).coerceAtLeast(0f)
                            val old = rowScrollX[pos.row] ?: 0f
                            rowScrollX[pos.row] = (old + (downX - ex)).coerceIn(0f, maxSx)
                            downX = ex
                            invalidate()
                        }
                        lastY = ey
                        return true
                    }

                    if (longPressTriggered) {
                        val moved = absDy > touchSlop || absDx > touchSlop
                        if (moved) longPressMoved = true
                        if (moved && longPressIndex >= 0) {
                            dragIndex = longPressIndex
                            dragTargetIndex = longPressIndex
                            pressedIndex = -1
                            longPressTriggered = false
                            removeCallbacks(longPressRunnable)
                            startShake()
                            invalidate()
                        }
                        lastY = ey
                        return true
                    }

                    if (!dragging && !longPressTriggered) {
                        if (absDy > touchSlop && absDy >= absDx) {
                            dragging = true
                            // 已进入滚动：长按计时必须取消，否则滚动中途长按触发会把手势劫持成拖拽排序
                            removeCallbacks(longPressRunnable)
                            pressedIndex = -1
                            stretch = 0f
                            if (!scroller.isFinished) scroller.abortAnimation()
                        } else if (absDx > touchSlop && absDx > absDy) {
                            val pos = positions.getOrNull(pressedIndex)
                            if (pos != null && pos.extraWide) {
                                horizontalDrag = pressedIndex
                                removeCallbacks(longPressRunnable)
                                pressedIndex = -1
                                invalidate()
                            }
                        }
                    }

                    if (dragging) {
                        dragBy(dy)
                        invalidate()
                    } else if (horizontalDrag < 0 && !longPressTriggered) {
                        val n = hitTest(ex, ey)
                        if (n != pressedIndex) {
                            pressedIndex = n
                            invalidate()
                        }
                    }

                    lastY = ey
                }

                MotionEvent.ACTION_UP -> {
                    activePointerId = -1
                    handleUp(event)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        activePointerId = -1
                        handleUp(event)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    activePointerId = -1
                    recycleVelocityTracker()
                    removeCallbacks(longPressRunnable)
                    stopShake()
                    dragIndex = -1
                    dragTargetIndex = -1
                    pressedIndex = -1
                    horizontalDrag = -1
                    dragging = false
                    longPressTriggered = false
                    longPressMoved = false
                    springBackIfNeeded()
                    invalidate()
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        /** 主动抬起的统一收尾（UP / 追踪指针的 POINTER_UP 共用）；体内坐标无关，只用速度与状态 */
        private fun handleUp(event: MotionEvent) {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    recycleVelocityTracker()
                    removeCallbacks(longPressRunnable)

                    if (dragIndex >= 0) {
                        if (dragTargetIndex >= 0 && dragTargetIndex != dragIndex) {
                            val mutable = allCandidates.toMutableList()
                            val item = mutable.removeAt(dragIndex)
                            mutable.add(dragTargetIndex, item)
                            allCandidates = mutable
                            recomputeLayout()
                            this@CandidateGridView.onCandidatesReordered?.invoke(allCandidates)
                        }
                        this@CandidateGridView.onDragComplete?.invoke(allCandidates)
                        dragIndex = -1
                        dragTargetIndex = -1
                        stopShake()
                        invalidate()
                        parent.requestDisallowInterceptTouchEvent(false)
                        return
                    }

                    if (longPressTriggered) {
                        pressedIndex = -1
                        longPressTriggered = false
                        // 防御性复位：长按分支提前 return，不能把滚动状态留到下一个手势
                        if (dragging) {
                            dragging = false
                            springBackIfNeeded()
                        }
                        if (!longPressMoved && longPressIndex >= 0 && longPressIndex in allCandidates.indices) {
                            val firstRow = (scrollOffsetY / rowH).toInt().coerceAtLeast(0)
                            val yOff = -(scrollOffsetY - firstRow * rowH) + stretch
                            val pos = positions[longPressIndex]
                            val rowY = (pos.row - firstRow) * rowH + yOff
                            val rightEdge = if (pos.extraWide) width.toFloat() else pos.xStart + pos.width
                            val d = resources.displayMetrics.density
                            val wordVisibleRow = pos.row - firstRow
                            val above = wordVisibleRow >= visibleRows - 2
                            this@CandidateGridView.onWordForget?.invoke(
                                allCandidates[longPressIndex],
                                (rightEdge - 40f * d).coerceAtLeast(4f * d),
                                if (above) rowY - 96f * d else rowY + rowH + 4f * d
                            )
                        }
                        invalidate()
                        parent.requestDisallowInterceptTouchEvent(false)
                        return
                    }

                    if (horizontalDrag >= 0) {
                        horizontalDrag = -1
                    } else if (dragging) {
                        dragging = false
                        springBackIfNeeded()
                        if (abs(velocityY) >= minFlingVelocity) fling(-velocityY.toInt())
                    } else {
                        val i = pressedIndex
                        pressedIndex = -1
                        invalidate()
                        if (i in allCandidates.indices) {
                            onCandidateSelected?.invoke(allCandidates[i])
                        }
                    }

                    parent.requestDisallowInterceptTouchEvent(false)
        }

        private fun startShake() {
            shakePhase = 0f
            val view = this
            shakeRunnable = object : Runnable {
                override fun run() {
                    shakePhase = (shakePhase + 0.18f) % (Math.PI.toFloat() * 2f)
                    invalidate()
                    view.postOnAnimation(this)
                }
            }
            postOnAnimation(shakeRunnable)
        }

        private fun stopShake() {
            shakeRunnable?.let { removeCallbacks(it) }
            shakeRunnable = null
            shakePhase = 0f
        }

        private fun dragBy(deltaY: Float) {
            val proposed = scrollOffsetY + deltaY
            when {
                proposed < 0f -> {
                    scrollOffsetY = 0f
                    stretch = rubberBand(-proposed)
                }

                proposed > maxScroll -> {
                    scrollOffsetY = maxScroll
                    stretch = -rubberBand(proposed - maxScroll)
                }

                else -> {
                    scrollOffsetY = proposed
                    stretch = 0f
                }
            }
        }

        private fun rubberBand(d: Float): Float {
            val limit = overScrollLimit
            return limit * d / (limit + d)
        }

        fun clampScroll() {
            scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScroll)
        }

        private fun springBackIfNeeded(): Boolean {
            val maxScrollInt = maxScroll.toInt()
            val currentScroll = scrollOffsetY.toInt()

            if (stretch != 0f) {
                animateStretchBack()
                return true
            }
            if (currentScroll < 0 || currentScroll > maxScrollInt) {
                scroller.springBack(0, currentScroll, 0, 0, 0, maxScrollInt)
                postInvalidateOnAnimation()
                return true
            }
            clampScroll()
            return false
        }

        private fun animateStretchBack() {
            stretchAnimator?.cancel()
            val start = stretch
            stretchAnimator = ValueAnimator.ofFloat(start, 0f).apply {
                duration = 260
                addUpdateListener {
                    stretch = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun fling(velocityY: Int) {
            val maxScrollInt = maxScroll.toInt()
            scroller.fling(0, scrollOffsetY.toInt(), 0, velocityY, 0, 0, 0, maxScrollInt)
            postInvalidateOnAnimation()
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        override fun computeScroll() {
            if (dragging) return
            if (scroller.computeScrollOffset()) {
                scrollOffsetY = scroller.currY.toFloat()
                if (scroller.isFinished) {
                    clampScroll()
                }
                invalidate()
            }
        }

        fun resetScroll() {
            if (!scroller.isFinished) scroller.abortAnimation()
            scrollOffsetY = 0f
            stretch = 0f
        }

    }

    // ── init ──

    init {
        isClickable = true
        sidePanelPunctuationItems = listOf(".", "?", "!", "@", "/", "-").map { ch ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = ch,
                    textSize = 15f,
                    percentWidth = 0.5f,
                    margin = false,
                    variant = KeyDef.Appearance.Variant.Alternative
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(ch))),
            )
        }
        sidePanelKey.updateItems(sidePanelPunctuationItems)

        addView(sidePanelKey, lParams(0, matchParent))
        addView(gridCanvas, lParams(0, matchParent))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        val sw = (tw * 0.15f).toInt()
        val gw = tw - sw
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        gridCanvas.measure(mES(gw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        setMeasuredDimension(tw, th)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        val sw = ((r - l) * 0.15f).toInt()
        sidePanelKey.layout(0, 0, sw, h)
        gridCanvas.layout(sw, 0, r - l, h)
    }

    private fun mES(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)

    fun refreshTheme(context: Context) {
        val colors = KeyboardColors.resolve(context)
        refreshTheme(colors)

        gridCanvas.updateColors(colors.panel)
        sidePanelKey.refreshTheme(colors)
        invalidate()
    }

    /** 收起动画进行中：此间 visibility 仍为 VISIBLE，show() 需特殊处理 */
    private var collapseAnimating = false

    fun show(list: List<EngineMessage.Candidate>) {
        allCandidates = list
        needMoreRequested = false
        gridCanvas.recomputeLayout()
        gridCanvas.resetScroll()
        if (collapseAnimating) {
            // 收起动画未结束时 visibility 仍是 VISIBLE，ComponentView.show()
            // 的守卫会直接空转，而旧动画收尾还会清空刚写入的候选：
            // 先取消动画（slideUpCollapse 的 cancelled 守卫会跳过收尾清空）、
            // 复位缩放，直接置为展开态
            collapseAnimating = false
            animate().cancel()
            animate().setListener(null)
            scaleY = 1f
            bringToFront()
            visibility = View.VISIBLE
        } else {
            super.show()
        }
        // 隐藏期间积压的拼音侧栏数据在展开时才真正构建应用
        pendingPinYinData?.let { applyPossibleCandidatePinYin(it) }
    }

    fun updateCandidates(list: List<EngineMessage.Candidate>) {
        allCandidates = list
        // 列表已更新（新批次或追加页到达）：允许再次在滚到底时请求下一页
        needMoreRequested = false
        gridCanvas.recomputeLayout()
        gridCanvas.clampScroll()
        gridCanvas.invalidate()
    }

    override fun hide() {
        collapseAnimating = true
        slideUpCollapse {
            collapseAnimating = false
            allCandidates = emptyList()
            gridCanvas.resetScroll()
        }
    }

    private var pendingPinYinData: List<CandidatePinYin>? = null

    fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (!subscribePossibleCandidatePinYin) return
        pendingPinYinData = data
        // 网格隐藏时只记数据不构建：旧实现每键都为不可见的侧栏全量重建 KeyDef
        if (visibility != View.VISIBLE) return
        applyPossibleCandidatePinYin(data)
    }

    private fun applyPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (data.isEmpty()) {
            sidePanelKey.updateItems(sidePanelPunctuationItems)
        } else {
            sidePanelKey.updateItems(PinYinKeyDefCache.get(data))
        }
    }
}

/**
 * 拼音侧栏 KeyDef 构建缓存：同一批数据会被候选网格侧栏与键盘（T9）侧栏两个
 * 消费方各请求一次，两处构建结果完全一致。按数据引用缓存一份共用，避免每键
 * 重复全量构建。仅在主线程的 UI 回调里访问，无需同步。
 */
internal object PinYinKeyDefCache {
    private var lastData: List<CandidatePinYin>? = null
    private var lastDefs: List<KeyDef> = emptyList()

    fun get(data: List<CandidatePinYin>): List<KeyDef> {
        if (data === lastData) return lastDefs
        val defs = data.map { pinYin ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = pinYin.pinYin,
                    textSize = 15f,
                    percentWidth = 0.5f,
                    margin = false
                ),
                behaviors = setOf(
                    KeyDef.Behavior.Press(
                        KeyboardAction.SelectCandidatePinYin(
                            pinYin = pinYin
                        )
                    )
                ),
            )
        }
        lastData = data
        lastDefs = defs
        return defs
    }
}