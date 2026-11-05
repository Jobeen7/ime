package com.jobeen.ime.input.keyboard.key

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt
import com.jobeen.ime.skin.model.KeyGradient
import com.jobeen.ime.skin.model.KeyGradientType
import kotlin.math.max
import kotlin.math.min

fun radiusDrawable(
    r: Float,
    @ColorInt color: Int = Color.WHITE,
): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = r
}

fun insetRadiusDrawable(
    hInset: Int,
    vInset: Int,
    r: Float = 0f,
    @ColorInt color: Int = Color.WHITE,
): Drawable = InsetDrawable(
    radiusDrawable(r, color),
    hInset, vInset, hInset, vInset,
)

fun insetOvalDrawable(
    hInset: Int,
    vInset: Int,
    @ColorInt color: Int = Color.WHITE,
): Drawable = InsetDrawable(
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    },
    hInset, vInset, hInset, vInset,
)

fun shadowedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    shadowWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        radiusDrawable(radius, shadowColor),
        radiusDrawable(radius, bkgColor),
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin - shadowWidth)
    setLayerInset(1, hMargin, vMargin, hMargin, vMargin)
}

fun flatKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, strokeColor)
        },
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

fun borderedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = borderedKeyBackgroundDrawable(
    bkgColor, strokeColor, radius, strokeWidth, hMargin, vMargin, hMargin, vMargin,
)

/** L4 四边版：左／上／右／下内缩可各不相同；对称值与两值版逐像素一致。 */
fun borderedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMarginLeft: Int,
    vMarginTop: Int,
    hMarginRight: Int,
    vMarginBottom: Int,
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, strokeColor)
        },
    ),
).apply {
    setLayerInset(0, hMarginLeft, vMarginTop, hMarginRight, vMarginBottom)
}

/**
 * 拟态双阴影键面（2026-10-29 item-1）：左上亮影／右下暗影／主形
 * 三层。两影层为半透明圆角矩形相对主形反向偏移 [offsetPx]；
 * 柔和来自半透明色＋小偏移，不做逐像素模糊（每键仅多两个
 * GradientDrawable，零位图分配、零热路径成本；偏移超键间距
 * 时内缩钳制为 0，影层贴边、绝不崩）。
 */
fun dualShadowKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
    @ColorInt lightColor: Int,
    @ColorInt darkColor: Int,
    offsetPx: Int,
): Drawable = dualShadowKeyBackgroundDrawable(
    bkgColor, strokeColor, radius, strokeWidth,
    hMargin, vMargin, hMargin, vMargin,
    lightColor, darkColor, offsetPx,
)

/** L4 四边版：影层按四边内缩各自偏移；对称值与两值版逐像素一致。 */
fun dualShadowKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMarginLeft: Int,
    vMarginTop: Int,
    hMarginRight: Int,
    vMarginBottom: Int,
    @ColorInt lightColor: Int,
    @ColorInt darkColor: Int,
    offsetPx: Int,
): Drawable {
    val o = offsetPx.coerceAtLeast(0)
    val main = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(bkgColor)
        setStroke(strokeWidth, strokeColor)
    }
    val light = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(lightColor)
    }
    val dark = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(darkColor)
    }
    return LayerDrawable(arrayOf(light, dark, main)).apply {
        // 左上亮：左/上内缩减小、右/下内缩增大 → 整体向左上偏移。
        setLayerInset(
            0,
            (hMarginLeft - o).coerceAtLeast(0), (vMarginTop - o).coerceAtLeast(0),
            hMarginRight + o, vMarginBottom + o,
        )
        // 右下暗：反向偏移。
        setLayerInset(
            1,
            hMarginLeft + o, vMarginTop + o,
            (hMarginRight - o).coerceAtLeast(0), (vMarginBottom - o).coerceAtLeast(0),
        )
        setLayerInset(2, hMarginLeft, vMarginTop, hMarginRight, vMarginBottom)
    }
}

fun highlightMaskDrawable(
    @ColorInt color: Int,
    bordered: Boolean,
    hMargin: Int,
    vMargin: Int,
    radius: Float,
): Drawable = if (bordered) {
    insetRadiusDrawable(hMargin, vMargin, radius, color)
} else {
    InsetDrawable(ColorDrawable(color), hMargin, vMargin, hMargin, vMargin)
}

/**
 * 样式键面（审计 A 项）：单支 Drawable 画完填充（纯色或渐变）、
 * 描边、模糊阴影与下边缘条。只在键带样式几何面时使用；无几何
 * 面的键一律走既有 GradientDrawable 路径，行为逐字节不变。
 *
 * - 渐变：线性取起止点（缺省上→下）、径向取中心半长边、锥形
 *   取中心扫掠；位置数组缺省均匀分布（解析期已保证等长）。
 * - 阴影：BlurMaskFilter 模糊形状先画（偏移后、填充之下），
 *   须配合视图软件层（KeyView 在有阴影时设置）；无阴影时
 *   不画此层、不碰图层类型。
 * - 下边缘：裁剪到键形内再画底部窄条，圆角处不溢出。
 * - 连续圆角：三次贝塞尔近似（控制距 0.75r，比圆弧更贴角、
 *   过渡更平滑）；圆弧仍走系统 addRoundRect。
 * - 透明度：setAlpha 作用于填充/描边/阴影/下边缘全部画笔，
 *   按压淡入淡出沿用既有 LayerDrawable alpha 机制。
 */
class StyledKeyDrawable(
    @ColorInt private val solidColor: Int,
    private val gradient: KeyGradient?,
    private val cornerRadiusPx: Float,
    private val strokeWidthPx: Int = 0,
    @ColorInt private val strokeColor: Int = Color.TRANSPARENT,
    private val continuous: Boolean = false,
    /** 已乘不透明度的阴影色（见 KeyShadowLayer.effectiveColor）；null = 无阴影。 */
    @ColorInt private val shadowColor: Int? = null,
    private val shadowRadiusPx: Float = 0f,
    private val shadowDxPx: Float = 0f,
    private val shadowDyPx: Float = 0f,
    @ColorInt private val lowerEdgeColor: Int? = null,
    private val lowerEdgeHeightPx: Int = 0,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lowerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var drawableAlpha = 255
    private var fillShader: Shader? = null

    init {
        strokePaint.strokeWidth = strokeWidthPx.toFloat()
        strokePaint.color = strokeColor
        lowerEdgePaint.color = lowerEdgeColor ?: Color.TRANSPARENT
        if (shadowColor != null && shadowRadiusPx > 0f) {
            shadowPaint.maskFilter = BlurMaskFilter(shadowRadiusPx, BlurMaskFilter.Blur.NORMAL)
        }
        shadowPaint.color = shadowColor ?: Color.TRANSPARENT
        applyAlpha()
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        fillShader = gradient?.let { shaderFor(it, RectF(bounds)) }
    }

    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds)
        if (rect.isEmpty) return
        val radius = min(cornerRadiusPx, min(rect.width(), rect.height()) / 2f)
            .coerceAtLeast(0f)
        val path = cornerPath(rect, radius, continuous)
        if (shadowColor != null) {
            canvas.save()
            canvas.translate(shadowDxPx, shadowDyPx)
            canvas.drawPath(path, shadowPaint)
            canvas.restore()
        }
        fillPaint.shader = fillShader
        if (gradient == null) {
            fillPaint.color = solidColor
        }
        canvas.drawPath(path, fillPaint)
        if (lowerEdgeColor != null && lowerEdgeHeightPx > 0) {
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(
                rect.left, rect.bottom - lowerEdgeHeightPx,
                rect.right, rect.bottom, lowerEdgePaint,
            )
            canvas.restore()
        }
        if (strokeWidthPx > 0 && strokeColor != Color.TRANSPARENT) {
            val half = strokeWidthPx / 2f
            val inset = RectF(
                rect.left + half, rect.top + half,
                rect.right - half, rect.bottom - half,
            )
            if (!inset.isEmpty) {
                val strokeRadius = (radius - half).coerceAtLeast(0f)
                canvas.drawPath(cornerPath(inset, strokeRadius, continuous), strokePaint)
            }
        }
    }

    private fun shaderFor(gradient: KeyGradient, rect: RectF): Shader {
        val colors = gradient.colors.toIntArray()
        val positions = gradient.locations?.toFloatArray()
        return when (gradient.type) {
            KeyGradientType.LINEAR -> {
                val start = gradient.startPoint
                val end = gradient.endPoint
                LinearGradient(
                    rect.left + (start?.x ?: 0.5f) * rect.width(),
                    rect.top + (start?.y ?: 0f) * rect.height(),
                    rect.left + (end?.x ?: 0.5f) * rect.width(),
                    rect.top + (end?.y ?: 1f) * rect.height(),
                    colors, positions, Shader.TileMode.CLAMP,
                )
            }
            KeyGradientType.RADIAL -> RadialGradient(
                rect.centerX(), rect.centerY(),
                max(rect.width(), rect.height()) / 2f,
                colors, positions, Shader.TileMode.CLAMP,
            )
            KeyGradientType.CONIC -> SweepGradient(
                rect.centerX(), rect.centerY(), colors, positions,
            )
        }
    }

    private fun applyAlpha() {
        fillPaint.alpha = drawableAlpha
        strokePaint.alpha = drawableAlpha
        shadowPaint.alpha = drawableAlpha
        lowerEdgePaint.alpha = drawableAlpha
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        applyAlpha()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        lowerEdgePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * 圆角路径：连续曲线用三次贝塞尔（控制距 0.75r），
         * 圆弧用系统圆角矩形。
         */
        fun cornerPath(rect: RectF, radius: Float, continuous: Boolean): Path {
            val path = Path()
            if (radius <= 0f) {
                path.addRect(rect, Path.Direction.CW)
                return path
            }
            if (!continuous) {
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
                return path
            }
            val r = radius
            val k = r * 0.75f
            path.moveTo(rect.left + r, rect.top)
            path.lineTo(rect.right - r, rect.top)
            path.cubicTo(
                rect.right - r + k, rect.top,
                rect.right, rect.top + r - k,
                rect.right, rect.top + r,
            )
            path.lineTo(rect.right, rect.bottom - r)
            path.cubicTo(
                rect.right, rect.bottom - r + k,
                rect.right - r + k, rect.bottom,
                rect.right - r, rect.bottom,
            )
            path.lineTo(rect.left + r, rect.bottom)
            path.cubicTo(
                rect.left + r - k, rect.bottom,
                rect.left, rect.bottom - r + k,
                rect.left, rect.bottom - r,
            )
            path.lineTo(rect.left, rect.top + r)
            path.cubicTo(
                rect.left, rect.top + r - k,
                rect.left + r - k, rect.top,
                rect.left + r, rect.top,
            )
            path.close()
            return path
        }
    }
}
