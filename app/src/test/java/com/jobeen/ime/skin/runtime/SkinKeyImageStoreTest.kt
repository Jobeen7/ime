// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import android.graphics.Bitmap
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        SkinKeyImageStore.decoder = SkinKeyImageStore::decode
        SkinKeyImageStore.bitmapRecycler = { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
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
}
