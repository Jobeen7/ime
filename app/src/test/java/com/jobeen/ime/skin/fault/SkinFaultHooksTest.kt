package com.jobeen.ime.skin.fault

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故障注入钩子（详设 §2.9）：默认空分发立即返回；debug 实现在
 * 标记文件存在时阻塞、标记删除后放行。
 */
class SkinFaultHooksTest {

    @After
    fun resetHandler() {
        SkinFaultHooks.handler = {}
    }

    @Test
    fun defaultHandlerIsNoOp() {
        val start = System.nanoTime()
        SkinFaultHooks.hit(SkinFaultHooks.Point.A1)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("default hit must return immediately, took ${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun noMarkerReturnsImmediately() {
        val dir = Files.createTempDirectory("fault-hooks").toFile()
        DebugFaultHooks.install(dir)
        val start = System.nanoTime()
        SkinFaultHooks.hit(SkinFaultHooks.Point.I1)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 500)
    }

    @Test
    fun markerBlocksUntilRemoved() {
        val dir = Files.createTempDirectory("fault-hooks").toFile()
        val marker = File(dir, SkinFaultHooks.Point.A2.name)
        marker.writeText("hold")
        DebugFaultHooks.install(dir)

        val remover = thread {
            Thread.sleep(300)
            marker.delete()
        }
        val start = System.nanoTime()
        SkinFaultHooks.hit(SkinFaultHooks.Point.A2)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        remover.join()
        assertTrue("hit should block while marker exists, took ${elapsedMs}ms", elapsedMs >= 250)
        assertTrue(elapsedMs < 10_000)
    }
}
