package com.jobeen.ime.skin.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.image.ImageDecodePolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * 单键图片键面位图解析（G1）：
 *
 * - 快照只记资产 id 与 sha（位图本体不入快照，定稿一.4）；
 *   键盘构建期（冷路径）KeyView 按 id 向本 Store 取位图。
 * - 字节来源由 [SkinSnapshotProvider] 在快照安装时注入
 *   （内容寻址资产文件）；未注入/文件缺失/解码失败一律返回
 *   null，渲染回退颜色键面并记一条 WARN（只记资产 id，不记内容）。
 * - 缓存上限：失败结果亦缓存（避免每次重建重复解码失败），
 *   快照切换/卸载时由 provider 清空。
 */
object SkinKeyImageStore {

    /** 键面解码最长边上限（px）：键面小图不需要原图尺寸，超限降采样。 */
    const val MAX_SIDE_PX = 512

    @Volatile
    var bytesProvider: ((String) -> ByteArray?)? = null

    // ConcurrentHashMap 不接受 null 值：命中与否分两个容器记
    // （cache 只存成功位图，resolved 记已解析过的 id 含失败）。
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val resolved = ConcurrentHashMap.newKeySet<String>()
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /**
     * 按资产 id 取位图（null = 无图/解码失败，调用方回退颜色键面）。
     * 第 5 批 §2：在途合并——整个解析加锁，同一 key 并发调用只
     * 解码一次（后来者直接取缓存／失败记录）；失败结果亦缓存，
     * 快照切换（clear）前不再重试（世代变更语义由 clear 承载）。
     */
    @Synchronized
    fun resolve(assetId: String): Bitmap? {
        if (assetId in resolved) return cache[assetId]
        val bytes = runCatching { bytesProvider?.invoke(assetId) }.getOrNull()
        val bitmap = if (bytes == null) {
            null
        } else {
            runCatching { decode(bytes) }.getOrNull()
        }
        resolved += assetId
        if (bitmap != null) cache[assetId] = bitmap
        if (bitmap == null && logged.add(assetId)) {
            DiagLog.w("keymap", "key_image_decode_fallback", mapOf("asset" to assetId))
        }
        return bitmap
    }

    /**
     * IO 预热（第 5 批 §2）：快照安装后在 IO 段把常用键图先解进
     * 缓存，使后续键盘构建期 resolve 命中缓存、不在 UI 线程解码。
     * 上限 [ImageDecodePolicy.PREWARM_MAX_KEYS] 条，超出者仍走
     * 首次构建解码（旧口径）；调用方必须在 IO 线程调本函数。
     */
    fun prewarm(assetIds: Iterable<String>) {
        for (assetId in assetIds.take(ImageDecodePolicy.PREWARM_MAX_KEYS)) {
            runCatching { resolve(assetId) }
        }
    }

    /** 直接注入已解码位图（测试/预览用；生产路径走 bytesProvider）。 */
    fun put(assetId: String, bitmap: Bitmap?) {
        resolved += assetId
        if (bitmap != null) {
            cache[assetId] = bitmap
        } else {
            cache.remove(assetId)
        }
    }

    fun clear() {
        cache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        cache.clear()
        resolved.clear()
        logged.clear()
        bytesProvider = null
    }

    /**
     * 解码（两遍式降采样：先 bounds 算采样率，再解码；失败返回 null）。
     * 第 5 批 §2：bounds 像素超 24MP 硬上限直接拒（与
     * BoundedBitmapDecoder 同口径，不另造解码器）。
     */
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (ImageDecodePolicy.exceedsPixelBudget(bounds.outWidth, bounds.outHeight)) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE_PX ||
            bounds.outHeight / sample > MAX_SIDE_PX
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
