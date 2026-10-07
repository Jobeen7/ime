package com.jobeen.ime.input.panel

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
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.panel.toolbar.ToolbarRenderer
import com.jobeen.ime.input.panel.toolbar.ToolbarRendererResources
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

    // 背景渐变缓存：只在尺寸/颜色变化时重建，onDraw 内零分配
    private val bgGradPaint = Paint()
    private var bgGradW = -1
    private var bgGradH = -1
    private var bgGradColor = 0
    private var bgGradKeyboardBg = 0
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
                context.getDrawable(R.drawable.ic_keyboard_keyboard_close),
                context.getDrawable(R.drawable.ic_keyboard_trash),
                context.getDrawable(R.drawable.ic_toolbar_emoji),
                context.getDrawable(R.drawable.ic_toolbar_select_all),
                context.getDrawable(R.drawable.ic_toolbar_copy),
                context.getDrawable(R.drawable.ic_toolbar_paste),
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val bgColor = paints.bgPaint.color
        val kbBg = paints.keyboardBackground
        if (width != bgGradW || height != bgGradH || bgColor != bgGradColor || kbBg != bgGradKeyboardBg) {
            bgGradPaint.set(paints.bgPaint)
            bgGradPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(bgColor, bgColor, kbBg),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
            bgGradW = width
            bgGradH = height
            bgGradColor = bgColor
            bgGradKeyboardBg = kbBg
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgGradPaint)
        currentRenderer.draw(canvas, width, height, paints, scrollX, isExpanded, screenDensity)
    }

    private var pressAnimator: ValueAnimator? = null
    private var expandLongPressed = false
    private var dragTotalX = 0f
    private var dragTotalY = 0f

    // 当前手势的指针 id：只认第一根落指的手指，其他手指的事件不干扰本手势
    private var activePointerId = -1

    // 延迟触发的工具栏动作（Palette 300ms / CloseKeyboard 100ms）：
    // 用具名 Runnable 以便取消——新按下、输入结束、View 分离时必须清掉，
    // 否则过期的点击会在错误的状态下迟到触发
    private var delayedPalette: Runnable? = null
    private var delayedClose: Runnable? = null

    fun cancelDelayedTaps() {
        delayedPalette?.let { removeCallbacks(it) }
        delayedClose?.let { removeCallbacks(it) }
        delayedPalette = null
        delayedClose = null
    }

    override fun onDetachedFromWindow() {
        cancelDelayedTaps()
        longPressHandler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
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
                // 已有手指在进行手势时，第二根手指不重启手势
                if (activePointerId != -1) return true
                activePointerId = event.getPointerId(0)
                cancelDelayedTaps()
                lastTouchX = event.getX(0)
                lastTouchY = event.getY(0)
                dragTotalX = 0f
                dragTotalY = 0f
                isScrolling = false
                expandLongPressed = false
                parent.requestDisallowInterceptTouchEvent(true)
                if (!recording) longPressHandler.postDelayed(longPressRunnable, 500)
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val ex = event.getX(idx)
                val ey = event.getY(idx)
                val dx = ex - lastTouchX
                val dy = ey - lastTouchY
                lastTouchX = ex
                lastTouchY = ey
                dragTotalX += dx
                dragTotalY += dy
                // 长按取消按累计位移判定（dragTotal 即相对按下点的实际位移）：
                // 旧实现用单步位移，慢速小步拖动每步都达不到阈值，手已滑走长按仍触发
                if (abs(dragTotalX) + abs(dragTotalY) > 8 * screenDensity) {
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
                // 与 CustomGestureView 同一守卫：双指同压时被追踪手指已以
                // POINTER_UP 收尾过，剩余手指的 ACTION_UP 不得再命中一次
                if (activePointerId == event.getPointerId(0)) {
                    activePointerId = -1
                    handleUp(event.x, event.y)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 被追踪的手指先抬起（还有其他手指在按）时同样结束手势
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val idx = event.actionIndex
                    activePointerId = -1
                    handleUp(event.getX(idx), event.getY(idx))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                cancelDelayedTaps()
                longPressHandler.removeCallbacks(longPressRunnable)
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** 主动抬起的统一收尾（坐标取抬起的那根手指） */
    private fun handleUp(upX: Float, upY: Float) {
        longPressHandler.removeCallbacks(longPressRunnable)
        parent.requestDisallowInterceptTouchEvent(false)
        if (expandLongPressed) return
        if (!isScrolling) {
            val result = currentRenderer.hitTest(
                upX, upY, width, height, scrollX, isExpanded, screenDensity,
            )
            if (recording && !isRecordingAllowed(result)) return
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
                    val r = Runnable { onTap?.invoke(result) }
                    delayedPalette = r
                    postDelayed(r, 300L)
                    return
                }
            }
            val delayedAction =
                result is KawaiiPanel.TouchResult.ToolbarAction && result.action is PanelAction.CloseKeyboard
            if (delayedAction) {
                val r = Runnable { onTap?.invoke(result) }
                delayedClose = r
                postDelayed(r, 100L)
            } else {
                onTap?.invoke(result)
            }
            if (result is KawaiiPanel.TouchResult.ToolbarAction && (result.action is PanelAction.SwitchKeyboard || result.action is PanelAction.CursorMove)) {
                post { playToolbarPressAt(result.tapX, result.tapY) }
            }
        }
    }
}
