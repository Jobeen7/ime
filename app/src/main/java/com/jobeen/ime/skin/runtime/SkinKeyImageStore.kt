package com.jobeen.ime.skin.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.image.ImageDecodePolicy

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
 *
 * 代次令牌（2026-11-05 审计 #1 项 3）：缓存键为（代次，资产 id），
 * resolve 开始时捕获代次与字节源、解码在锁外进行，完成后仅当
 * 代次仍相同才写回——切换后才完成的旧皮肤解码绝不污染新皮肤
 * 同名 assetId 的缓存；安装（代次更新＋缓存清理＋字节源注入）
 * 经 [installProvider] 在同一把锁下原子完成。旧位图不立即
 * recycle（仍可能被旧 KeyView 绘制）：退役后保留一个代次，
 * 再切换时回收（见 [retireCurrentLocked]）。
 */
object SkinKeyImageStore {

    /** 键面解码最长边上限（px）：键面小图不需要原图尺寸，超限降采样。 */
    const val MAX_SIDE_PX = 512

    @Volatile
    var bytesProvider: ((String) -> ByteArray?)? = null

    /** 缓存键：代次＋资产 id——同名 assetId 在不同皮肤代次下内容不同，绝不串味。 */
    private data class CacheKey(val generation: Long, val assetId: String)

    private val lock = Any()
    private var generation = 0L

    // 以下容器一律由 lock 守卫（HashMap/HashSet 非线程安全）：
    // cache 只存成功位图，resolved 记已解析过的键（含失败），
    // inFlight 记正在解码的键（在途合并），retired 记已退役、
    // 待延迟回收的位图（代次，位图）。
    private val cache = HashMap<CacheKey, Bitmap>()
    private val resolved = HashSet<CacheKey>()
    private val logged = HashSet<CacheKey>()
    private val inFlight = HashSet<CacheKey>()
    private val retired = ArrayDeque<Pair<Long, Bitmap>>()

    /** 测试缝：解码器（默认 [decode]）；生产不改。 */
    internal var decoder: (ByteArray) -> Bitmap? = ::decode

    /** 测试缝：位图回收（默认未回收才 recycle）；生产不改。 */
    internal var bitmapRecycler: (Bitmap) -> Unit = { bitmap ->
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    /** 当前代次（测试用）。 */
    internal val currentGeneration: Long
        get() = synchronized(lock) { generation }

    /**
     * 性能面板「皮肤图片缓存」行：当前代次缓存项数与估算字节
     * （位图 allocationByteCount 求和；退役滞留位图不在 cache 内，不计入）。
     */
    fun cacheStats(): Pair<Int, Long> = synchronized(lock) {
        var bytes = 0L
        for (bitmap in cache.values) {
            bytes += runCatching { bitmap.allocationByteCount.toLong() }.getOrDefault(0L)
        }
        cache.size to bytes
    }

    /**
     * 按资产 id 取位图（null = 无图/解码失败，调用方回退颜色键面）。
     * 第 5 批 §2：在途合并——同一键并发调用只解码一次（后来者等
     * 首个解码者完成后再重取，代次可能已变）；失败结果亦缓存，
     * 快照切换前不再重试（世代变更语义由代次承载）。
     * 解码在锁外进行：切换安装不被在途解码阻塞；过期结果不入缓存。
     */
    fun resolve(assetId: String): Bitmap? {
        while (true) {
            val key: CacheKey
            val provider: ((String) -> ByteArray?)?
            synchronized(lock) {
                key = CacheKey(generation, assetId)
                if (key in resolved) return cache[key]
                if (key in inFlight) {
                    // 等首个解码者完成（wait 释放锁，安装可并行），
                    // 再从头重取：代次／字节源可能已变。
                    try {
                        (lock as Object).wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    continue
                }
                inFlight += key
                provider = bytesProvider
            }
            val bitmap = try {
                val bytes = runCatching { provider?.invoke(assetId) }.getOrNull()
                if (bytes == null) {
                    null
                } else {
                    runCatching { decoder(bytes) }.getOrNull()
                }
            } finally {
                synchronized(lock) {
                    inFlight -= key
                    (lock as Object).notifyAll()
                }
            }
            synchronized(lock) {
                if (generation != key.generation) {
                    // 切换已发生：过期解码结果不入缓存、不记 resolved；
                    // 位图本身返回给调用方（旧 KeyView 仍可用它绘制，
                    // 预热调用方丢弃后由 GC 回收），绝不回收在用位图。
                    return bitmap
                }
                resolved += key
                if (bitmap != null) cache[key] = bitmap
                if (bitmap == null && logged.add(key)) {
                    DiagLog.w("keymap", "key_image_decode_fallback", mapOf("asset" to assetId))
                }
            }
            return bitmap
        }
    }

    /**
     * IO 预热（第 5 批 §2）：快照安装后在 IO 段把常用键图先解进
     * 缓存，使后续键盘构建期 resolve 命中缓存、不在 UI 线程解码。
     * 上限 [ImageDecodePolicy.PREWARM_MAX_KEYS] 条，超出者仍走
     * 首次构建解码（旧口径）；调用方必须在 IO 线程调本函数。
     * [shouldStop] 为真时提前停（安装方取消上一代次预热时用）。
     */
    fun prewarm(assetIds: Iterable<String>, shouldStop: () -> Boolean = { false }) {
        for (assetId in assetIds.take(ImageDecodePolicy.PREWARM_MAX_KEYS)) {
            if (shouldStop()) break
            runCatching { resolve(assetId) }
        }
    }

    /** 直接注入已解码位图（测试/预览用；生产路径走 bytesProvider）。 */
    fun put(assetId: String, bitmap: Bitmap?) {
        synchronized(lock) {
            val key = CacheKey(generation, assetId)
            resolved += key
            if (bitmap != null) {
                cache[key] = bitmap
            } else {
                cache.remove(key)
            }
        }
    }

    /**
     * 原子安装字节源（快照切换唯一入口）：当前代次缓存退役、
     * 代次推进、字节源注入在同一把锁下完成，中间不存在“已清
     * 缓存、未换字节源”的窗口——该窗口内的 resolve 会把新代次
     * 的失败结果误缓存下来。
     */
    fun installProvider(provider: ((String) -> ByteArray?)?) {
        synchronized(lock) {
            retireCurrentLocked()
            bytesProvider = provider
        }
    }

    /** 清空缓存与字节源（卸载／失效时用；同样推进代次，使在途解码过期）。 */
    fun clear() {
        synchronized(lock) {
            retireCurrentLocked()
            bytesProvider = null
        }
    }

    /**
     * 当前代次退役（须持 lock）：缓存位图改记退役（不立即回收，
     * 旧 KeyView 仍可能在绘制）；上上代次以前的退役位图此时回收
     * ——旧视图至多滞留一个代次。代次随后推进。
     */
    private fun retireCurrentLocked() {
        for ((key, bitmap) in cache) {
            retired += key.generation to bitmap
        }
        cache.clear()
        resolved.clear()
        logged.clear()
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val (gen, bitmap) = iterator.next()
            if (gen < generation) {
                iterator.remove()
                runCatching { bitmapRecycler(bitmap) }
            }
        }
        generation += 1
    }

    /**
     * 解码（两遍式降采样：先 bounds 算采样率，再解码；失败返回 null）。
     * 施工单 5 第 7 条：超限键图不再直接拒收——采样循环把边长
     * 压到 [MAX_SIDE_PX] 以内（512 边至多 262K 像素，恒在
     * 8192 边／64M 像素上限内），24MP 图也降采样可用；旧口径
     * （第 5 批 §2：bounds 像素超 24MP 硬上限直接拒）已废止。
     */
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
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
