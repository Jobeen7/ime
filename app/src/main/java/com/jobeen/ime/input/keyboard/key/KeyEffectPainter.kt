package com.jobeen.ime.input.keyboard.key

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.ColorInt

/**
 * WO38 按键特效绘制（对端 Mie v3.3.9 `KeyEffectPainter` 口径的 Jime 版，
 * 直接画布绘制、不走位图缓存）：
 *
 * - 内阴影：沿键形边缘画一圈模糊描边、裁切到键形内部（对端
 *   `innerShadowBitmap` 同款：描边宽 = 模糊半径 ×2，偏移模拟光源方向）。
 *   与外阴影不同：内阴影不超出键形，无需键盘层统一绘制。
 * - 描边宽与模糊判定是纯函数，可直接单测；画布操作本身由
 *   [StyledKeyDrawable] 在软件层下调用（BlurMaskFilter 须软件层）。
 */
object KeyEffectPainter {

    /** 内阴影描边宽 px：模糊半径 ×2，至少 1px（对端同款下限）。 */
    fun innerStrokeWidthPx(blurPx: Float): Float = (blurPx * 2f).coerceAtLeast(1f)

    /** 模糊半径大于 0.5px 时才挂 BlurMaskFilter（对端同款阈值）。 */
    fun innerNeedsBlur(blurPx: Float): Boolean = blurPx > 0.5f

    /** 按内阴影参数配好一支描边画笔（调用方复用，不逐帧新建）。 */
    fun configureInnerShadowPaint(
        paint: Paint,
        @ColorInt color: Int,
        blurPx: Float,
    ) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = innerStrokeWidthPx(blurPx)
        paint.maskFilter = if (innerNeedsBlur(blurPx)) {
            BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
    }

    /**
     * 沿 [path] 内侧画一圈内阴影。调用方须保证画布已处于软件层；
     * 描边的外半被裁掉，只留内侧一半在键形内，可见带宽 ≈ 模糊半径。
     */
    fun drawInnerShadow(
        canvas: Canvas,
        path: Path,
        paint: Paint,
        dxPx: Float,
        dyPx: Float,
    ) {
        canvas.save()
        canvas.clipPath(path)
        canvas.translate(dxPx, dyPx)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
