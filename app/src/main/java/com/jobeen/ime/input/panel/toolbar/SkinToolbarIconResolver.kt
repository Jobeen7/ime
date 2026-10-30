package com.jobeen.ime.input.panel.toolbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SkinToolbarIconRef
import com.jobeen.ime.skin.model.SkinToolbarSnapshot
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.File

/**
 * 皮肤工具栏图标解码（渲染构造期冷路径，非绘制热路径）。
 *
 * 输入是快照中的资产引用（id + sha256 + 扩展名），按内容寻址
 * 从皮肤库取实体文件解码。任一失败路径只记元数据日志（操作 +
 * 阶段 + 皮肤 id + 异常类名），绝不抛异常、绝不记图标内容：
 * - 文件缺失 → WARN `toolbar_icon_file_missing`，该钮回退内置图标；
 * - 解码失败/异常 → WARN `toolbar_icon_decode_failed`（含异常类名），
 *   该钮回退内置图标。
 * WARN 级经 DiagLog 镜像进 Timber → AppLogBuffer，导出诊断日志
 * zip 的 diagnostic.txt 可见（2026-10-27 错误日志硬要求）。
 */
object SkinToolbarIconResolver {

    /** 快照中全部去重后的图标引用（中央区 + 左右边缘）。 */
    fun collectIconRefs(snapshot: SkinToolbarSnapshot): List<SkinToolbarIconRef> {
        val refs = LinkedHashMap<String, SkinToolbarIconRef>()
        snapshot.buttons?.forEach { button ->
            button.icon?.let { refs.putIfAbsent(it.assetId, it) }
        }
        snapshot.expandIcon?.let { refs.putIfAbsent(it.assetId, it) }
        snapshot.collapseIcon?.let { refs.putIfAbsent(it.assetId, it) }
        return refs.values.toList()
    }

    /**
     * 解码全部图标，返回资产 id → Drawable（只含成功项；失败项
     * 不在表内，调用方按“查无 = 回退内置图标”处理）。
     * 整体包 runCatching：库目录异常也不阻断工具栏构造。
     */
    fun resolve(
        context: Context,
        skinId: String,
        snapshot: SkinToolbarSnapshot,
    ): Map<String, Drawable> {
        val result = LinkedHashMap<String, Drawable>()
        runCatching {
            for (ref in collectIconRefs(snapshot)) {
                val drawable = decodeRef(context, skinId, ref) ?: continue
                result[ref.assetId] = drawable
            }
        }.onFailure { error ->
            DiagLog.e(
                "toolbar", "toolbar_icon_resolve_failed",
                mapOf(
                    "skinId" to skinId,
                    "errorClass" to error.javaClass.simpleName,
                ),
                throwable = error,
            )
        }
        return result
    }

    private fun decodeRef(
        context: Context,
        skinId: String,
        ref: SkinToolbarIconRef,
    ): Drawable? {
        val file = SkinLibrary.layout.assetFile(ref.sha256, ref.ext.ifEmpty { null })
        if (!file.isFile) {
            DiagLog.w(
                "toolbar", "toolbar_icon_file_missing",
                mapOf("skinId" to skinId, "assetId" to ref.assetId),
            )
            return null
        }
        val bitmap = decodeIconFile(file, skinId, ref.assetId) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    /** 单文件解码（失败返回 null 并记日志；BitmapFactory 抛异常亦然）。 */
    internal fun decodeIconFile(file: File, skinId: String, assetId: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath) ?: run {
                DiagLog.w(
                    "toolbar", "toolbar_icon_decode_failed",
                    mapOf(
                        "skinId" to skinId, "assetId" to assetId,
                        "errorClass" to "null_bitmap",
                    ),
                )
                null
            }
        } catch (error: Exception) {
            DiagLog.w(
                "toolbar", "toolbar_icon_decode_failed",
                mapOf(
                    "skinId" to skinId, "assetId" to assetId,
                    "errorClass" to error.javaClass.simpleName,
                ),
                throwable = error,
            )
            null
        }
    }
}
