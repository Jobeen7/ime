package com.jobeen.ime.skin.image

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * float_back 的形状等价物（审计 D 项 + E4）。
 *
 * 取代上一轮固定 16dp 圆角的 XML drawable（已删除，不叠加）：
 * - 主体色按深浅取 [SkinImageEquivalents.floatBackGray]／
 *   [SkinImageEquivalents.floatBackDarkGray]；
 * - 圆角按描述实测系数算（[SkinImageEquivalents.floatBackRadiusPx]，
 *   形体 min 边 × 0.3125），bounds 变化时重算；
 * - 投影沿用上一轮几何：投影层铺满 bounds，形体左右各缩 2dp、
 *   底部缩 4dp（投影露边），投影色 #26000000。
 *
 * @param dark true = 深色主体，否则浅色主体。
 * @param density 形体内缩（2dp／4dp）换算用的屏幕密度。
 */
class FloatBackDrawable(
    private val dark: Boolean,
    private val density: Float,
) : Drawable() {
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SkinImageEquivalents.floatBackShadow
        style = Paint.Style.FILL
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) {
            SkinImageEquivalents.floatBackDarkGray
        } else {
            SkinImageEquivalents.floatBackGray
        }
        style = Paint.Style.FILL
    }
    private val shadowRect = RectF()
    private val bodyRect = RectF()
    private var radius: Float = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        shadowRect.set(bounds)
        val side = 2f * density
        val bottom = 4f * density
        bodyRect.set(
            bounds.left + side, bounds.top.toFloat(),
            bounds.right - side, bounds.bottom - bottom,
        )
        radius = SkinImageEquivalents.floatBackRadiusPx(
            minOf(bodyRect.width(), bodyRect.height()).coerceAtLeast(0f),
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
        canvas.drawRoundRect(bodyRect, radius, radius, bodyPaint)
    }

    override fun setAlpha(alpha: Int) {
        shadowPaint.alpha = alpha
        bodyPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        shadowPaint.colorFilter = colorFilter
        bodyPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
