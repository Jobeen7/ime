package com.jobeen.ime.input.keyboard.key

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.KeyboardManager
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
class GridKeyboardView(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
    private var columns: Int = 5,
    private var rows: Int = 5,
) : ViewGroup(context) {

    var onKeyAction: ((KeyboardAction) -> Unit)? = null
    var onKeyPressed: ((KeyView) -> Unit)? = null

    private var scrollOffsetY = 0f
    // 已通过 offsetTopAndBottom 应用到子 View 位置的滚动偏移：滚动时增量
    // 平移子 View 代替每帧 requestLayout 的整批 measure/layout 遍历
    private var appliedScrollOffset = 0
    private var laidFirstRow = -1
    private var laidLastRow = -1
    private var rowH = 0
    private var lastMeasuredColW = -1
    private var lastMeasuredRowH = -1
    private var totalRows = 0

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var lastY = 0f
    private var downY = 0f
    private var dragging = false
    private var stretch = 0f
    private var stretchAnimator: ValueAnimator? = null
    private val overScrollLimit get() = max(height * 0.45f, 1f)

    private val maxScroll
        get(): Int {
            val contentH = totalRows * rowH
            return (contentH - height).coerceAtLeast(0)
        }

    // KeyView 复用缓存（最近一次 items）：表情/符号面板每次打开、切回分类
    // 都会用内容完全相同的 items 再调一次 setItems，全量重建数百个 KeyView
    // 是纯浪费。内容签名未变时复用已建的 View，只做 detach 后再 attach。
    // 配色在本 View 生命周期内固定（构造入参，换主题走键盘重建、新建本
    // View），不存在原地换色路径；仍把配色记入缓存快照比对，将来若有
    // 原地刷新配色的入口，签名不一致会自然落到重建分支清掉缓存。
    private var cachedSignature: List<String>? = null
    private var cachedColors: KeyboardColors.ColorScheme? = null
    private var cachedViews: List<KeyView> = emptyList()

    /**
     * items 的内容签名：外观类型与文本/图源、字号、变体、宽度与 Press
     * 动作共同决定一个 KeyView 的长相与点击行为，任一不同即视为内容变化。
     * KeyDef/Appearance 不是 data class，不能直接靠 equals 比对，故逐项拼串。
     */
    private fun itemsSignature(items: List<KeyDef>): List<String> = items.map { def ->
        val appearance = def.appearance
        val appearancePart = when (appearance) {
            is KeyDef.Appearance.AltText ->
                "AltText:${appearance.displayText}/${appearance.altText}/${appearance.altTextSize}/${appearance.textSize}"

            is KeyDef.Appearance.ImageText ->
                "ImageText:${appearance.displayText}/${appearance.src}/${appearance.textSize}"

            is KeyDef.Appearance.Image -> "Image:${appearance.src}"
            is KeyDef.Appearance.Text -> "Text:${appearance.displayText}/${appearance.textSize}"
            else -> "Other:${appearance.javaClass.simpleName}"
        }
        val pressAction = def.behaviors
            .filterIsInstance<KeyDef.Behavior.Press>()
            .firstOrNull()?.action
        "$appearancePart|${appearance.variant}|${appearance.percentWidth}|$pressAction"
    }

    fun setItems(items: List<KeyDef>) {
        val signature = itemsSignature(items)
        val reusable = signature == cachedSignature &&
            cachedViews.size == items.size &&
            cachedColors == colors
        if (!reusable) {
            cachedViews = items.map { createKeyView(it) }
            cachedSignature = signature
            cachedColors = colors
        }
        // 复用的 View 可能还挂在父容器上（就是本 View 或重建前的旧父级），
        // 先统一 detach 再 attach，避免「已有 parent」异常；点击监听在建时
        // 捕获的是本 View 的 onKeyAction 属性（点击时动态读取），复用后
        // 仍走当前回调，无需重绑
        removeAllViews()
        for (keyView in cachedViews) {
            (keyView.parent as? ViewGroup)?.removeView(keyView)
            addView(keyView)
        }
        scrollOffsetY = 0f
        stretch = 0f
        if (!scroller.isFinished) scroller.abortAnimation()
        requestLayout()
    }

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, colors, def.appearance)
            else -> TextKeyView(
                context, colors, KeyDef.Appearance.Text(
                    displayText = "?", textSize = 16f, percentWidth = 1f / columns,
                )
            )
        }.apply {
            borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
            hMargin = 0
            vMargin = 0
            setOnClickListener {
                val action =
                    def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().firstOrNull()?.action
                if (action != null) {
                    onKeyAction?.invoke(action)
                }
            }
            onPressedChanged = { key ->
                if (key.isPressed) onKeyPressed?.invoke(key)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val colW = w / columns
        rowH = h / rows
        totalRows = (childCount + columns - 1) / columns
        // 只测可见行（上下各留 1 行缓冲）：分类最多近 400 个键，
        // 滚动时每帧全量 measure/layout 是主要卡顿源；不可见键滚入视口时再测
        val (first, last) = visibleRowRange()
        // 尺寸未变且子 View 已按同规格测过时跳过：滚动每帧 requestLayout 会
        // 把可见键整批重复 measure（fling 期约 30-70 个/帧），而它们的
        // EXACTLY 规格在滚动中恒定，重复测量纯浪费
        val sizeChanged = colW != lastMeasuredColW || rowH != lastMeasuredRowH
        for (i in 0 until childCount) {
            val row = if (columns > 0) i / columns else 0
            if (row !in first..last) continue
            val child = getChildAt(i)
            if (!sizeChanged && child.measuredWidth == colW && child.measuredHeight == rowH) {
                continue
            }
            child.measure(
                MeasureSpec.makeMeasureSpec(colW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowH, MeasureSpec.EXACTLY)
            )
        }
        lastMeasuredColW = colW
        lastMeasuredRowH = rowH
        setMeasuredDimension(w, h)
    }

    /** 当前视口覆盖的行范围（含 1 行缓冲）；rowH 未知时返回全量范围。 */
    private fun visibleRowRange(): Pair<Int, Int> {
        if (rowH <= 0 || totalRows == 0) return 0 to (totalRows - 1).coerceAtLeast(0)
        val first = (scrollOffsetY.toInt() / rowH - 1).coerceAtLeast(0)
        val last = (first + rows + 2).coerceAtMost(totalRows - 1)
        return first to last
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val colW = (r - l) / columns
        val (first, last) = visibleRowRange()
        for (i in 0 until childCount) {
            val row = i / columns
            if (row !in first..last) continue
            val col = i % columns
            val child = getChildAt(i)
            val childTop = row * rowH - scrollOffsetY.toInt()
            child.layout(
                col * colW, childTop, (col + 1) * colW, childTop + rowH
            )
        }
        // onLayout 已按当前偏移定位，与增量平移的基准对齐
        appliedScrollOffset = scrollOffsetY.toInt()
        laidFirstRow = first
        laidLastRow = last
    }

    /**
     * 把滚动偏移以增量平移应用到全部子 View（纯位置字段修改 + 一次
     * invalidate），滚动/fling 期间不再每帧请求整批 measure/layout。
     * 子 View 的点击坐标由其 layout 位置决定，平移后命中与绘制一致。
     */
    private fun applyScrollOffset() {
        // 新滚入的行尚未按当前范围 layout 过：跨行时才回退一次完整布局
        // （每滚过一行一次，而非每帧），行内滚动走纯增量平移
        val (f, l) = visibleRowRange()
        if (f != laidFirstRow || l != laidLastRow) {
            requestLayout()
            return
        }
        val newOffset = scrollOffsetY.toInt()
        val delta = newOffset - appliedScrollOffset
        if (delta == 0) return
        for (i in 0 until childCount) {
            getChildAt(i).offsetTopAndBottom(-delta)
        }
        appliedScrollOffset = newOffset
        invalidate()
    }

    override fun computeScroll() {
        if (dragging) return
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat()
            clampScroll()
            applyScrollOffset()
        }
    }

    private fun clampScroll() {
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScroll.toFloat())
    }

    private fun hitTest(x: Float, y: Float): Int {
        val adjustedY = (y + scrollOffsetY).toInt()
        val row = adjustedY / rowH
        val col = (x / ((right - left) / columns)).toInt().coerceIn(0, columns - 1)
        val i = row * columns + col
        return if (i in 0 until childCount) i else -1
    }

    private fun dragBy(deltaY: Float) {
        val proposed = scrollOffsetY + deltaY
        when {
            proposed < 0f -> {
                scrollOffsetY = 0f
                stretch = rubberBand(-proposed)
            }

            proposed > maxScroll -> {
                scrollOffsetY = maxScroll.toFloat()
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

    private fun springBackIfNeeded(): Boolean {
        if (stretch != 0f) {
            animateStretchBack()
            return true
        }
        if (scrollOffsetY < 0f || scrollOffsetY > maxScroll) {
            scroller.springBack(
                0, scrollOffsetY.toInt(), 0, 0, 0, maxScroll
            )
            invalidate()
            return true
        }
        return false
    }

    private fun animateStretchBack() {
        stretchAnimator?.cancel()
        stretchAnimator = null
        // stretch 从未进入 onLayout 的位置计算（位置只由 scrollOffsetY
        // 决定），此前的逐帧动画只是驱动 260ms 的空转布局。直接归零，
        // 视觉与此前一致（本来就没有拉伸位移），省掉整段布局空转
        stretch = 0f
    }

    private fun fling(velocityY: Int) {
        @Suppress("DEPRECATION") scroller.fling(
            0, scrollOffsetY.toInt(), 0, velocityY, 0, 0, 0, maxScroll
        )
        invalidate()
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.abortAnimation()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                downY = event.y
                lastY = event.y
                dragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = lastY - event.y
                val absDy = abs(event.y - downY)
                if (!dragging && absDy > touchSlop) {
                    dragging = true
                    stretch = 0f
                    if (!scroller.isFinished) scroller.abortAnimation()
                    return true
                }
                lastY = event.y
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                dragging = false
                return false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (dragging) {
                    val dy = lastY - event.y
                    dragBy(dy)
                    applyScrollOffset()
                }
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                val velocityY = velocityTracker?.yVelocity ?: 0f
                recycleVelocityTracker()

                if (dragging) {
                    dragging = false
                    springBackIfNeeded()
                    if (abs(velocityY) >= minFlingVelocity) fling(-velocityY.toInt())
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                dragging = false
                springBackIfNeeded()
                return true
            }
        }
        return true
    }
}
