package com.jobeen.ime.skin.runtime

import android.content.Context
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.store.SkinGenRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 启动快照加载（审计 #1-6）：首次调用永不在调用线程等对账。
 *
 * 测的是 [SkinSnapshotProvider.ensureLoaded] 的编排（经
 * [SkinSnapshotProvider.StartupLoadDeps] 注入慢对账与快照录制）：
 * 慢对账时首帧调用立即返回（主线程耗时有界）、快照在后台装好、
 * 其间安装/失效可按代次 supersede。真实安装路径（install、
 * 位图、音效）由 ApplyTransactionTest 等覆盖，本文件不碰
 * Android 框架实现（假依赖不调 Context 方法）。
 */
class SkinSnapshotProviderStartupLoadTest {

    private class FakeDeps(
        var snapshot: SkinSnapshot? = null,
        var pending: Boolean = false,
        /** 为真时 installSnapshot 透传给真实 install（token＋预期代次核对）；为假只记录。 */
        var delegateToRealInstall: Boolean = false,
    ) : SkinSnapshotProvider.StartupLoadDeps {
        val bootstrapCalls = AtomicInteger(0)
        val bootstrapEntered = CountDownLatch(1)
        val bootstrapRelease = CountDownLatch(1)
        val installed = mutableListOf<SkinSnapshot>()
        val installAccepted = mutableListOf<Boolean>()
        val installFinished = CountDownLatch(1)
        var installedPending: Boolean? = null

        override suspend fun awaitBootstrap() {
            bootstrapCalls.incrementAndGet()
            bootstrapEntered.countDown()
            bootstrapRelease.await(10, TimeUnit.SECONDS)
        }

        override suspend fun loadSnapshot(context: Context): Pair<SkinSnapshot, Boolean>? =
            snapshot?.let { it to pending }

        override fun installSnapshot(
            context: Context,
            snapshot: SkinSnapshot,
            pending: Boolean,
            requestToken: Long,
            expectedGeneration: Long,
        ) {
            synchronized(installed) { installed += snapshot }
            installedPending = pending
            if (delegateToRealInstall) {
                val accepted = SkinSnapshotProvider.install(
                    context, snapshot, requestToken, expectedGeneration,
                )
                synchronized(installAccepted) { installAccepted += accepted }
            }
            installFinished.countDown()
        }
    }

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

    private fun snapshot() = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme(),
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        soundBindings = SoundBindings.EMPTY,
    )

    /** 桩 Context：单测桩的 Application 构造器不触框架方法，仅作占位（假依赖不调它）。 */
    private val context: Context = android.app.Application()

    private lateinit var savedDeps: SkinSnapshotProvider.StartupLoadDeps
    private lateinit var deps: FakeDeps

    @Before
    fun setUp() {
        savedDeps = SkinSnapshotProvider.startupLoadDeps
        deps = FakeDeps(snapshot = snapshot())
        SkinSnapshotProvider.startupLoadDeps = deps
        SkinSnapshotProvider.invalidate()
    }

    @After
    fun tearDown() {
        deps.bootstrapRelease.countDown()
        SkinSnapshotProvider.startupLoadDeps = savedDeps
        SkinSnapshotProvider.invalidate()
    }

    @Test
    fun ensureLoaded_returnsImmediatelyWhileBootstrapSlow() {
        // 慢对账（10 秒才放行）：首帧调用必须立即返回，不卡调用线程；
        // 此时快照未到，current 为 null（内置默认回退，与无皮肤同口径）。
        val startMs = System.currentTimeMillis()
        SkinSnapshotProvider.ensureLoaded(context)
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue(
            "ensureLoaded must not block on slow bootstrap, took=${elapsedMs}ms",
            elapsedMs < 1000,
        )
        assertNull(SkinSnapshotProvider.peek())
        assertTrue(deps.bootstrapEntered.await(10, TimeUnit.SECONDS))
        assertEquals(1, deps.bootstrapCalls.get())
    }

    @Test
    fun ensureLoaded_installsSnapshotAsyncAfterBootstrap() {
        SkinSnapshotProvider.ensureLoaded(context)
        assertTrue(deps.bootstrapEntered.await(10, TimeUnit.SECONDS))
        assertNull(SkinSnapshotProvider.peek())
        deps.bootstrapRelease.countDown()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (deps.installed.size == 1) break
            Thread.sleep(50)
        }
        assertEquals(1, deps.installed.size)
        assertEquals("skin-1", deps.installed.single().ref.skinId)
        assertEquals(false, deps.installedPending)
    }

    @Test
    fun ensureLoaded_secondCallDoesNotRelaunch() {
        SkinSnapshotProvider.ensureLoaded(context)
        SkinSnapshotProvider.ensureLoaded(context)
        assertTrue(deps.bootstrapEntered.await(10, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, deps.bootstrapCalls.get())
    }

    @Test
    fun ensureLoaded_supersededByInvalidateThenReloads() {
        // 其间失效（代次递增）：在途的启动加载完成时按代次丢弃，
        // 不安装旧快照；下次 ensureLoaded 重新发起并装好。
        SkinSnapshotProvider.ensureLoaded(context)
        assertTrue(deps.bootstrapEntered.await(10, TimeUnit.SECONDS))
        SkinSnapshotProvider.invalidate()
        deps.bootstrapRelease.countDown()
        Thread.sleep(500)
        assertTrue(deps.installed.isEmpty())
        SkinSnapshotProvider.ensureLoaded(context)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (deps.installed.size == 1) break
            Thread.sleep(50)
        }
        assertEquals(1, deps.installed.size)
    }
}
