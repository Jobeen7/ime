package com.ninthsoft.ime.input.keyboard.key

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.sqrt

/**
 * 键盘点击水波纹效果视图。
 * 通过扩散的不规则圆环模拟水波纹动画，避免使用截屏 + drawBitmapMesh
 * 导致的软件/硬件渲染差异引发的按键背景变亮问题。
 */
class KeyboardRippleView(
    ctx: Context,
) : View(ctx) {

    private companion object {
        // 正弦查找表位宽
        private const val SIN_BITS = 8
        private const val SIN_MASK = (1 shl SIN_BITS) - 1
        private const val SIN_COUNT = 1 shl SIN_BITS

        // 预先计算的正弦值表，提升性能
        private val SIN_TABLE = FloatArray(SIN_COUNT).apply {
            val step = 2f * Math.PI.toFloat() / SIN_COUNT
            for (i in indices) {
                this[i] = kotlin.math.sin(i * step)
            }
        }

        // 1 / (2*PI)，用于相位转索引
        private const val TWO_PI_INV = 0.159154943f

        private const val ANIM_DURATION = 650L
        private const val RING_COUNT = 2
    }

    private var animProgress = 0f
    private var animator: ValueAnimator? = null
    var rippleEnabled = true

    fun cancelRipple() {
        animator?.cancel()
        animator = null
        animProgress = 0f
    }

    // 波纹中心
    private var cx = 0f
    private var cy = 0f

    // 每次波纹的随机扰动参数
    private val perturbAmps = FloatArray(4)
    private val perturbFreqs = floatArrayOf(2f, 3f, 5f, 7f)
    private val perturbPhases = FloatArray(4)
    private var ringScale = 1f
    private var radiusMultiplier = 1f

    private val density = resources.displayMetrics.density

    // 绘制圆环的画笔
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(180, 180, 230, 255)
        strokeWidth = 2f * density
    }

    // 用于绘制不规则水波圆环的路径
    private val ringPath = Path()
    private val ringSegments = 22

    /** 绘制一条带有角度扰动的水波环 */
    private fun drawWaterRing(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float, paint: Paint) {
        if (baseRadius <= 0f) return
        ringPath.rewind()
        val step = (2f * kotlin.math.PI).toFloat() / ringSegments
        for (i in 0 until ringSegments) {
            val angle = i * step
            var perturb = 1f
            for (j in perturbAmps.indices) {
                perturb += perturbAmps[j] * sinLut(perturbFreqs[j] * angle + perturbPhases[j])
            }
            val r = baseRadius * perturb
            val px = cx + r * sinLut(angle + kotlin.math.PI.toFloat() / 2f)
            val py = cy + r * sinLut(angle)
            if (i == 0) ringPath.moveTo(px, py) else ringPath.lineTo(px, py)
        }
        ringPath.close()
        canvas.drawPath(ringPath, paint)
    }

    /**
     * 启动波纹动画。
     * @param x 触摸中心 x 坐标（相对于本视图）
     * @param y 触摸中心 y 坐标（相对于本视图）
     */
    fun startRipple(x: Float, y: Float, sourceView: View? = null) {
        if (!rippleEnabled || width <= 0 || height <= 0) return

        cx = x
        cy = y

        for (i in perturbAmps.indices) {
            perturbAmps[i] = 0.001f + Math.random().toFloat() * 0.008f
            perturbPhases[i] = (Math.random().toFloat() * 2f * kotlin.math.PI).toFloat()
        }
        ringScale = 0.5f + Math.random().toFloat() * 0.3f
        radiusMultiplier = if (sourceView is KeyView && sourceView.def.viewId == KeyView.button_space) 1.5f else 1f
        animProgress = 0f

        animator?.let {
            it.cancel()
            it.start()
        } ?: run {
            ValueAnimator.ofFloat(0f, 1f).also {
                it.duration = ANIM_DURATION
                it.interpolator = DecelerateInterpolator()
                it.addUpdateListener { anim ->
                    animProgress = anim.animatedFraction
                    invalidate()
                }
                animator = it
                it.start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!rippleEnabled) return
        val t = animProgress
        if (t <= 0f || t >= 1f) return

        // 绘制扩散的圆环（带角度扰动，模拟水波不规则扩散）
        val ringProgress = sqrt(t * 0.35f)
        val waveFade = (1f - t).coerceIn(0f, 1f)
        for (i in 0 until RING_COUNT) {
            val radius = 140f * density * ringScale * radiusMultiplier * (ringProgress - i * 0.1f)
            if (radius <= 0f) continue
            val alpha = (waveFade * 50 - i * 16).toInt().coerceIn(5, 50)
            wavePaint.alpha = alpha
            drawWaterRing(canvas, cx, cy, radius, wavePaint)
        }
    }

    /** 快速正弦查找 */
    private fun sinLut(angle: Float): Float {
        val idx = ((angle * SIN_COUNT * TWO_PI_INV).toInt()) and SIN_MASK
        return SIN_TABLE[idx]
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}