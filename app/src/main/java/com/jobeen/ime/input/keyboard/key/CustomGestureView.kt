package com.jobeen.ime.input.keyboard.key

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.jobeen.ime.base.feedback.InputFeedbacks

open class CustomGestureView(ctx: Context) : FrameLayout(ctx) {

    enum class SwipeAxis { X, Y }

    enum class GestureType { Down, Move, Up, Cancel }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    @Volatile
    private var touchMovedOutside = false

    @Volatile
    private var longPressTriggered = false
    var longPressEnabled = false

    @Volatile
    var longPressFeedbackEnabled = true

    @Volatile
    private var repeatStarted = false
    var repeatEnabled = false
    private val repeatHandler = Handler(Looper.getMainLooper())

    /** MOVE 事件坐标换算复用数组（长按拖动时每事件新建 IntArray 是纯浪费） */
    private val moveLocation = IntArray(2)
    private val repeatRunnable = Runnable { fireRepeat() }

    /** 长按触发瞬间记下的指针 id：长按动作（如语音）穿线取用，免得等首次
     * MOVE 才认领手指时其他手指的抬起已被漏过；无长按触发时为 -1 */
    var longPressPointerId: Int = -1
        private set

    // 长按计时走 Handler + 复用 Runnable：旧实现每次按键都 launch 一个协程
    // 只为 delay 后触发长按（同文件连发路径早已证明 Handler 足够）
    private val longPressRunnable = Runnable {
        if (longPressFeedbackEnabled) {
            InputFeedbacks.hapticFeedback(this, true)
        }
        // 先记 id 再触发：performLongClick 内同步回调监听方，届时即可取到
        longPressPointerId = activePointerId
        longPressTriggered = performLongClick()
    }

    private fun fireRepeat() {
        if (isEnabled) {
            repeatStarted = true
            onRepeatListener?.invoke(this@CustomGestureView)
            repeatHandler.postDelayed(repeatRunnable, RepeatInterval)
        }
    }

    var swipeEnabled = false
    var keyboardGestureEnabled = false
    var swipeRepeatEnabled = false
    var swipeThresholdX = 24f
    var swipeThresholdY = 24f

    private var swipeRepeatTriggered = false
    private var swipeLastX = -1f
    private var swipeLastY = -1f
    private var swipeXUnconsumed = 0f
    private var swipeYUnconsumed = 0f
    private var swipeTotalX = 0
    private var swipeTotalY = 0
    private var gestureConsumed = false

    var doubleTapEnabled = false
    private var lastClickTime = 0L
    private var maybeDoubleTap = false

    // 当前手势的指针 id：只追踪按下本 View 的那根手指。
    // 多指场景下其他指针的 MOVE/UP 不得干扰本手势（坐标串指、提前结束等）。
    private var activePointerId = -1

    var onTouchMoveListener: ((pointerId: Int, rawX: Float, rawY: Float) -> Unit)? = null
    var onTouchDownListener: ((View) -> Unit)? = null
    var onTouchUpListener: ((View) -> Unit)? = null
    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null
    var soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard
    private val touchSlop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = true
        isHapticFeedbackEnabled = true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    private fun pointInView(x: Float, y: Float): Boolean {
        return -touchSlop <= x && -touchSlop <= y && x < (width + touchSlop) && y < (height + touchSlop)
    }

    private fun resetState() {
        touchMovedOutside = false
        if (longPressEnabled) {
            longPressTriggered = false
            repeatHandler.removeCallbacks(longPressRunnable)
        }
        if (repeatEnabled) {
            repeatStarted = false
            repeatHandler.removeCallbacks(repeatRunnable)
        }
        if (swipeEnabled) {
            if (swipeRepeatEnabled) {
                swipeRepeatTriggered = false
            }
            swipeXUnconsumed = 0f
            swipeYUnconsumed = 0f
            swipeTotalX = 0
            swipeTotalY = 0
            gestureConsumed = false
        }
        // double tap state should be preserved on touch up
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                // 已有手指在进行手势时，第二根手指的 DOWN 不重启手势
                if (activePointerId != -1) return true
                activePointerId = event.getPointerId(0)
                val x = event.getX(0)
                val y = event.getY(0)
                drawableHotspotChanged(x, y)
                isPressed = true
                InputFeedbacks.hapticFeedback(this)
                InputFeedbacks.soundEffect(context, soundEffect)
                onTouchDownListener?.invoke(this)
                dispatchGestureEvent(GestureType.Down, x, y)
                if (longPressEnabled) {
                    repeatHandler.removeCallbacks(longPressRunnable)
                    repeatHandler.postDelayed(longPressRunnable, longPressDelay)
                }
                if (repeatEnabled) {
                    repeatHandler.removeCallbacks(repeatRunnable)
                    repeatHandler.postDelayed(repeatRunnable, longPressDelay)
                }
                if (swipeEnabled) {
                    swipeLastX = x
                    swipeLastY = y
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 其他手指按下与本手势无关，不接管
                return true
            }

            MotionEvent.ACTION_UP -> {
                // 双指同压时被追踪手指会先以 POINTER_UP 收尾（activePointerId
                // 已置 -1），剩余手指抬起产生的 ACTION_UP 不能再收尾一次，
                // 否则 handleRelease 以复位后的状态重算出 click，动作触发两次
                if (activePointerId == event.getPointerId(0)) {
                    activePointerId = -1
                    handleRelease(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 只有被追踪的那根手指抬起才算手势结束；其他手指抬起忽略
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val idx = event.actionIndex
                    activePointerId = -1
                    handleRelease(event.getX(idx), event.getY(idx))
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val x = event.getX(idx)
                val y = event.getY(idx)
                drawableHotspotChanged(x, y)
                if (longPressTriggered) {
                    // raw 坐标按追踪指针换算（event.rawX 永远是 0 号指针的）
                    val loc = moveLocation
                    getLocationOnScreen(loc)
                    onTouchMoveListener?.invoke(activePointerId, loc[0] + x, loc[1] + y)
                }
                if (!touchMovedOutside && !pointInView(x, y)) {
                    touchMovedOutside = true
                    if (longPressEnabled) {
                        repeatHandler.removeCallbacks(longPressRunnable)
                    }
                    if (repeatEnabled) {
                        repeatHandler.removeCallbacks(repeatRunnable)
                    }
                    if (repeatStarted || (!swipeEnabled && !keyboardGestureEnabled)) {
                        isPressed = false
                    }
                }
                if ((!swipeEnabled && !keyboardGestureEnabled) || (longPressTriggered && !keyboardGestureEnabled && !swipeEnabled) || (repeatStarted && !swipeEnabled && !keyboardGestureEnabled)) return true
                val countX = consumeSwipe(x, SwipeAxis.X)
                val countY = consumeSwipe(y, SwipeAxis.Y)
                dispatchGestureEvent(GestureType.Move, x, y, countX, countY)
                swipeLastX = x
                swipeLastY = y
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // CANCEL 是手势被系统/父容器打断，不是用户主动抬起：
                // 不能当作 UP——不触发 onTouchUpListener（语音会误上屏、
                // 弹窗会误选词），只做状态回收，并以 Cancel 类型通知手势监听方。
                val idx = event.findPointerIndex(activePointerId)
                val x = if (idx >= 0) event.getX(idx) else 0f
                val y = if (idx >= 0) event.getY(idx) else 0f
                activePointerId = -1
                isPressed = false
                dispatchGestureEvent(GestureType.Cancel, x, y)
                resetState()
                // reset double tap state on cancel
                if (doubleTapEnabled) {
                    maybeDoubleTap = false
                    lastClickTime = 0
                }
                return true
            }
        }
        return true
    }

    /** 主动抬起（UP / 追踪指针的 POINTER_UP）的统一收尾：触发抬起回调与点击判定 */
    private fun handleRelease(x: Float, y: Float) {
        isPressed = false
        onTouchUpListener?.invoke(this)
        dispatchGestureEvent(GestureType.Up, x, y)
        val shouldPerformClick =
            !(touchMovedOutside || longPressTriggered || repeatStarted || swipeRepeatTriggered || gestureConsumed)
        resetState()
        if (shouldPerformClick) {
            if (doubleTapEnabled) {
                val now = System.currentTimeMillis()
                if (maybeDoubleTap && now - lastClickTime <= longPressDelay) {
                    maybeDoubleTap = false
                    onDoubleTapListener?.invoke(this)
                } else {
                    maybeDoubleTap = true
                    performClick()
                }
                lastClickTime = now
            } else {
                performClick()
            }
        }
    }

    private fun dispatchGestureEvent(
        type: GestureType, x: Float, y: Float, countX: Int = 0, countY: Int = 0
    ) {
        val event = Event(type, gestureConsumed, x, y, countX, countY, swipeTotalX, swipeTotalY)
        val consumed = onGestureListener?.onGesture(this, event) ?: return
        if (consumed && !gestureConsumed) {
            gestureConsumed = true
            // 手势已被消费（如空格慢滑移光标）：取消长按与连按，
            // 否则慢滑超过长按时间会误触语音输入
            if (longPressEnabled && !longPressTriggered) {
                repeatHandler.removeCallbacks(longPressRunnable)
            }
            if (repeatEnabled && !repeatStarted) {
                repeatHandler.removeCallbacks(repeatRunnable)
            }
        }
    }

    private fun consumeSwipe(current: Float, axis: SwipeAxis): Int {
        val unconsumed: Float
        val threshold: Float
        when (axis) {
            SwipeAxis.X -> {
                unconsumed = current - swipeLastX + swipeXUnconsumed
                threshold = swipeThresholdX
            }

            SwipeAxis.Y -> {
                unconsumed = current - swipeLastY + swipeYUnconsumed
                threshold = swipeThresholdY
            }
        }
        val remains: Float = unconsumed % threshold
        val count: Int = (unconsumed / threshold).toInt()
        if (count != 0) {
            if (swipeRepeatEnabled && !swipeRepeatTriggered) {
                swipeRepeatTriggered = true
            }
            if (longPressEnabled && !longPressTriggered) {
                repeatHandler.removeCallbacks(longPressRunnable)
            }
            if (repeatEnabled && !repeatStarted) {
                repeatHandler.removeCallbacks(repeatRunnable)
            }
        }
        when (axis) {
            SwipeAxis.X -> {
                swipeXUnconsumed = remains
                swipeTotalX += count
            }

            SwipeAxis.Y -> {
                swipeYUnconsumed = remains
                swipeTotalY += count
            }
        }
        return count
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        repeatHandler.removeCallbacks(repeatRunnable)
        repeatHandler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    companion object {
        const val longPressDelay = 250L
        const val RepeatInterval = 100L
    }
}
