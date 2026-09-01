package com.ninthsoft.ime.input.panel

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.panel.toolbar.ToolbarRenderer
import com.ninthsoft.ime.input.panel.toolbar.ToolbarRendererResources
import kotlin.math.abs

@SuppressLint("UseCompatLoadingForDrawables")
class KawaiiPanelView(context: Context) : View(context) {

    var currentRenderer: IRenderer
    var scrollX = 0f
    var onTap: ((KawaiiPanel.TouchResult?) -> Unit)? = null
    var onExpandChanged: ((Boolean, List<EngineMessage.Candidate>) -> Unit)? = null
    var isExpanded: Boolean = false
        private set

    var recording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            currentRenderer.recording = value
            invalidate()
        }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        onExpandChanged?.invoke(false, emptyList())
        invalidate()
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        if (expanded) {
            val candidates = (currentRenderer as? ComposingRenderer)?.candidates ?: return
            onExpandChanged?.invoke(true, candidates)
        } else {
            onExpandChanged?.invoke(false, emptyList())
        }
        invalidate()
    }

    private val paints = Paints(context)
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isScrolling = false
    private val screenDensity = resources.displayMetrics.density

    init {
        paints.updateColors(context)
        paints.applyDensity(screenDensity)
        val hPad = KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat()
        currentRenderer = ToolbarRenderer(
            ToolbarRendererResources(
                context.getDrawable(R.drawable.ic_keyboard_menu),
                context.getDrawable(R.drawable.ic_keyboard_arrow_back),
                context.getDrawable(R.drawable.ic_keyboard_clipboard),
                context.getDrawable(R.drawable.ic_keyboard_undo),
                context.getDrawable(R.drawable.ic_keyboard_redo),
                context.getDrawable(R.drawable.ic_keyboard_palette),
                context.getDrawable(R.drawable.ic_keyboard_cursor_move),
                context.getDrawable(R.drawable.ic_keyboard_keyboard_close),
                context.getDrawable(R.drawable.ic_keyboard_trash),
            ),
            hPad,
        )
    }

    fun refreshTheme() {
        paints.updateColors(context)
        invalidate()
    }

    fun refreshDensity() {
        paints.applyDensity(resources.displayMetrics.density)
        invalidate()
    }

    fun updateHorizontalPadding(hPadDp: Float) {
        when (val r = currentRenderer) {
            is ToolbarRenderer -> r.horizontalPaddingDp = hPadDp
            is ComposingRenderer -> r.horizontalPaddingDp = hPadDp
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val gradPaint = Paint(paints.bgPaint).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(paints.bgPaint.color, paints.bgPaint.color, paints.keyboardBackground),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradPaint)
        currentRenderer.draw(canvas, width, height, paints, scrollX, isExpanded, screenDensity)
    }

    private var pressAnimator: ValueAnimator? = null
    private var expandLongPressed = false
    private var dragTotalX = 0f
    private var dragTotalY = 0f
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        val result = currentRenderer.hitTest(
            lastTouchX, lastTouchY, width, height, scrollX, isExpanded, screenDensity,
        )
        if (result is KawaiiPanel.TouchResult.ExpandCandidates || result is KawaiiPanel.TouchResult.CollapseCandidates) {
            expandLongPressed = true
            onTap?.invoke(KawaiiPanel.TouchResult.LongPressExpand)
        } else if (result is KawaiiPanel.TouchResult.ToolbarAction && result.action is PanelAction.AddPhrase) {
            expandLongPressed = true
            onTap?.invoke(KawaiiPanel.TouchResult.LongPressClearPhrases)
        }
    }

    private fun isRecordingAllowed(result: KawaiiPanel.TouchResult?): Boolean {
        return when (result) {
            is KawaiiPanel.TouchResult.ToolbarAction -> result.action is PanelAction.CloseKeyboard

            is KawaiiPanel.TouchResult.CollapseCandidates -> true
            else -> false
        }
    }

    private fun startPressAnimation(renderer: ToolbarRenderer) {
        pressAnimator?.cancel()
        renderer.pressAlpha = 120
        renderer.pressRadius = 0f
        pressAnimator = ValueAnimator.ofFloat(0f, renderer.pressRadiusMax).apply {
            duration = 300
            addUpdateListener {
                renderer.pressRadius = animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun playToolbarPressAt(x: Float, y: Float) {
        val renderer = currentRenderer as? ToolbarRenderer ?: return
        renderer.hitTest(x, y, width, height, scrollX, isExpanded, screenDensity)
        startPressAnimation(renderer)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                dragTotalX = 0f
                dragTotalY = 0f
                isScrolling = false
                expandLongPressed = false
                parent.requestDisallowInterceptTouchEvent(true)
                if (!recording) longPressHandler.postDelayed(longPressRunnable, 500)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                dragTotalX += dx
                dragTotalY += dy
                val moveDist = abs(dx) + abs(dy)
                if (moveDist > 8 * screenDensity) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (currentRenderer !is ComposingRenderer) return true
                if (isScrolling || (abs(dragTotalX) > 8 * screenDensity && abs(dragTotalX) >= abs(
                        dragTotalY
                    ))
                ) {
                    isScrolling = true
                    val renderer = currentRenderer as ComposingRenderer
                    scrollX = (scrollX + dx).coerceIn(-renderer.maxScrollX, 0f)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                parent.requestDisallowInterceptTouchEvent(false)
                if (expandLongPressed) return true
                if (!isScrolling) {
                    val result = currentRenderer.hitTest(
                        event.x, event.y, width, height, scrollX, isExpanded, screenDensity,
                    )
                    if (recording && !isRecordingAllowed(result)) return true
                    if (currentRenderer is ToolbarRenderer && result != null) {
                        val action = (result as? KawaiiPanel.TouchResult.ToolbarAction)?.action
                        val isClipAction =
                            action is PanelAction.ClipTab || action is PanelAction.AddPhrase || action is PanelAction.ClearClipboard || action is PanelAction.ClearPhrases
                        val animateAfterRender =
                            action is PanelAction.SwitchKeyboard || action is PanelAction.CursorMove
                        if (!isClipAction && !animateAfterRender) {
                            startPressAnimation(currentRenderer as ToolbarRenderer)
                        }
                        if (result is KawaiiPanel.TouchResult.ToolbarAction && result.action is PanelAction.Palette) {
                            postDelayed({ onTap?.invoke(result) }, 300L)
                            return true
                        }
                    }
                    val delayedAction =
                        result is KawaiiPanel.TouchResult.ToolbarAction && result.action is PanelAction.CloseKeyboard
                    if (delayedAction) {
                        postDelayed({ onTap?.invoke(result) }, 100L)
                    } else {
                        onTap?.invoke(result)
                    }
                    if (result is KawaiiPanel.TouchResult.ToolbarAction && (result.action is PanelAction.SwitchKeyboard || result.action is PanelAction.CursorMove)) {
                        post { playToolbarPressAt(result.tapX, result.tapY) }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
