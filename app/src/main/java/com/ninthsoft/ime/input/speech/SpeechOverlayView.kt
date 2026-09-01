package com.ninthsoft.ime.input.speech

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import com.ninthsoft.ime.R

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SpeechOverlayView(
    context: Context,
    type: WaveViewType = WaveViewType.PARTICLE,
) : FrameLayout(context) {

    enum class WaveViewType { PARTICLE, SPECTRUM }

    enum class DragTarget { CLOSE, LOCK, NONE }

    interface OnSpeechActionListener {
        fun onClose()
        fun onLockStateChanged(isLocked: Boolean)
    }

    companion object {
        private const val CAPSULE_ALPHA = 30
        private const val HIGHLIGHT_ALPHA = 100
        private const val BUTTON_HEIGHT_RATIO = 0.12f
        private const val BUTTON_MAX_RATIO = 0.32f
        private const val BUTTON_MARGIN_RATIO = 0.056f
    }

    var onSpeechActionListener: OnSpeechActionListener? = null

    var isLocked: Boolean = false
        private set

    private var bgColor: Int = Color.TRANSPARENT
    private var cachedKeyText: Int = Color.CYAN
    private var cachedKeyBg: Int = Color.CYAN
    private var cachedKeyPressed: Int = Color.CYAN
    private var cachedWaveformColor: Int = Color.CYAN
    private var cachedBarColor: Int = Color.CYAN
    private var waveViewType: WaveViewType = type
    private var waveView: ISpeechView = createWaveView(type, context)

    private val density = context.resources.displayMetrics.density
    private var buttonHeight = (32f * density).toInt()
    private var buttonMaxSize = (88f * density).toInt()
    private var buttonMargin = (16f * density).toInt()
    private val closeButton: ImageView
    private val lockButton: ImageView
    private val closeButtonBg: GradientDrawable
    private val lockButtonBg: GradientDrawable
    private var cachedCapsuleBg: Int = 0
    private var dragTarget: ImageView? = null

    private fun createWaveView(type: WaveViewType, context: Context): ISpeechView = when (type) {
        WaveViewType.PARTICLE -> ParticleWaveView(context)
        WaveViewType.SPECTRUM -> SpectrumWaveView(context)
    }

    fun switchWaveView(type: WaveViewType) {
        if (waveViewType == type) return
        waveViewType = type
        val wasVisible = isVisible
        if (wasVisible) {
            waveView.stopAnim()
        }
        removeView(waveView.view)
        waveView.release()
        waveView = createWaveView(type, context)
        waveView.updateColors(Color.TRANSPARENT, cachedWaveformColor, cachedBarColor)
        addView(
            waveView.view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        if (wasVisible) {
            waveView.startAnim()
        }
    }

    fun unlock() {
        isLocked = false
        lockButton.setImageResource(R.drawable.ic_keyboard_lock_open_outline)
        closeButton.setImageResource(R.drawable.ic_keyboard_arrow_back)
        lockButtonBg.setColor(cachedCapsuleBg)
    }

    fun setDragLocked() {
        isLocked = true
        lockButton.setImageResource(R.drawable.ic_keyboard_lock_outline)
        closeButton.setImageResource(R.drawable.ic_keyboard_close)
        highlightButton(lockButton, lockButtonBg)
    }

    fun applyColors(
        overlayBg: Int,
        keyBg: Int,
        keyPressed: Int,
        keyText: Int,
        waveformColor: Int = keyText,
        barColor: Int = waveformColor,
    ) {
        bgColor = overlayBg
        cachedKeyBg = keyBg
        cachedKeyPressed = keyPressed
        cachedKeyText = keyText
        cachedWaveformColor = waveformColor
        cachedBarColor = barColor
        waveView.updateColors(Color.TRANSPARENT, waveformColor, barColor)
        applyButtonColors()
    }

    private fun applyButtonColors() {
        cachedCapsuleBg = Color.argb(
            CAPSULE_ALPHA,
            Color.red(cachedKeyBg),
            Color.green(cachedKeyBg),
            Color.blue(cachedKeyBg)
        )
        closeButtonBg.setColor(cachedCapsuleBg)
        if (isLocked) {
            highlightButton(lockButton, lockButtonBg)
        } else {
            lockButtonBg.setColor(cachedCapsuleBg)
        }
        closeButton.setColorFilter(cachedKeyText)
        lockButton.setColorFilter(cachedKeyText)
    }

    private fun highlightButton(button: ImageView, bg: GradientDrawable) {
        val hl = Color.argb(
            HIGHLIGHT_ALPHA,
            Color.red(cachedKeyPressed),
            Color.green(cachedKeyPressed),
            Color.blue(cachedKeyPressed)
        )
        bg.setColor(hl)
    }

    private fun resetHighlight() {
        closeButtonBg.setColor(cachedCapsuleBg)
        lockButtonBg.setColor(cachedCapsuleBg)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { updateButtonDimensions() }
    }

    private fun updateButtonDimensions() {
        val w = waveView.view.width
        val h = waveView.view.height
        if (w <= 0 || h <= 0) return
        val minDim = minOf(w, h).toFloat()
        val capsulePad = minDim * 0.08f
        val buttonSize =
            ((h - capsulePad * 3.6f) * 0.55f * 0.66f).toInt().coerceIn(buttonHeight, buttonMaxSize)

        buttonHeight = (h * BUTTON_HEIGHT_RATIO).toInt()
        buttonMaxSize = (h * BUTTON_MAX_RATIO).toInt()
        buttonMargin = (h * BUTTON_MARGIN_RATIO).toInt()

        val lp = LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setMargins(buttonMargin, 0, 0, 0)
        }
        closeButton.layoutParams = lp

        val rp = LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setMargins(0, 0, buttonMargin, 0)
        }
        lockButton.layoutParams = rp
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = GONE
        isClickable = true
        isFocusable = true
        addView(waveView.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val defaultColor = Color.CYAN
        val defaultCapsuleBg = Color.argb(
            CAPSULE_ALPHA,
            Color.red(defaultColor),
            Color.green(defaultColor),
            Color.blue(defaultColor)
        )

        closeButtonBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(defaultCapsuleBg)
        }
        closeButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_keyboard_close_circle_outline)
            background = closeButtonBg
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onSpeechActionListener?.onClose()
            }
        }
        addView(closeButton, LayoutParams(buttonHeight, buttonHeight).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setMargins(buttonMargin, 0, 0, 0)
        })

        lockButtonBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(defaultCapsuleBg)
        }
        lockButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_keyboard_lock_open_outline)
            background = lockButtonBg
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isLocked) return@setOnClickListener
                isLocked = !isLocked
                setImageResource(
                    if (isLocked) R.drawable.ic_keyboard_lock_outline
                    else R.drawable.ic_keyboard_lock_open_outline
                )
                if (isLocked) {
                    highlightButton(lockButton, lockButtonBg)
                } else {
                    lockButtonBg.setColor(cachedCapsuleBg)
                }
                onSpeechActionListener?.onLockStateChanged(isLocked)
            }
        }
        addView(lockButton, LayoutParams(buttonHeight, buttonHeight).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setMargins(0, 0, buttonMargin, 0)
        })

        val closeHitRect = Rect()
        val lockHitRect = Rect()

        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                closeButton.getHitRect(closeHitRect)
                lockButton.getHitRect(lockHitRect)
                if (closeHitRect.contains(event.x.toInt(), event.y.toInt()) || lockHitRect.contains(
                        event.x.toInt(), event.y.toInt()
                    )
                ) {
                    return@setOnTouchListener false
                }
            }
            true
        }
    }

    fun onDragPosition(rawX: Float, rawY: Float) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val localX = rawX.toInt() - location[0]
        val localY = rawY.toInt() - location[1]

        val closeRect = Rect()
        closeButton.getHitRect(closeRect)
        val lockRect = Rect()
        lockButton.getHitRect(lockRect)

        when {
            closeRect.contains(localX, localY) -> {
                if (dragTarget !== closeButton) {
                    resetHighlight()
                    highlightButton(closeButton, closeButtonBg)
                    dragTarget = closeButton
                }
            }

            lockRect.contains(localX, localY) -> {
                if (dragTarget !== lockButton) {
                    resetHighlight()
                    highlightButton(lockButton, lockButtonBg)
                    dragTarget = lockButton
                }
            }

            else -> {
                resetHighlight()
                dragTarget = null
            }
        }
    }

    val currentDragTarget: DragTarget
        get() = when (dragTarget) {
            closeButton -> DragTarget.CLOSE
            lockButton -> DragTarget.LOCK
            else -> DragTarget.NONE
        }

    fun show() {
        animate().cancel()
        visibility = VISIBLE
        setBackgroundColor(
            Color.argb(
                230, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)
            )
        )
        alpha = 0f
        animate().alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        waveView.view.apply {
            animate().cancel()
            scaleX = 1.3f
            scaleY = 1.3f
            animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        waveView.startAnim()
    }

    fun hide() {
        animate().cancel()
        animate().alpha(0f).setDuration(100).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                visibility = GONE
                waveView.stopAnim()
            }.start()
    }

    fun updateAmplitude(amplitude: Float) {
        val a = (amplitude.coerceIn(0f, 1f) * 1.6).coerceAtMost(1.0)
        val mapped = ln(1.0 + 18.0 * a) / ln(1.0 + 18.0)
        val vol = (mapped * 100).toInt().coerceIn(0, 100)
        waveView.setVolume(vol)
    }

    private fun ln(d: Double): Double = kotlin.math.ln(d)
}
