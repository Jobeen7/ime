package com.ninthsoft.ime.input.keyboard.key.widget

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyView
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
abstract class SidePanelView(
    ctx: Context,
    theme: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance,
    private val keepSelection: Boolean = false,
) : KeyView(ctx, theme, def) {

    protected abstract val render: SidePanelRender

    private var rawItems: List<KeyDef> = emptyList()

    private val contentView = SidePanelCanvasView(ctx)

    var selectedIndex: Int = -1
        private set

    fun selectIndex(index: Int) {
        selectedIndex = index
        contentView.applySelection(index)
    }

    init {
        appearanceView.add(contentView, lParams(matchParent, matchParent))
    }

    fun updateItems(items: List<KeyDef>) {
        rawItems = items
        render.updateItems(items.mapNotNull(render::toItem))
        contentView.resetPosition(true)
    }

    fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        colors = newColors
        radius = dp(newColors.cornerRadius)
        hMargin = dp(newColors.keyHMargin).toInt()
        vMargin = dp(newColors.keyVMargin).toInt()
        setupBackgroundWithPress()
        render.updateTheme(newColors)
        if (rawItems.isNotEmpty()) {
            render.updateItems(rawItems.mapNotNull(render::toItem))
        }
        contentView.invalidate()
    }

    fun updateVisibleItemCount(count: Int) {
        render.updateVisibleItemCount(count)
        contentView.clampScroll()
        contentView.invalidate()
    }

    fun resetPosition() {
        contentView.resetPosition(false)
    }

    var onRippleRequest: ((Float, Float) -> Unit)? = null

    fun setOnItemActionListener(listener: (KeyboardAction) -> Unit) {
        contentView.onItemAction = {
            InputFeedbacks.hapticFeedback(contentView)
            InputFeedbacks.soundEffect(context, InputFeedbacks.SoundEffect.Standard)
            listener.invoke(it)
        }
    }

    // =========================================================
    // Canvas View
    // =========================================================
    private inner class SidePanelCanvasView(ctx: Context) : View(ctx) {

        private val panel = RectF()
        private val scroller = OverScroller(ctx)
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val minFlingVelocity = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity
        private val maxFlingVelocity = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity

        private var velocityTracker: VelocityTracker? = null

        private var scrollOffset = 0f
        private var lastY = 0f
        private var downY = 0f

        private var pressedIndex = -1
        private var visualPressedIndex = -1
        private var pressedAlpha = 0
        private var pressAnimator: Animator? = null
        private var dragging = false

        private var stretch = 0f
        private var stretchAnimator: ValueAnimator? = null

        var onItemAction: ((KeyboardAction) -> Unit)? = null

        fun applySelection(index: Int) {
            pressAnimator?.removeAllListeners()
            pressAnimator?.cancel()
            pressAnimator = null
            visualPressedIndex = index
            pressedAlpha = 255
            invalidate()
        }

        private val rippleLoc = IntArray(2)

        // =====================================================
        // 🌊 Ripple（同色简化版）
        // =====================================================
        private var rippleIndex = -1
        private var rippleX = 0f
        private var rippleY = 0f
        private var rippleRadius = 0f
        private var rippleActive = false

        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val overScrollLimit: Float
            get() = max(panel.height() * 0.45f, 1f)

        init {
            isClickable = true
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 填充整个侧边面板容器（panel 之外的外边距区域），避免透明露出底层
            canvas.drawColor(render.style.backgroundColor)

            panel.set(
                hMargin.toFloat(),
                vMargin.toFloat(),
                width - hMargin.toFloat(),
                height - vMargin.toFloat(),
            )

            render.draw(canvas, panel, scrollOffset, visualPressedIndex, stretch, pressedAlpha, selectedIndex)

            drawRipple(canvas)
            updateRipple()
        }

        // =====================================================
        // 🌊 同色 ripple（核心）
        // =====================================================
        private fun drawRipple(canvas: Canvas) {
            if (!rippleActive || rippleIndex !in render.items.indices) return

            val itemHeight = render.itemHeight(panel.height())

            val top = panel.top + itemHeight * rippleIndex - scrollOffset + stretch
            val bottom = top + itemHeight
            canvas.save()
            canvas.clipRect(panel.left, top, panel.right, bottom)

            ripplePaint.color = 0
            ripplePaint.alpha = 25   // 👈 永远同色，不渐变

            canvas.drawCircle(rippleX, rippleY, rippleRadius, ripplePaint)
            canvas.restore()
        }

        // =====================================================
        // 🌊 更自然扩散（无 alpha 变化）
        // =====================================================
        private fun updateRipple() {
            if (!rippleActive) return
            val maxRadius = width * 1.25f
            rippleRadius += width * 0.014f * (1f + rippleRadius * 0.02f)
            if (rippleRadius > maxRadius) {
                rippleActive = false
                rippleIndex = -1
            }
            invalidate()
        }

        override fun computeScroll() {
            if (dragging) return

            if (scroller.computeScrollOffset()) {
                scrollOffset = scroller.currY.toFloat()
                if (scroller.isFinished) clampScroll()
                invalidate()
            }
        }

        private var longPressTriggered = false

        private val longPressRunnable = Runnable {
            longPressTriggered = true
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
                    dragging = false
                    pressAnimator?.cancel()
                    visualPressedIndex = -1
                    pressedAlpha = 0
                    invalidate()
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)

                    val dy = lastY - event.y

                    if (!dragging && abs(event.y - downY) > touchSlop) {
                        dragging = true
                        visualPressedIndex = -1
                        pressedAlpha = 0
                        pressAnimator?.cancel()
                        stretch = 0f
                        if (!scroller.isFinished) scroller.abortAnimation()
                    }

                    if (dragging) {
                        dragBy(dy)
                        invalidate()
                    }

                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())

                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    var index = -1
                    var click = false

                    recycleVelocityTracker()
                    removeCallbacks(longPressRunnable)
                    if (!longPressTriggered) {
                        pressedIndex = render.itemIndexAt(panel, event.y, scrollOffset)
                        val itemHeight = render.itemHeight(panel.height())
                        if (pressedIndex >= 0 && abs(event.y - downY) < itemHeight) {
                            click = true
                            index = pressedIndex
                            if (keepSelection) {
                                selectedIndex = index
                                visualPressedIndex = selectedIndex
                                pressedAlpha = 255
                            } else {
                                visualPressedIndex = index
                                pressedAlpha = 255
                                pressAnimator?.cancel()
                                pressAnimator = ValueAnimator.ofInt(255, 0).apply {
                                    duration = 180
                                    startDelay = 70
                                    addUpdateListener {
                                        pressedAlpha = animatedValue as Int
                                        invalidate()
                                    }
                                    addListener(object : Animator.AnimatorListener {
                                        override fun onAnimationEnd(a: Animator) { visualPressedIndex = -1; pressedAlpha = 0 }
                                        override fun onAnimationCancel(a: Animator) { visualPressedIndex = -1; pressedAlpha = 0 }
                                        override fun onAnimationStart(a: Animator) {}
                                        override fun onAnimationRepeat(a: Animator) {}
                                    })
                                    start()
                                }
                            }
                        }
                        invalidate()
                    }

                    pressedIndex = -1
                    dragging = false
                    longPressTriggered = false

                    parent.requestDisallowInterceptTouchEvent(false)
                    if (click) {
                        render.items[index].action?.let { onItemAction?.invoke(it) }
                    } else if (springBackIfNeeded()) {
                        invalidate()
                    } else if (abs(velocityY) >= minFlingVelocity) {
                        fling(-velocityY.toInt())
                    }

                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    recycleVelocityTracker()
                    removeCallbacks(longPressRunnable)

                    pressAnimator?.cancel()
                    pressedIndex = -1
                    visualPressedIndex = -1
                    pressedAlpha = 0
                    dragging = false
                    longPressTriggered = false

                    springBackIfNeeded()
                    return true
                }
            }
            return true
        }

        fun clampScroll() {
            val maxScroll = render.maxScroll(panel.height())
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        }

        fun resetPosition(immediate: Boolean) {
            if (immediate) {
                if (!scroller.isFinished) scroller.abortAnimation()
                scrollOffset = 0f
                stretch = 0f
                invalidate()
            } else {
                scroller.startScroll(0, scrollOffset.toInt(), 0, -scrollOffset.toInt())
                animateStretchBack()
                postInvalidateOnAnimation()
            }
        }

        private fun fling(velocityY: Int) {
            val maxScroll = render.maxScroll(panel.height()).toInt()
            scroller.fling(0, scrollOffset.toInt(), 0, velocityY, 0, 0, 0, maxScroll)
            postInvalidateOnAnimation()
        }

        private fun dragBy(deltaY: Float) {
            val maxScroll = render.maxScroll(panel.height())
            val proposed = scrollOffset + deltaY

            when {
                proposed < 0f -> {
                    scrollOffset = 0f
                    stretch = rubberBand(-proposed)
                }

                proposed > maxScroll -> {
                    scrollOffset = maxScroll
                    stretch = -rubberBand(proposed - maxScroll)
                }

                else -> {
                    scrollOffset = proposed
                    stretch = 0f
                }
            }
        }

        private fun rubberBand(d: Float): Float {
            val limit = overScrollLimit
            return limit * d / (limit + d)
        }

        private fun springBackIfNeeded(): Boolean {
            val maxScroll = render.maxScroll(panel.height()).toInt()
            val currentScroll = scrollOffset.toInt()

            if (stretch != 0f) {
                animateStretchBack()
                return true
            }

            if (currentScroll < 0 || currentScroll > maxScroll) {
                scroller.springBack(0, currentScroll, 0, 0, 0, maxScroll)
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

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }
    }
}