package com.jobeen.ime.skin.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.jobeen.ime.skin.model.ImageContentMode
import com.jobeen.ime.skin.model.NinePatchSlice
import com.jobeen.ime.skin.model.SkinImageDescriptor
import com.jobeen.ime.skin.model.SpriteFrame
import com.jobeen.ime.skin.model.SpriteInsets
import com.jobeen.ime.skin.model.SpriteRect
import com.jobeen.ime.skin.model.contentModeDstRect
import com.jobeen.ime.skin.model.cropOrFull
import com.jobeen.ime.skin.model.effectiveCapInsets
import com.jobeen.ime.skin.model.lookupSpriteFrame
import com.jobeen.ime.skin.model.ninePatchSlices

/**
 * 图像描述的 Android 渲染层（审计 D 项）。
 *
 * - [ImageContentMode.toScaleType]：键面 ImageView 的模式映射
 *   （scaleToFill→FIT_XY、scaleAspectFit→FIT_CENTER、
 *   scaleAspectFill→CENTER_CROP、center→CENTER）。
 * - [SkinImageDrawable]：单张位图按 contentMode 在 bounds 内绘制
 *   （等比铺满／原尺寸居中溢出时裁剪到 bounds，不越界）。
 * - [NinePatchBitmapDrawable]：9-patch 切片绘制（平台
 *   NinePatchDrawable 的等价实现，不依赖 chunk 二进制格式：
 *   角部按源像素宽绘制，边与中段拉伸；切片几何复用纯层
 *   [ninePatchSlices]，边框盖满致无拉伸段时回退整图拉伸）。
 * - [drawableFor]：描述 + 精灵图位图 → 可绘制对象（常态／高亮
 *   各调一次；高亮缺失时调用方用常态结果，不在此回退）。
 *
 * 像素空间约定：cap 边框与 bounds 皆以像素计（精灵图位图为
 * 自然像素、bounds 为 View 像素）；源像素与 View 像素的
 * density 换算由调用方在传入 bounds 前完成（工具栏绘制本身
 * 即像素空间，键面 ImageView 由 ScaleType 处理）。
 */

/** 内容模式 → Android ScaleType（键面 ImageView 用）。 */
fun ImageContentMode.toScaleType(): ImageView.ScaleType = when (this) {
    ImageContentMode.SCALE_TO_FILL -> ImageView.ScaleType.FIT_XY
    ImageContentMode.SCALE_ASPECT_FIT -> ImageView.ScaleType.FIT_CENTER
    ImageContentMode.SCALE_ASPECT_FILL -> ImageView.ScaleType.CENTER_CROP
    ImageContentMode.CENTER -> ImageView.ScaleType.CENTER
}

/** 单张位图按 contentMode 绘制（bounds 外裁剪，永不越界）。 */
class SkinImageDrawable(
    private val bitmap: Bitmap,
    private val mode: ImageContentMode,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
    private val dstRect = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.isRecycled) return
        val dst = contentModeDstRect(
            mode, bitmap.width, bitmap.height,
            bounds.width().toFloat(), bounds.height().toFloat(),
        )
        dstRect.set(
            bounds.left + dst.left, bounds.top + dst.top,
            bounds.left + dst.right, bounds.top + dst.bottom,
        )
        val checkpoint = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (bitmap.hasAlpha()) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}

/** 9-patch 切片绘制（位图 + 帧矩形 + cap 边框；bounds 变化时重算切片）。 */
class NinePatchBitmapDrawable(
    private val bitmap: Bitmap,
    private val frame: SpriteRect,
    private val insets: SpriteInsets,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var slices: List<NinePatchSlice> = emptyList()
    private var lastWidth: Int = -1
    private var lastHeight: Int = -1

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        lastWidth = -1
        lastHeight = -1
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.isRecycled) return
        if (bounds.width() != lastWidth || bounds.height() != lastHeight) {
            slices = ninePatchSlices(
                bitmap.width, bitmap.height, frame, insets,
                bounds.width().toFloat(), bounds.height().toFloat(),
            )
            lastWidth = bounds.width()
            lastHeight = bounds.height()
        }
        if (slices.isEmpty()) {
            // 边框盖满无拉伸段：回退整图拉伸（与 contentMode 铺满一致）。
            dstRect.set(bounds)
            srcRect.set(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            return
        }
        for (slice in slices) {
            srcRect.set(slice.srcLeft, slice.srcTop, slice.srcRight, slice.srcBottom)
            dstRect.set(
                bounds.left + slice.dstLeft, bounds.top + slice.dstTop,
                bounds.left + slice.dstRight, bounds.top + slice.dstBottom,
            )
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (bitmap.hasAlpha()) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}

/**
 * 描述 + 精灵图位图 → 可绘制对象。
 *
 * @param highlight true = 高亮变体（描述无 highlight 时返回 null，
 *   调用方回退常态结果）；false = 常态变体。
 * @return null = 帧脏数据（存在但与 sheet 无交集）或位图无效；
 *   帧缺失时按整图计（不返回 null）。
 */
fun drawableFor(
    sheetBitmap: Bitmap,
    descriptor: SkinImageDescriptor.File,
    spriteMaps: Map<String, Map<String, SpriteFrame>>,
    highlight: Boolean,
): Drawable? {
    if (sheetBitmap.isRecycled || sheetBitmap.width <= 0 || sheetBitmap.height <= 0) {
        return null
    }
    if (highlight && descriptor.highlight == null) return null
    val frame = lookupSpriteFrame(descriptor, spriteMaps, highlight)
    val crop = cropOrFull(frame, sheetBitmap.width, sheetBitmap.height) ?: return null
    val cropped = if (crop.x == 0 && crop.y == 0 &&
        crop.width == sheetBitmap.width && crop.height == sheetBitmap.height
    ) {
        sheetBitmap
    } else {
        runCatching {
            Bitmap.createBitmap(sheetBitmap, crop.x, crop.y, crop.width, crop.height)
        }.getOrNull() ?: return null
    }
    val caps = effectiveCapInsets(descriptor, frame)
    if (!caps.isZero) {
        val slices = ninePatchSlices(
            cropped.width, cropped.height,
            SpriteRect(0, 0, cropped.width, cropped.height), caps,
            cropped.width.toFloat(), cropped.height.toFloat(),
        )
        if (slices.isNotEmpty()) {
            return NinePatchBitmapDrawable(
                cropped, SpriteRect(0, 0, cropped.width, cropped.height), caps,
            )
        }
        // 边框盖满：落到 contentMode 绘制（不丢图）。
    }
    return SkinImageDrawable(cropped, descriptor.contentMode)
}
