// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import com.jobeen.ime.skin.model.KeyGradient
import com.jobeen.ime.skin.model.KeyGradientPoint
import com.jobeen.ime.skin.model.KeyGradientType

/**
 * 键盘背景渐变 Drawable（S8）：
 *
 * KeyGradient 的画布实现——线性默认端点 (0.5,0)→(0.5,1)
 * （对端 axial 缺省），位置缺省均匀分布；径向中心缺省
 * (0.5,0.5)、半径取 bounds 长边一半；锥形中心同径向。
 * 边界变化时重建 Shader（TileMode 恒 CLAMP）。
 * 单色渐变由调用方按纯色处理，不进入此处。
 */
class GradientBackgroundDrawable(
    private val gradient: KeyGradient,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastBounds: Rect? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild(bounds)
    }

    private fun rebuild(bounds: Rect) {
        if (bounds.isEmpty) return
        lastBounds = Rect(bounds)
        val colors = gradient.colors.toIntArray()
        val positions = gradient.locations?.toFloatArray()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        fun px(point: KeyGradientPoint?): Pair<Float, Float> {
            val p = point ?: KeyGradientPoint(0.5f, 0.5f)
            return (bounds.left + p.x * w) to (bounds.top + p.y * h)
        }
        paint.shader = when (gradient.type) {
            KeyGradientType.LINEAR -> {
                val (x0, y0) = px(gradient.startPoint ?: KeyGradientPoint(0.5f, 0f))
                val (x1, y1) = px(gradient.endPoint ?: KeyGradientPoint(0.5f, 1f))
                LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
            }
            KeyGradientType.RADIAL -> {
                val (cx, cy) = px(gradient.startPoint)
                val radius = maxOf(w, h) / 2f
                RadialGradient(cx, cy, radius, colors, positions, Shader.TileMode.CLAMP)
            }
            KeyGradientType.CONIC -> {
                val (cx, cy) = px(gradient.startPoint)
                SweepGradient(cx, cy, colors, positions)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (lastBounds != b) rebuild(b)
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
