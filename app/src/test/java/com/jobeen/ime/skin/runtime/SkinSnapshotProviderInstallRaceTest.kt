package com.jobeen.ime.skin.runtime

import android.content.Context
import android.graphics.Bitmap
import com.jobeen.ime.data.keyboard.theme.BackgroundBlurMode
import com.jobeen.ime.data.keyboard.theme.BackgroundBlurOverride
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.BackgroundSnapshot
import com.jobeen.ime.skin.model.ImageContentMode
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.store.SkinGenRef
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 快照安装竞态（.23 审计问题 2）：安装请求在开始准备时分配
 * 单调递增的 request token，锁内提交只认最新 token；启动加载
 * 的代次比对也在 install 内原子完成。
 *
 * 测的是 [SkinSnapshotProvider.install] 的提交裁决（经
 * backgroundDecoder／bitmapRecycler／blurOverrideProvider／
 * scaleModeProvider 四个测试缝注入可控解码与回收观测）：旧请求在解码期被新请求超
 * 越时，旧请求的位图必须回收、不得覆盖新快照；启动加载在解
 * 码期被用户应用超越时同样被拒；陈旧的 blur 重建不得盖掉同
 * 皮肤重装带入的新背景；背景解码并发有上限（2 路）。
 * Bitmap 用 Unsafe 绕过构造器分配（实例上不调任何 Bitmap 方法，
 * 回收走 bitmapRecycler 缝记录，不碰 android.jar 桩方法）。
 */
class SkinSnapshotProviderInstallRaceTest {

    private fun scheme() = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22,
            toolbarIcon = 23, candidateBackground = 24, candidateText = 25,
            candidateIndex = 26, candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
    )

    private fun background(preparedKey: String, blurRadius: Float = 0f) = BackgroundSnapshot(
        assetId = "bg",
        assetSha256 = "a".repeat(64),
        blurRadius = blurRadius,
        overlayAlpha = 0f,
        preparedKey = preparedKey,
        targetWidthPx = 100,
        targetHeightPx = 100,
        specBlurRadius = 0f,
    )

    private fun snapshot(skinId: String, preparedKey: String? = null) = SkinSnapshot(
        ref = SkinGenRef(skinId, "h".repeat(64), 1),
        name = "test-$skinId",
        colorScheme = scheme(),
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = preparedKey?.let { background(it) },
        degradations = emptySet(),
        layoutId = null,
        soundBindings = SoundBindings.EMPTY,
    )

    private fun fakeBitmap(): Bitmap {
        val unsafeField = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    /**
     * 桩 Context：[android.app.Application] 构造器不触框架方法，
     * 仅 getApplicationContext 是桩方法（installLocked 在预热前
     * 取一次）——子类覆写为返回自身，绕过桩抛错；其余 Context
     * 方法测试路径均不调（背景解码／模糊覆盖走测试缝，音效／
     * 字体／键图在空快照下是纯操作，内存采样在 runCatching 内）。
     */
    private class TestApplication : android.app.Application() {
        override fun getApplicationContext(): Context = this
    }

    private val context: Context = TestApplication()

    private lateinit var savedDeps: SkinSnapshotProvider.StartupLoadDeps
    private lateinit var savedDecoder: (Context, SkinSnapshot) -> Bitmap?
    private lateinit var savedRecycler: (Bitmap) -> Unit
    private lateinit var savedBlurOverride: (Context) -> BackgroundBlurOverride
    private lateinit var savedScaleMode: (Context) -> ImageContentMode
    private val recycled = Collections.synchronizedList(mutableListOf<Bitmap>())

    @Before
    fun setUp() {
        savedDeps = SkinSnapshotProvider.startupLoadDeps
        savedDecoder = SkinSnapshotProvider.backgroundDecoder
        savedRecycler = SkinSnapshotProvider.bitmapRecycler
        savedBlurOverride = SkinSnapshotProvider.blurOverrideProvider
        savedScaleMode = SkinSnapshotProvider.scaleModeProvider
        recycled.clear()
        SkinSnapshotProvider.bitmapRecycler = { recycled += it }
        SkinSnapshotProvider.invalidate()
    }

    @After
    fun tearDown() {
        // 先失效（测试缝仍在位：假位图的回收走记录缝，不碰桩方法），
        // 再把缝恢复默认；顺序反了会在默认缝里调假位图的 isRecycled。
        SkinSnapshotProvider.invalidate()
        SkinSnapshotProvider.startupLoadDeps = savedDeps
        SkinSnapshotProvider.backgroundDecoder = savedDecoder
        SkinSnapshotProvider.bitmapRecycler = savedRecycler
        SkinSnapshotProvider.blurOverrideProvider = savedBlurOverride
        SkinSnapshotProvider.scaleModeProvider = savedScaleMode
    }

    @Test
    fun staleInstallLosesToNewerInstallAndRecyclesBitmap() {
        // 旧 A 在解码期等待，新 B 先完成并提交，然后放行 A：
        // 当前仍为 B，A 的位图已回收，键图缓存代次不串。
        val snapA = snapshot("skin-a", "key-a")
        val snapB = snapshot("skin-b", "key-b")
        val bmpA = fakeBitmap()
        val bmpB = fakeBitmap()
        val decodeEnteredA = CountDownLatch(1)
        val decodeReleaseA = CountDownLatch(1)
        SkinSnapshotProvider.backgroundDecoder = { _, snap ->
            if (snap.ref.skinId == "skin-a") {
                decodeEnteredA.countDown()
                assertTrue(decodeReleaseA.await(10, TimeUnit.SECONDS))
                bmpA
            } else {
                bmpB
            }
        }
        val genBefore = SkinKeyImageStore.currentGeneration
        val tokenA = SkinSnapshotProvider.allocateInstallToken()
        var acceptedA: Boolean? = null
        val installA = thread {
            acceptedA = SkinSnapshotProvider.install(context, snapA, tokenA)
        }
        assertTrue(decodeEnteredA.await(10, TimeUnit.SECONDS))
        val genAfterB: Long
        assertTrue(SkinSnapshotProvider.install(context, snapB, SkinSnapshotProvider.allocateInstallToken()))
        assertEquals("skin-b", SkinSnapshotProvider.peek()?.ref?.skinId)
        assertEquals(bmpB, SkinSnapshotProvider.currentBackgroundBitmap())
        genAfterB = SkinKeyImageStore.currentGeneration
        assertTrue("B 的一次安装应恰好推进一格键图代次", genAfterB == genBefore + 1)
        decodeReleaseA.countDown()
        installA.join(10_000)
        assertFalse("A 的安装线程应在放行后退出", installA.isAlive)
        assertEquals(false, acceptedA)
        assertEquals("skin-b", SkinSnapshotProvider.peek()?.ref?.skinId)
        assertEquals(bmpB, SkinSnapshotProvider.currentBackgroundBitmap())
        assertEquals(listOf(bmpA), recycled)
        assertEquals(
            "被拒的 A 不得碰键图缓存代次",
            genAfterB, SkinKeyImageStore.currentGeneration,
        )
    }

    @Test
    fun startupLoadSupersededDuringDecodeByUserApply() {
        // 启动加载在解码期被用户应用超越：快路径比对通过后、提交
        // 前用户安装完成，启动安装在锁内按 token 被拒，不覆盖。
        val snapStart = snapshot("skin-start", "key-start")
        val snapUser = snapshot("skin-user")
        val bmpStart = fakeBitmap()
        val decodeEntered = CountDownLatch(1)
        val decodeRelease = CountDownLatch(1)
        val installFinished = CountDownLatch(1)
        val accepted = Collections.synchronizedList(mutableListOf<Boolean>())
        SkinSnapshotProvider.backgroundDecoder = { _, snap ->
            if (snap.background?.preparedKey == "key-start") {
                decodeEntered.countDown()
                assertTrue(decodeRelease.await(10, TimeUnit.SECONDS))
                bmpStart
            } else {
                null
            }
        }
        val deps = object : SkinSnapshotProvider.StartupLoadDeps {
            override suspend fun awaitBootstrap() {}
            override suspend fun loadSnapshot(context: Context) =
                snapStart to false
            override fun installSnapshot(
                context: Context,
                snapshot: SkinSnapshot,
                pending: Boolean,
                requestToken: Long,
                expectedGeneration: Int,
            ) {
                accepted += SkinSnapshotProvider.install(
                    context, snapshot, requestToken, expectedGeneration,
                )
                installFinished.countDown()
            }
        }
        SkinSnapshotProvider.startupLoadDeps = deps
        SkinSnapshotProvider.ensureLoaded(context)
        assertTrue(decodeEntered.await(10, TimeUnit.SECONDS))
        assertTrue(SkinSnapshotProvider.install(context, snapUser, SkinSnapshotProvider.allocateInstallToken()))
        assertEquals("skin-user", SkinSnapshotProvider.peek()?.ref?.skinId)
        decodeRelease.countDown()
        assertTrue(installFinished.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(false), accepted)
        assertEquals("skin-user", SkinSnapshotProvider.peek()?.ref?.skinId)
        assertEquals(listOf(bmpStart), recycled)
    }

    @Test
    fun startupLoadSkippedWhenApplyWinsDuringBootstrap() {
        // 启动加载在对账期就被用户应用超越：快路径比对失败，
        // installSnapshot 根本不调（省掉一次注定被拒的解码）。
        val snapStart = snapshot("skin-start", "key-start")
        val snapUser = snapshot("skin-user")
        val installCalls = AtomicInteger(0)
        val bootstrapEntered = CountDownLatch(1)
        val bootstrapRelease = CountDownLatch(1)
        val deps = object : SkinSnapshotProvider.StartupLoadDeps {
            override suspend fun awaitBootstrap() {
                bootstrapEntered.countDown()
                bootstrapRelease.await(10, TimeUnit.SECONDS)
            }
            override suspend fun loadSnapshot(context: Context) =
                snapStart to false
            override fun installSnapshot(
                context: Context,
                snapshot: SkinSnapshot,
                pending: Boolean,
                requestToken: Long,
                expectedGeneration: Int,
            ) {
                installCalls.incrementAndGet()
            }
        }
        SkinSnapshotProvider.startupLoadDeps = deps
        SkinSnapshotProvider.ensureLoaded(context)
        assertTrue(bootstrapEntered.await(10, TimeUnit.SECONDS))
        assertTrue(SkinSnapshotProvider.install(context, snapUser, SkinSnapshotProvider.allocateInstallToken()))
        bootstrapRelease.countDown()
        Thread.sleep(500)
        assertEquals(0, installCalls.get())
        assertEquals("skin-user", SkinSnapshotProvider.peek()?.ref?.skinId)
    }

    @Test
    fun staleBlurRefreshLosesToSameSkinReinstall() {
        // blur 重建在解码期，同皮肤重装先提交：只核 ref 会放行
        //（同一 ref），token 核对必须拦下，旧 blur 值不得盖掉
        // 重装带入的背景。
        val snap = snapshot("skin-1", "key-1")
        val bmpFirst = fakeBitmap()
        val bmpReinstall = fakeBitmap()
        val bmpBlur = fakeBitmap()
        val decodeCalls = AtomicInteger(0)
        val blurDecodeEntered = CountDownLatch(1)
        val blurDecodeRelease = CountDownLatch(1)
        SkinSnapshotProvider.backgroundDecoder = { _, s ->
            val bg = s.background
            when {
                bg == null -> null
                bg.blurRadius == 12f -> {
                    blurDecodeEntered.countDown()
                    assertTrue(blurDecodeRelease.await(10, TimeUnit.SECONDS))
                    bmpBlur
                }
                else -> {
                    if (decodeCalls.incrementAndGet() == 1) bmpFirst else bmpReinstall
                }
            }
        }
        SkinSnapshotProvider.blurOverrideProvider = {
            BackgroundBlurOverride(BackgroundBlurMode.ON, 12f)
        }
        // 拉伸模式缝同样打桩：桩 Context 调 getSharedPreferences
        // 会触 android.jar 桩方法抛错；本用例只测 blur 竞态，
        // 拉伸取快照默认值（与 blur 变化不相干）。
        SkinSnapshotProvider.scaleModeProvider = { ImageContentMode.SCALE_TO_FILL }
        assertTrue(SkinSnapshotProvider.install(context, snap, SkinSnapshotProvider.allocateInstallToken()))
        assertEquals(bmpFirst, SkinSnapshotProvider.currentBackgroundBitmap())
        val blurThread = thread { SkinSnapshotProvider.refreshBackground(context) }
        assertTrue(blurDecodeEntered.await(10, TimeUnit.SECONDS))
        assertTrue(SkinSnapshotProvider.install(context, snap, SkinSnapshotProvider.allocateInstallToken()))
        // 同键重装：旧位图保留、新解份回收（既有口径），当前仍为首份。
        assertEquals(bmpFirst, SkinSnapshotProvider.currentBackgroundBitmap())
        assertEquals(listOf(bmpReinstall), recycled)
        blurDecodeRelease.countDown()
        blurThread.join(10_000)
        assertFalse("blur 线程应在放行后退出", blurThread.isAlive)
        assertEquals("skin-1", SkinSnapshotProvider.peek()?.ref?.skinId)
        assertTrue(SkinSnapshotProvider.peek()?.background?.blurRadius == 0f)
        assertEquals(bmpFirst, SkinSnapshotProvider.currentBackgroundBitmap())
        assertEquals(listOf(bmpReinstall, bmpBlur), recycled)
    }

    @Test
    fun backgroundDecodeConcurrencyBoundedToTwo() {
        // 三路安装并发解码：同一时刻在解的不得超过 2 路；三份
        // 位图恰好一份留任、两份回收（晚开始的请求胜出）。
        val snaps = (1..3).map { snapshot("skin-$it", "key-$it") }
        val bitmaps = (1..3).map { fakeBitmap() }
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        SkinSnapshotProvider.backgroundDecoder = { _, snap ->
            startLatch.await(10, TimeUnit.SECONDS)
            val now = active.incrementAndGet()
            maxActive.updateAndGet { prev -> maxOf(prev, now) }
            try {
                Thread.sleep(200)
            } finally {
                active.decrementAndGet()
            }
            val index = snap.ref.skinId.removePrefix("skin-").toInt() - 1
            bitmaps[index]
        }
        val threads = snaps.map { snap ->
            thread {
                startLatch.await(10, TimeUnit.SECONDS)
                SkinSnapshotProvider.install(context, snap, SkinSnapshotProvider.allocateInstallToken())
            }
        }
        startLatch.countDown()
        threads.forEach { it.join(10_000) }
        threads.forEach { assertFalse("安装线程应在 10 秒内退出", it.isAlive) }
        assertTrue("并发解码峰值不得超过 2 路，实测=${maxActive.get()}", maxActive.get() <= 2)
        assertEquals(2, recycled.size)
        val current = SkinSnapshotProvider.currentBackgroundBitmap()
        assertTrue(bitmaps.contains(current))
        assertEquals(2, bitmaps.count { it in recycled })
    }
}
