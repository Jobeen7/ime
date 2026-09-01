package com.ninthsoft.ime.input.panel.component

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
import com.ninthsoft.ime.base.util.slideUpCollapse
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
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

        private val rowScrollX = mutableMapOf<Int, Float>()
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

        private val maxScroll
            get(): Float {
                val totalRows = positions.maxOfOrNull { it.row }?.plus(1) ?: visibleRows
                return (totalRows * rowH - height).coerceAtLeast(0f)
            }

        fun updateColors(pc: KeyboardColors.ColorScheme.PanelColors) {
            bgPaint.color = pc.background
            textPaint.color = pc.candidateText
            sepPaint.color = pc.candidateDivider
            pressPaint.color = pc.candidateBackground
        }

        fun recomputeLayout() {
            if (width <= 0 || allCandidates.isEmpty()) {
                positions = emptyList()
                return
            }

            val rowWidth = width.toFloat()
            // 每个候选词原始宽度 = 文本宽度 + 左右内边距
            val rawWidths = FloatArray(allCandidates.size) {
                textPaint.measureText(allCandidates[it].text) + cellPad * 2f
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
            rowScrollX.clear()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
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

            canvas.withSave {
                clipRect(0f, 0f, rowWidth, h)
                for (i in positions.indices) {
                    if (i == dragIndex) continue

                    val pos = positions[i]
                    if (pos.row < firstRow) continue
                    val y = (pos.row - firstRow) * rowH + yOff
                    if (y > h) break
                    val c = allCandidates[i]
                    val tw = textPaint.measureText(c.text)
                    val isTarget = i == dragTargetIndex && dragIndex >= 0

                    val shakeOff = if (dragIndex >= 0 && i != dragIndex) {
                        kotlin.math.sin(shakePhase + i * 1.9f) * 0.8f * resources.displayMetrics.density
                    } else 0f
                    val shakeAngle = if (dragIndex >= 0 && i != dragIndex) {
                        kotlin.math.sin(shakePhase + i * 1.9f) * shakeMaxAngle
                    } else 0f

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
                            val textY =
                                y + rowH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
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
                        val textY = y + rowH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
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
                val dragW = textPaint.measureText(c.text) + cellPad * 2
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
                    dragTop + rowH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f,
                    textPaint
                )
            }
        }

        private fun hitTest(x: Float, y: Float): Int {
            val adjustedY = y + scrollOffsetY
            for (i in positions.indices) {
                val pos = positions[i]
                val cy = pos.row * rowH
                if (adjustedY < cy || adjustedY >= cy + rowH) continue
                if (pos.extraWide) return i
                if (x in pos.xStart..(pos.xStart + pos.width)) return i
            }
            return -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                    val dy = lastY - event.y
                    val dx = event.x - downX
                    val absDy = abs(event.y - downY)
                    val absDx = abs(event.x - downX)

                    if (dragIndex >= 0) {
                        dragFingerX = event.x
                        dragFingerY = event.y
                        val hit = hitTest(event.x, event.y)
                        dragTargetIndex = if (hit >= 0 && hit != dragIndex) hit else dragIndex
                        invalidate()
                        lastY = event.y
                        return true
                    }

                    if (horizontalDrag >= 0) {
                        val pos = positions.getOrNull(horizontalDrag)
                        if (pos != null && pos.extraWide) {
                            val tw = textPaint.measureText(allCandidates[horizontalDrag].text)
                            val maxSx = (tw - (width - cellPad * 2f)).coerceAtLeast(0f)
                            val old = rowScrollX[pos.row] ?: 0f
                            rowScrollX[pos.row] = (old + (downX - event.x)).coerceIn(0f, maxSx)
                            downX = event.x
                            invalidate()
                        }
                        lastY = event.y
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
                        lastY = event.y
                        return true
                    }

                    if (!dragging && !longPressTriggered) {
                        if (absDy > touchSlop && absDy >= absDx) {
                            dragging = true
                            pressedIndex = -1
                            stretch = 0f
                            if (!scroller.isFinished) scroller.abortAnimation()
                        } else if (absDx > touchSlop && absDx > absDy) {
                            val pos = positions.getOrNull(pressedIndex)
                            if (pos != null && pos.extraWide) {
                                horizontalDrag = pressedIndex
                                pressedIndex = -1
                                invalidate()
                            }
                        }
                    }

                    if (dragging) {
                        dragBy(dy)
                        invalidate()
                    } else if (horizontalDrag < 0 && !longPressTriggered) {
                        val n = hitTest(event.x, event.y)
                        if (n != pressedIndex) {
                            pressedIndex = n
                            invalidate()
                        }
                    }

                    lastY = event.y
                }

                MotionEvent.ACTION_UP -> {
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
                        return true
                    }

                    if (longPressTriggered) {
                        pressedIndex = -1
                        longPressTriggered = false
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
                        return true
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

                MotionEvent.ACTION_CANCEL -> {
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

    fun show(list: List<EngineMessage.Candidate>) {
        allCandidates = list
        gridCanvas.recomputeLayout()
        gridCanvas.resetScroll()
        super.show()
    }

    fun updateCandidates(list: List<EngineMessage.Candidate>) {
        allCandidates = list
        gridCanvas.recomputeLayout()
        gridCanvas.clampScroll()
        gridCanvas.invalidate()
    }

    override fun hide() {
        slideUpCollapse {
            allCandidates = emptyList()
            gridCanvas.resetScroll()
        }
    }

    fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (!subscribePossibleCandidatePinYin) return
        if (data.isEmpty()) {
            sidePanelKey.updateItems(sidePanelPunctuationItems)
        } else {
            sidePanelKey.updateItems(data.map { pinYin ->
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
            })
        }
    }
}