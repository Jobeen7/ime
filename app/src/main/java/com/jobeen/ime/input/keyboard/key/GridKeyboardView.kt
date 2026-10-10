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

    // KeyView 复用缓存（按内容签名多条目）：表情/符号面板每次打开、切换
    // 分类都会用某分类的 items 调一次 setItems，全量重建数百个 KeyView 是
    // 纯浪费。旧实现只缓存最近一个分类，来回切分类时每切一次都重建；现
    // 改为按签名缓存多个分类的已建 View，切回已缓存分类直接复用，只做
    // detach 后再 attach。
    // 失效策略选「条目内带配色快照」：配色在本 View 生命周期内固定（构造
    // 入参，换主题走键盘重建、新建本 View），不存在原地换色路径；仍给每
    // 条目记一份配色快照，命中时比对、不符则丢弃该条目按当前配色重建，
    // 将来若有原地刷新配色的入口也能自然失效，无需整表清理的代码路径。
    // 容量上限：表情+符号分类总数有限，超过上限时按最久未用淘汰
    // （accessOrder 的 LinkedHashMap，get/put 都会刷新条目顺序）。
    private val viewCache =
        LinkedHashMap<List<String>, Pair<KeyboardColors.ColorScheme, List<KeyView>>>(
            16, 0.75f, true
        )

    /** 缓存构建时的显示度量戳：density/scaledDensity 变化（改显示大小/字体大小）后旧 View 的像素字号已固化，整表失效重建 */
    private var cacheDensity = 0f
    private var cacheScaledDensity = 0f

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
        // 显示大小/字体大小变更后，缓存 View 在构造时固化的像素字号与
        // 当前度量不符（签名不含度量）——度量戳变化即整表失效
        val dm = resources.displayMetrics
        if (dm.density != cacheDensity || dm.scaledDensity != cacheScaledDensity) {
            viewCache.clear()
            cacheDensity = dm.density
            cacheScaledDensity = dm.scaledDensity
        }
        val signature = itemsSignature(items)
        // 签名逐项由 items 生成，长度恒与 items 相同；再核一遍条目内 View
        // 数与 items 数一致才复用，防签名构造方式将来变更时误用旧条目
        val cached = viewCache[signature]
        val views: List<KeyView> = if (cached != null &&
            cached.first == colors &&
            cached.second.size == items.size
        ) {
            // 复用前必须清掉旧布局坐标：本 View 只给可见行调 layout，
            // 不在可见行的复用 View 会保留上次（可能在滚动态势下）的
            // 位置，叠到首屏键上抢触摸。清零后未被 layout 的键是
            // 0 尺寸、不可见也不可命中，等它滚进可见行时再定位。
            cached.second.forEach { it.layout(0, 0, 0, 0) }
            cached.second
        } else {
            items.map { createKeyView(it) }.also { built ->
                viewCache[signature] = colors to built
                // 按缓存内 View 总数淘汰最久未用条目（单分类可达数百
                // 个键，按「套数」设上限会让上万个 View 常驻）；被淘汰
                // 条目的 View 若正挂在本 View 上，随后统一 detach 流
                // 程会把它们摘下交 GC，不影响本次挂载。至少保留刚
                // 放入的这一套。
                while (viewCache.size > 1 &&
                    viewCache.values.sumOf { it.second.size } > MAX_CACHED_VIEWS_TOTAL
                ) {
                    viewCache.remove(viewCache.keys.first())
                }
            }
        }
        // 复用的 View 可能还挂在父容器上（就是本 View 或重建前的旧父级），
        // 先统一 detach 再 attach，避免「已有 parent」异常；点击监听在建时
        // 捕获的是本 View 的 onKeyAction 属性（点击时动态读取），复用后
        // 仍走当前回调，无需重绑
        removeAllViews()
        for (keyView in views) {
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 键盘隐藏后裁剪复用缓存（只留最近用的几套）：全量分类的
        // KeyView 在隐藏期间常驻纯属浪费，下次打开时按需重建/复用
        while (viewCache.size > DETACHED_KEEP_SETS) {
            viewCache.remove(viewCache.keys.first())
        }
    }

    companion object {
        /** 缓存内 KeyView 总数上限：超出按最久未用整套淘汰 */
        private const val MAX_CACHED_VIEWS_TOTAL = 1200

        /** detach 后缓存保留的套数上限：键盘隐藏期间不养全量缓存 */
        private const val DETACHED_KEEP_SETS = 3
    }
}
