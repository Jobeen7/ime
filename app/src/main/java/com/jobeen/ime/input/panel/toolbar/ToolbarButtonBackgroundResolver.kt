package com.jobeen.ime.input.panel.toolbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.image.drawableFor
import com.jobeen.ime.skin.model.SkinImageDescriptor
import com.jobeen.ime.skin.model.SkinImageDescriptorSnapshot
import com.jobeen.ime.skin.model.SpriteFrame
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.File

/**
 * 皮肤工具栏钮背景图解析（渲染构造期冷路径，非绘制热路径；
 * 与 [SkinToolbarIconResolver] 同口径：内容寻址取实体文件解码，
 * 任一失败只记元数据日志，绝不抛异常）。
 *
 * - 常态解码失败 → 整背景为 null（调用方不画背景，旧口径）；
 * - 高亮解码失败 → 高亮回退常态（变体选择规则，见
 *   SkinImageDescriptor 模型注释）；
 * - 常态与高亮同 sheet 时只解码一次；
 * - assetImage 描述由 [builtinImageProvider] 解析（SkinToolbarApplier
 *   注入 BuiltinImageRegistry.contextProvider；无此资源时背景为
 *   null 并记一条 WARN）。
 */
object ToolbarButtonBackgroundResolver {

    /** 解析结果：常态必备（null = 无背景）；高亮缺失时与常态相同。 */
    data class Resolved(
        val normal: Drawable,
        val highlight: Drawable,
    )

    fun resolve(
        context: Context,
        skinId: String,
        snapshot: SkinImageDescriptorSnapshot?,
        spriteMaps: Map<String, Map<String, SpriteFrame>>,
        builtinImageProvider: ((String) -> Drawable?)? = null,
    ): Resolved? {
        if (snapshot == null) return null
        return runCatching {
            when (val descriptor = snapshot.descriptor) {
                is SkinImageDescriptor.Asset -> {
                    val drawable = builtinImageProvider?.invoke(descriptor.name)
                    if (drawable == null) {
                        DiagLog.w(
                            "toolbar", "toolbar_button_background_asset_unresolved",
                            mapOf("skinId" to skinId, "name" to descriptor.name),
                        )
                        null
                    } else {
                        Resolved(drawable, drawable)
                    }
                }
                is SkinImageDescriptor.File -> {
                    val sheets = LinkedHashMap<String, Bitmap>()
                    fun sheetBitmap(assetId: String): Bitmap? {
                        sheets[assetId]?.let { return it }
                        val ref = snapshot.sheets[assetId] ?: return null
                        val file = SkinLibrary.layout.assetFile(
                            ref.sha256, ref.ext.ifEmpty { null },
                        )
                        val bitmap = decodeSheetFile(file, skinId, assetId) ?: return null
                        sheets[assetId] = bitmap
                        return bitmap
                    }
                    val normalBitmap = sheetBitmap(descriptor.normal.file) ?: run {
                        DiagLog.w(
                            "toolbar", "toolbar_button_background_normal_unresolved",
                            mapOf("skinId" to skinId, "asset" to descriptor.normal.file),
                        )
                        return null
                    }
                    val normal = drawableFor(normalBitmap, descriptor, spriteMaps, false)
                        ?: return null
                    val highlight = descriptor.highlight?.let { ref ->
                        val bitmap = sheetBitmap(ref.file)
                        bitmap?.let { drawableFor(it, descriptor, spriteMaps, true) }
                    } ?: normal
                    Resolved(normal, highlight)
                }
            }
        }.onFailure { error ->
            DiagLog.e(
                "toolbar", "toolbar_button_background_resolve_failed",
                mapOf(
                    "skinId" to skinId,
                    "errorClass" to error.javaClass.simpleName,
                ),
                throwable = error,
            )
        }.getOrNull()
    }

    /** 精灵图 sheet 解码上限（与键图／图标同值 512px 最长边）。 */
    private const val MAX_SHEET_SIDE_PX = 512

    private fun decodeSheetFile(file: File, skinId: String, assetId: String): Bitmap? {
        if (!file.isFile) {
            DiagLog.w(
                "toolbar", "toolbar_button_background_file_missing",
                mapOf("skinId" to skinId, "asset" to assetId),
            )
            return null
        }
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > MAX_SHEET_SIDE_PX ||
                bounds.outHeight / sample > MAX_SHEET_SIDE_PX
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.onFailure { error ->
            DiagLog.w(
                "toolbar", "toolbar_button_background_decode_failed",
                mapOf(
                    "skinId" to skinId,
                    "asset" to assetId,
                    "errorClass" to error.javaClass.simpleName,
                ),
            )
        }.getOrNull()
        return bitmap
    }
}
