package com.jobeen.ime.skin.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.image.DecodeGate
import com.jobeen.ime.skin.image.ImageDecodePolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
 *
 * 在途合并与失败集（2026-11-12 审计 K-L2）：本 Store 原先自带
 * 一套 inFlight／resolved 实现，与 [DecodeGate] 重复——现以
 * DecodeGate 为唯一在途／失败门：同一键在途只解码一次，失败后
 * 不再重试直到世代变更（安装／清空时 nextGeneration）。
 *
 * 异步解码（施工单 6 第 5 条，.23 审计问题 6）：KeyView 构造
 * 路径只读缓存 [peek]、先渲染文字／颜色占位，未命中走
 * [request] 异步解码、完成后只刷新仍属当前代次的键——构造
 * 期绝不在调用线程解码。全部键图字节读＋解码（同步 [resolve]、
 * 异步 [request]、[prewarm]）共用同一有界通道
 * [decodeDispatcher]（`Dispatchers.IO.limitedParallelism(2)`，
 * [ImageDecodePolicy.DECODE_PARALLELISM] 落地于此，不再只是
 * 常量）；缓存总字节受 [ImageDecodePolicy.MAX_CACHE_BYTES]
 * 约束，超限按 FIFO 退役最旧条目（退役口径与切换一致，滞留
 * 一代再回收）。同步 [resolve] 不得在解码通道线程上调用
 * （调用线程等解码任务落槽——解码任务本身不等待调用方，无
 * 死锁；但通道线程上调会白占一个槽位）。
 */
object SkinKeyImageStore {

    /** 键面解码最长边上限（px）：键面小图不需要原图尺寸，超限降采样。 */
    const val MAX_SIDE_PX = 512

    /**
     * 同步 [resolve] 的阻塞等待上限（2026-11-12 审计 K-M5）：
     * 旧实现 latch.await() 无超时、无取消，解码通道一旦停滞即
     * 长期占用 IO 线程、预热永不完成。超时后返回已得结果
     * （未解出为 null，调用方回退颜色键面）；迟到的解码完成时
     * 回调仍投递、但不入缓存（代次守卫不变）。
     */
    const val RESOLVE_TIMEOUT_MS = 5_000L

    /** 有界解码通道：全部键图字节读＋解码共用，至多 2 路并发。 */
    private val decodeDispatcher =
        Dispatchers.IO.limitedParallelism(ImageDecodePolicy.DECODE_PARALLELISM)
    private val decodeScope = CoroutineScope(SupervisorJob() + decodeDispatcher)

    @Volatile
    var bytesProvider: ((String) -> ByteArray?)? = null

    /** 缓存键：代次＋资产 id——同名 assetId 在不同皮肤代次下内容不同，绝不串味。 */
    private data class CacheKey(val generation: Long, val assetId: String)

    private val lock = Any()
    private var generation = 0L

    // 以下容器一律由 lock 守卫（HashMap/HashSet 非线程安全）：
    // cache 只存成功位图，resolved 记已解析过的键（含失败），
    // 在途／失败由 decodeGate 守卫，retired 记已退役、待延迟回收
    // 的位图（代次，位图）。
    private val cache = HashMap<CacheKey, Bitmap>()
    private val resolved = HashSet<CacheKey>()
    private val logged = HashSet<CacheKey>()
    /** 在途合并＋失败集（K-L2：与本 Store 原先的 inFlight 手写实现合一）。 */
    private val decodeGate = DecodeGate()
    private val retired = ArrayDeque<Pair<Long, Bitmap>>()
    /** 异步等待回调：键 → 回调表（同一键多处并发只解码一次，回调逐个投递）。 */
    private val waiters = HashMap<CacheKey, MutableList<(Bitmap?) -> Unit>>()
    /** 缓存总字节记账（与 cache／cacheOrder 同步维护，见 [cacheWithBudget]）。 */
    private var cachedBytes = 0L
    private val cacheOrder = ArrayDeque<CacheKey>()

    /** 测试缝：解码器（默认 [decode]）；生产不改。 */
    internal var decoder: (ByteArray) -> Bitmap? = ::decode

    /** 测试缝：位图回收（默认未回收才 recycle）；生产不改。 */
    internal var bitmapRecycler: (Bitmap) -> Unit = { bitmap ->
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    /** 测试缝：位图字节数（默认 allocationByteCount；桩上取不到时按 0 计）。 */
    internal var bitmapByteSize: (Bitmap) -> Long = { bitmap ->
        runCatching { bitmap.allocationByteCount.toLong() }.getOrDefault(0L)
    }

    /** 测试缝：同步 resolve 的等待上限（默认 [RESOLVE_TIMEOUT_MS]；单测可缩短）。 */
    internal var resolveTimeoutMs: Long = RESOLVE_TIMEOUT_MS

    /** 当前代次（测试用；KeyView 构造期捕获，异步回填时核对）。 */
    internal val currentGeneration: Long
        get() = synchronized(lock) { generation }

    /**
     * 性能面板「皮肤图片缓存」行：当前代次缓存项数与估算字节
     * （位图 allocationByteCount 求和；退役滞留位图不在 cache 内，不计入）。
     */
    fun cacheStats(): Pair<Int, Long> = synchronized(lock) {
        var bytes = 0L
        for (bitmap in cache.values) {
            bytes += bitmapByteSize(bitmap)
        }
        cache.size to bytes
    }

    /**
     * 只读缓存窥视（KeyView 构造路径用）：命中返回位图，未命中
     * （含失败已缓存）返回 null；绝不解码、绝不等待——构造期
     * 在调用线程解码的旧口径已废止。
     */
    fun peek(assetId: String): Bitmap? = synchronized(lock) {
        cache[CacheKey(generation, assetId)]
    }

    /**
     * 异步解码请求（KeyView 构造路径用）：已解析（含失败）时在
     * 调用线程立即回调；未解析时在有界解码通道上解码一次，完
     * 成后回调——同一键并发只解码一次（后来者登记为等待回调）。
     * 回调线程：已解析时为调用线程，未解析时为解码通道线程；
     * 调用方触视图前须自行切主线程。代次已变（请求发出后切换
     * 皮肤）时回调仍得解出的位图（与 [resolve] 同口径），但绝
     * 不入缓存；KeyView 侧另有代次门，迟到结果不刷新旧键。
     */
    fun request(assetId: String, onDecoded: (Bitmap?) -> Unit) {
        val (key, provider, launchDecode, gateGeneration) = synchronized(lock) {
            val k = CacheKey(generation, assetId)
            if (k in resolved) {
                return onDecoded(cache[k])
            }
            // DecodeGate 裁决：已失败（本世代内）直接回落、不再发起
            // 解码；已在途则只登记等待回调、不重复解码。
            val gen = decodeGate.begin(k.assetId)
            if (gen == null) {
                if (decodeGate.isFailed(k.assetId)) return onDecoded(cache[k])
                waiters.getOrPut(k) { mutableListOf() } += onDecoded
                DecodeDecision(k, bytesProvider, false, null)
            } else {
                waiters.getOrPut(k) { mutableListOf() } += onDecoded
                DecodeDecision(k, bytesProvider, true, gen)
            }
        }
        if (launchDecode) {
            decodeScope.launch {
                completeDecode(key, decodeNow(key, provider), gateGeneration)
            }
        }
    }

    /** 解码发起裁决（锁内一次判定：键＋字节源＋是否发起＋门世代）。 */
    private data class DecodeDecision(
        val key: CacheKey,
        val provider: ((String) -> ByteArray?)?,
        val launchDecode: Boolean,
        val gateGeneration: Long?,
    )

    /**
     * 按资产 id 取位图（null = 无图/解码失败，调用方回退颜色键面）。
     * 第 5 批 §2：在途合并——同一键并发调用只解码一次（后来者等
     * 首个解码者完成后再重取，代次可能已变）；失败结果亦缓存，
     * 快照切换前不再重试（世代变更语义由代次承载）。
     * 解码在有界通道上进行（施工单 6 第 5 条），调用线程只等待
     * 结果；生产调用方只剩 IO 线程的 [prewarm]，主线程调用方
     * 一律走 [peek]＋[request]。
     * 代次口径：请求发出后切换皮肤的，解出的位图仍返回给调用
     * 方（旧 KeyView 仍可用它绘制，预热调用方丢弃后由 GC 回收），
     * 但绝不入缓存——迟到结果不污染新代次（审计 #1 项 3 已核实
     * 口径，施工单 6 第 5 条不改）。
     */
    fun resolve(assetId: String): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        val (key, provider, launchDecode, gateGeneration) = synchronized(lock) {
            val k = CacheKey(generation, assetId)
            if (k in resolved) return cache[k]
            // DecodeGate 裁决（同 request）：已失败直接回落；在途只等
            // 首个解码者，不重复解码。
            val gen = decodeGate.begin(k.assetId)
            if (gen == null) {
                if (decodeGate.isFailed(k.assetId)) return cache[k]
                waiters.getOrPut(k) { mutableListOf() } += { bitmap ->
                    result = bitmap
                    latch.countDown()
                }
                DecodeDecision(k, bytesProvider, false, null)
            } else {
                waiters.getOrPut(k) { mutableListOf() } += { bitmap ->
                    result = bitmap
                    latch.countDown()
                }
                DecodeDecision(k, bytesProvider, true, gen)
            }
        }
        if (launchDecode) {
            decodeScope.launch {
                completeDecode(key, decodeNow(key, provider), gateGeneration)
            }
        }
        // 有界等待（K-M5）：解码通道停滞时最多等 RESOLVE_TIMEOUT_MS，
        // 超时按解码失败回落（返回 null），不长期占用调用线程；
        // 迟到的解码完成时回调仍投递（result 已无人读，无害）。
        if (!latch.await(resolveTimeoutMs, TimeUnit.MILLISECONDS)) {
            DiagLog.w(
                "keymap", "key_image_resolve_timeout",
                mapOf("asset" to assetId),
            )
        }
        return result
    }

    /** 锁外解码一段（字节读＋解码；调用方不得在锁内调本函数）。 */
    private fun decodeNow(key: CacheKey, provider: ((String) -> ByteArray?)?): Bitmap? {
        val bytes = runCatching { provider?.invoke(key.assetId) }.getOrNull() ?: return null
        return runCatching { decoder(bytes) }.getOrNull()
    }

    /**
     * 解码完成收尾（解码协程调，唯一写回点）：在途标记清掉、
     * 代次仍相同才写缓存＋记 resolved（失败亦记，避免重解）、
     * 投递等待回调。回调在锁外投递（回调内可能再调本 Store）。
     * 代次已变：不写缓存、不记 resolved，但等待回调仍得解出
     * 的位图（与旧口径一致，调用方自行决定用不用；KeyView 侧
     * 有代次门丢弃迟到结果）。
     */
    private fun completeDecode(key: CacheKey, bitmap: Bitmap?, gateGeneration: Long? = null) {
        val callbacks: List<(Bitmap?) -> Unit>
        synchronized(lock) {
            // 门世代归还：成功／失败由 begin 时的世代裁决——安装已
            // 推进世代时，旧世代的失败不记入新世代失败集。
            if (gateGeneration != null) {
                decodeGate.finish(key.assetId, bitmap != null, gateGeneration)
            }
            if (generation == key.generation) {
                resolved += key
                if (bitmap != null) cacheWithBudget(key, bitmap)
                if (bitmap == null && logged.add(key)) {
                    DiagLog.w("keymap", "key_image_decode_fallback", mapOf("asset" to key.assetId))
                }
            }
            callbacks = waiters.remove(key) ?: emptyList()
        }
        for (callback in callbacks) runCatching { callback(bitmap) }
    }

    /**
     * 预算内写缓存（须持 lock）：单张超预算不缓存（调用方仍可
     * 绘制本次结果）；超总额时按 FIFO 退役最旧条目直至容下——
     * 退役位图进 retired 队列（滞留一代再回收，与切换口径一致，
     * 在用视图不受影响）。同一键重写时先扣旧账。
     */
    private fun cacheWithBudget(key: CacheKey, bitmap: Bitmap) {
        val size = bitmapByteSize(bitmap)
        if (size > ImageDecodePolicy.MAX_CACHE_BYTES) return
        val old = cache[key]
        if (old != null) {
            cachedBytes -= bitmapByteSize(old)
            cacheOrder.remove(key)
        }
        while (cachedBytes + size > ImageDecodePolicy.MAX_CACHE_BYTES &&
            cacheOrder.isNotEmpty()
        ) {
            val oldest = cacheOrder.removeFirst()
            val evicted = cache.remove(oldest)
            if (evicted != null) {
                cachedBytes -= bitmapByteSize(evicted)
                retired += oldest.generation to evicted
            }
        }
        cache[key] = bitmap
        cacheOrder += key
        cachedBytes += size
    }

    /**
     * IO 预热（第 5 批 §2）：快照安装后在 IO 段把常用键图先解进
     * 缓存，使后续键盘构建期 peek 命中缓存、不在 UI 线程解码。
     * 上限 [ImageDecodePolicy.PREWARM_MAX_KEYS] 条，超出者仍走
     * 键盘构建期异步解码（施工单 6 第 5 条：peek 未命中＋request
     * 回填，构造不阻塞）；调用方必须在 IO 线程调本函数。
     * [shouldStop] 为真时提前停（安装方取消上一代次预热时用）。
     * 解码走有界通道（见 [resolve]），与构建期异步请求共用
     * 2 路并行预算。
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
                cacheWithBudget(key, bitmap)
            } else {
                val old = cache.remove(key)
                if (old != null) {
                    cachedBytes -= bitmapByteSize(old)
                    cacheOrder.remove(key)
                }
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
            decodeGate.nextGeneration()
            bytesProvider = provider
        }
    }

    /** 清空缓存与字节源（卸载／失效时用；同样推进代次，使在途解码过期）。 */
    fun clear() {
        synchronized(lock) {
            retireCurrentLocked()
            decodeGate.nextGeneration()
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
        cacheOrder.clear()
        cachedBytes = 0L
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
