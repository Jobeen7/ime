// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import android.graphics.Bitmap
import com.jobeen.ime.skin.image.ImageDecodePolicy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 键图缓存代次令牌（2026-11-05 审计 #1 项 3）：纯 JVM 单测。
 * Bitmap 无法在 JVM 单测里正常构造（android.jar 桩方法抛错），
 * 故解码器与回收器以测试缝注入：解码器只记录收到的字节（返回
 * null，走失败缓存路径）、回收器只记录身份；需要位图实例时用
 * Unsafe 绕过构造器分配（实例上不调任何 Bitmap 方法）。
 */
class SkinKeyImageStoreTest {

    private val decoded = Collections.synchronizedList(mutableListOf<ByteArray>())

    @Before
    fun setUp() {
        SkinKeyImageStore.clear()
        SkinKeyImageStore.decoder = { bytes ->
            decoded += bytes
            null
        }
        SkinKeyImageStore.bitmapRecycler = {}
    }

    @After
    fun tearDown() {
        SkinKeyImageStore.resolveTimeoutMs = SkinKeyImageStore.RESOLVE_TIMEOUT_MS
        SkinKeyImageStore.decoder = SkinKeyImageStore::decode
        SkinKeyImageStore.bitmapRecycler = { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        SkinKeyImageStore.bitmapByteSize = { bitmap ->
            runCatching { bitmap.allocationByteCount.toLong() }.getOrDefault(0L)
        }
        SkinKeyImageStore.clear()
    }

    private fun fakeBitmap(): Bitmap {
        val unsafeField = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    @Test
    fun staleResolveDoesNotPoisonNewSkin() {
        val bytesA = "A-content".toByteArray()
        val bytesB = "B-content".toByteArray()
        val providerEntered = CountDownLatch(1)
        val latch = CountDownLatch(1)
        SkinKeyImageStore.installProvider { _ ->
            providerEntered.countDown()
            latch.await(10, TimeUnit.SECONDS)
            bytesA
        }
        // A 的 resolve 在另一线程阻塞在字节源上。
        val resolveA = thread { SkinKeyImageStore.resolve("k") }
        assertTrue(providerEntered.await(10, TimeUnit.SECONDS))
        // 此时安装 B：不得被在途解码阻塞。
        val installB = thread { SkinKeyImageStore.installProvider { bytesB } }
        installB.join(10_000)
        assertFalse("install must not block on in-flight decode", installB.isAlive)
        latch.countDown()
        resolveA.join(10_000)
        assertFalse(resolveA.isAlive)
        // A 只解了自己的字节，且未入缓存：随后 resolve 必须解 B。
        assertEquals(1, decoded.count { it.contentEquals(bytesA) })
        SkinKeyImageStore.resolve("k")
        assertEquals(1, decoded.count { it.contentEquals(bytesB) })
        // 新代次失败结果已缓存：再解不调解码器。
        val n = decoded.size
        SkinKeyImageStore.resolve("k")
        assertEquals(n, decoded.size)
    }

    @Test
    fun sameAssetIdDifferentContentResolvedPerGeneration() {
        val bytesA = byteArrayOf(1)
        val bytesB = byteArrayOf(2)
        SkinKeyImageStore.installProvider { bytesA }
        SkinKeyImageStore.resolve("k")
        SkinKeyImageStore.installProvider { bytesB }
        SkinKeyImageStore.resolve("k")
        assertEquals(1, decoded.count { it.contentEquals(bytesA) })
        assertEquals(1, decoded.count { it.contentEquals(bytesB) })
        val n = decoded.size
        SkinKeyImageStore.resolve("k")
        assertEquals(n, decoded.size)
    }

    @Test
    fun concurrentResolveDecodesOnce() {
        val providerCalls = AtomicInteger(0)
        val decoding = CountDownLatch(1)
        val latch = CountDownLatch(1)
        SkinKeyImageStore.decoder = { bytes ->
            decoded += bytes
            decoding.countDown()
            latch.await(10, TimeUnit.SECONDS)
            null
        }
        SkinKeyImageStore.installProvider {
            providerCalls.incrementAndGet()
            "x".toByteArray()
        }
        val t1 = thread { SkinKeyImageStore.resolve("k") }
        assertTrue(decoding.await(10, TimeUnit.SECONDS))
        val t2 = thread { SkinKeyImageStore.resolve("k") }
        Thread.sleep(200)
        latch.countDown()
        t1.join(10_000)
        t2.join(10_000)
        assertFalse(t1.isAlive)
        assertFalse(t2.isAlive)
        assertEquals(1, providerCalls.get())
        assertEquals(1, decoded.size)
    }

    @Test
    fun oldBitmapSurvivesOneGenerationThenRecycled() {
        val recycled = Collections.synchronizedList(mutableListOf<Bitmap>())
        SkinKeyImageStore.bitmapRecycler = { recycled += it }
        val bmpA = fakeBitmap()
        SkinKeyImageStore.put("k", bmpA)
        assertSame(bmpA, SkinKeyImageStore.resolve("k"))
        // 切到 B：A 退役但不回收——旧 KeyView 仍可能在绘制它。
        SkinKeyImageStore.installProvider { null }
        assertTrue(recycled.isEmpty())
        // 新代次 resolve 不再返回旧位图。
        assertEquals(null, SkinKeyImageStore.resolve("k"))
        assertTrue(recycled.isEmpty())
        // 再切到 C：A 已过一个代次，此时回收。
        SkinKeyImageStore.installProvider { null }
        assertEquals(listOf(bmpA), recycled)
    }

    @Test
    fun prewarmStopsWhenCancelled() {
        SkinKeyImageStore.installProvider { assetId -> assetId.toByteArray() }
        var checks = 0
        SkinKeyImageStore.prewarm(listOf("a", "b", "c")) { ++checks > 2 }
        assertEquals(
            listOf("a", "b"),
            decoded.map { String(it) },
        )
    }

    /**
     * 施工单 6 第 5 条：200 个唯一资产，预热只覆盖前 128 条
     * （PREWARM_MAX_KEYS）；第 129 条起走 request 异步解码——
     * request 在调用线程立即返回（解码器睡 300ms 也拦不住），
     * 回调随后拿到位图并入缓存。
     */
    @Test
    fun prewarmCoversFirst128OnlyAndRestResolveAsyncWithoutBlocking() {
        val ids = (0 until 200).map { "asset-$it" }
        SkinKeyImageStore.decoder = { fakeBitmap() }
        SkinKeyImageStore.installProvider { assetId -> assetId.toByteArray() }
        SkinKeyImageStore.prewarm(ids)
        val (count, _) = SkinKeyImageStore.cacheStats()
        assertEquals(ImageDecodePolicy.PREWARM_MAX_KEYS, count)
        assertEquals(null, SkinKeyImageStore.peek("asset-150"))

        val decodeStarted = CountDownLatch(1)
        SkinKeyImageStore.decoder = {
            decodeStarted.countDown()
            Thread.sleep(300)
            fakeBitmap()
        }
        var delivered: Bitmap? = null
        val start = System.nanoTime()
        SkinKeyImageStore.request("asset-150") { delivered = it }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "request must return without decoding on the calling thread",
            elapsedMs < 100,
        )
        assertTrue(decodeStarted.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (delivered == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertNotNull(delivered)
        assertSame(delivered, SkinKeyImageStore.peek("asset-150"))
    }

    /**
     * 施工单 6 第 5 条：预热与键盘创建（request）并发——同一资产
     * 只解码一次、无死锁，全部回调都拿到同一位图。
     */
    @Test
    fun prewarmConcurrentWithRequestsDecodesEachAssetOnce() {
        val ids = (0 until 20).map { "asset-$it" }
        val decodeCounts = ConcurrentHashMap<String, AtomicInteger>()
        SkinKeyImageStore.decoder = { bytes ->
            val id = String(bytes)
            decodeCounts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
            Thread.sleep(20)
            fakeBitmap()
        }
        SkinKeyImageStore.installProvider { assetId -> assetId.toByteArray() }
        val done = CountDownLatch(ids.size)
        val results = ConcurrentHashMap<String, Bitmap?>()
        val prewarmThread = thread { SkinKeyImageStore.prewarm(ids) }
        val requestThreads = ids.map { id ->
            thread {
                SkinKeyImageStore.request(id) { bitmap ->
                    results[id] = bitmap
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        prewarmThread.join(10_000)
        requestThreads.forEach { it.join(10_000) }
        assertEquals(ids.size, results.size)
        assertTrue(results.values.all { it != null })
        for (id in ids) {
            assertEquals("asset $id decoded once", 1, decodeCounts[id]?.get())
        }
    }

    /**
     * 施工单 6 第 5 条：迟到结果不污染新代次。旧代次解码完成时
     * 新皮肤已安装——调用方仍得旧位图（旧口径），但缓存里绝无
     * 旧内容（peek 仍是新代次的位图）。
     */
    @Test
    fun lateResultFromOldGenerationIsNotCached() {
        val bytesA = "A-content".toByteArray()
        val bmpB = fakeBitmap()
        val providerEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        SkinKeyImageStore.installProvider { _ ->
            providerEntered.countDown()
            releaseA.await(10, TimeUnit.SECONDS)
            bytesA
        }
        SkinKeyImageStore.decoder = { fakeBitmap() }
        var first: Bitmap? = null
        val resolveA = thread { first = SkinKeyImageStore.resolve("k") }
        assertTrue(providerEntered.await(10, TimeUnit.SECONDS))
        // 切到 B（位图由 put 直接注入，避免再走解码）。
        SkinKeyImageStore.installProvider { null }
        SkinKeyImageStore.put("k", bmpB)
        releaseA.countDown()
        resolveA.join(10_000)
        assertFalse(resolveA.isAlive)
        assertNotNull(first)
        assertFalse("late result must not overwrite new generation", first === bmpB)
        assertSame(bmpB, SkinKeyImageStore.peek("k"))
    }

    /**
     * 施工单 6 第 5 条：同一资产 5 路并发 request 只解码一次，
     * 全部回调拿到同一位图实例。
     */
    @Test
    fun requestMergesConcurrentCallersIntoOneDecode() {
        val decodeCalls = AtomicInteger(0)
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        SkinKeyImageStore.decoder = {
            decodeCalls.incrementAndGet()
            decoding.countDown()
            release.await(10, TimeUnit.SECONDS)
            fakeBitmap()
        }
        SkinKeyImageStore.installProvider { "x".toByteArray() }
        val done = CountDownLatch(5)
        val results = Collections.synchronizedList(mutableListOf<Bitmap?>())
        val threads = (1..5).map {
            thread {
                SkinKeyImageStore.request("k") { bitmap ->
                    results += bitmap
                    done.countDown()
                }
            }
        }
        assertTrue(decoding.await(10, TimeUnit.SECONDS))
        Thread.sleep(200)
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        threads.forEach { it.join(10_000) }
        assertEquals(1, decodeCalls.get())
        assertEquals(5, results.size)
        assertTrue(results.all { it != null })
        assertTrue(results.all { it === results[0] })
    }

    /**
     * 施工单 6 第 5 条：已缓存资产 request 在调用线程立即回调，
     * 不走解码器。
     */
    @Test
    fun requestDeliversCachedImmediatelyWithoutDecode() {
        val bmp = fakeBitmap()
        SkinKeyImageStore.put("k", bmp)
        val decodeCalls = AtomicInteger(0)
        SkinKeyImageStore.decoder = {
            decodeCalls.incrementAndGet()
            fakeBitmap()
        }
        var delivered: Bitmap? = null
        var callbackThread: Thread? = null
        SkinKeyImageStore.request("k") {
            delivered = it
            callbackThread = Thread.currentThread()
        }
        assertSame(bmp, delivered)
        assertSame(Thread.currentThread(), callbackThread)
        assertEquals(0, decodeCalls.get())
    }

    /**
     * 施工单 6 第 5 条：总预算 128MB，每张 40MB，4 张共 160MB
     * → 最旧一张按 FIFO 退役，其余三张仍在缓存；单张超预算
     * 不缓存（调用方仍拿到本次位图）。
     */
    @Test
    fun cacheBudgetEvictsOldestFirst() {
        SkinKeyImageStore.bitmapByteSize = { 40L * 1024 * 1024 }
        SkinKeyImageStore.decoder = { fakeBitmap() }
        SkinKeyImageStore.installProvider { assetId -> assetId.toByteArray() }
        for (id in listOf("a", "b", "c", "d")) {
            SkinKeyImageStore.resolve(id)
        }
        val (count, bytes) = SkinKeyImageStore.cacheStats()
        assertEquals(3, count)
        assertTrue(bytes <= ImageDecodePolicy.MAX_CACHE_BYTES)
        assertEquals(null, SkinKeyImageStore.peek("a"))
        assertNotNull(SkinKeyImageStore.peek("b"))
        assertNotNull(SkinKeyImageStore.peek("c"))
        assertNotNull(SkinKeyImageStore.peek("d"))
    }

    @Test
    fun singleBitmapOverBudgetIsReturnedButNotCached() {
        SkinKeyImageStore.bitmapByteSize = { 200L * 1024 * 1024 }
        val bmp = fakeBitmap()
        SkinKeyImageStore.decoder = { bmp }
        SkinKeyImageStore.installProvider { "x".toByteArray() }
        assertSame(bmp, SkinKeyImageStore.resolve("k"))
        assertEquals(null, SkinKeyImageStore.peek("k"))
        assertEquals(0 to 0L, SkinKeyImageStore.cacheStats())
    }

    // ---- 2026-11-12 审计 K-M5：同步 resolve 有界等待 ----

    @Test
    fun resolveTimeoutReturnsNullWhenDecodeStalls() {
        val release = CountDownLatch(1)
        SkinKeyImageStore.resolveTimeoutMs = 200L
        SkinKeyImageStore.decoder = { bytes ->
            decoded += bytes
            // 解码通道停滞（字节源／解码器挂住）：resolve 不得无限等。
            release.await(10, TimeUnit.SECONDS)
            null
        }
        SkinKeyImageStore.installProvider { "x".toByteArray() }
        try {
            val startMs = System.currentTimeMillis()
            assertNull(SkinKeyImageStore.resolve("k"))
            val elapsedMs = System.currentTimeMillis() - startMs
            assertTrue("resolve must time out, took ${elapsedMs}ms", elapsedMs < 4_000L)
        } finally {
            release.countDown()
        }
    }

    // ---- 2026-11-12 审计 K-L2：失败集由 DecodeGate 承载 ----

    @Test
    fun failedKeyNotRetriedUntilGenerationChanges() {
        SkinKeyImageStore.installProvider { "x".toByteArray() }
        assertNull(SkinKeyImageStore.resolve("k"))
        assertEquals(1, decoded.size)
        // 同世代内失败不再重试（失败已缓存／进失败集）。
        assertNull(SkinKeyImageStore.resolve("k"))
        assertEquals(1, decoded.size)
        // 世代变更（安装新快照）后重获一次重试机会。
        SkinKeyImageStore.installProvider { "x".toByteArray() }
        assertNull(SkinKeyImageStore.resolve("k"))
        assertEquals(2, decoded.size)
    }
}
