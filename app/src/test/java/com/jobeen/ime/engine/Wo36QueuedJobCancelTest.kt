// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import com.jobeen.ime.data.manager.DeployAwaitResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * finalize 取消作用域时等待方等超时（WO36 I-L1）：
 * [awaitDeployOutcome] 让 deployAndAwait 的等待方与部署 deferred、
 * 作用域死亡信号、超时三者竞速，finalize 已调时立即 FAILED，不等
 * 作业体 native 收尾；[claimDeployReleaseOnce] 保证三方释放点
 * （作业体 finally、onDropped、等待方）只放一次标志。
 *
 * 旧口径的等超时机理：等待方只等 deferred；作业体跑在 rime-main
 * 单线程上，取消恢复须排在阻塞 native 调用（deploy／
 * joinMaintenanceThread）之后，作业体 finally 到不了等待方，等待
 * 方等满整个 timeoutMs。消费循环的 catch 只在 !started 时调
 * onDropped（QueuedJob.started 守卫，见 QueuedJobStartedGuardTest），
 * 已执行作业的等待方不在其覆盖内。
 *
 * 状态空间：
 * - 认领：首次 true、之后恒 false；并发认领恰好一次 true。
 * - 竞速：deferred 先到取其值；死亡信号先到立即 FAILED（不等满超时）；
 *   两者都不到走超时 null；死亡信号已完成时立即 FAILED。
 * 引擎本体接线（finalize 完成信号、deployAndAwait 三方释放点走
 * 同一认领）无 JVM 协程循环可测，是代码自审项；真机验收：部署中
 * 杀引擎（finalize）时等待方立即 FAILED、不等超时。
 */
class Wo36QueuedJobCancelTest {

    // ---- claimDeployReleaseOnce ----

    @Test
    fun firstClaimWinsLaterClaimsFail() {
        val claimed = AtomicBoolean(false)
        assertTrue(claimDeployReleaseOnce(claimed))
        assertFalse("second claim must not release again", claimDeployReleaseOnce(claimed))
        assertFalse("third claim must not release again", claimDeployReleaseOnce(claimed))
    }

    @Test
    fun concurrentClaimsReleaseExactlyOnce() {
        val claimed = AtomicBoolean(false)
        val threads = 16
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val results = BooleanArray(threads)
        repeat(threads) { i ->
            thread {
                startGate.await()
                results[i] = claimDeployReleaseOnce(claimed)
                doneGate.countDown()
            }
        }
        startGate.countDown()
        doneGate.await()
        assertEquals("exactly one caller may release", 1, results.count { it })
    }

    // ---- awaitDeployOutcome ----

    @Test
    fun deferredCompletedFirstReturnsItsValue() = runBlocking {
        val deferred = CompletableDeferred<DeployAwaitResult>().apply {
            complete(DeployAwaitResult.COMPLETED)
        }
        assertEquals(
            DeployAwaitResult.COMPLETED,
            awaitDeployOutcome(deferred, CompletableDeferred(), 5_000),
        )
    }

    @Test
    fun deferredFailedFirstReturnsFailed() = runBlocking {
        val deferred = CompletableDeferred<DeployAwaitResult>().apply {
            complete(DeployAwaitResult.FAILED)
        }
        assertEquals(
            DeployAwaitResult.FAILED,
            awaitDeployOutcome(deferred, CompletableDeferred(), 5_000),
        )
    }

    @Test
    fun scopeDeathAlreadyCompletedReturnsFailedImmediately() = runBlocking {
        // 核心回归：作用域已死时不等满超时（旧口径等满 timeoutMs）。
        val deferred = CompletableDeferred<DeployAwaitResult>()
        val death = CompletableDeferred<Unit>().apply { complete(Unit) }
        val start = System.nanoTime()
        val result = withTimeout(5_000) {
            awaitDeployOutcome(deferred, death, 60_000)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(DeployAwaitResult.FAILED, result)
        assertTrue("must return promptly, not after 60s timeout (took ${elapsedMs}ms)",
            elapsedMs < 2_000)
    }

    @Test
    fun scopeDeathDuringWaitReturnsFailedBeforeTimeout() = runBlocking {
        val deferred = CompletableDeferred<DeployAwaitResult>()
        val death = CompletableDeferred<Unit>()
        launch { delay(50); death.complete(Unit) }
        val start = System.nanoTime()
        val result = withTimeout(5_000) {
            awaitDeployOutcome(deferred, death, 60_000)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(DeployAwaitResult.FAILED, result)
        assertTrue("must return on death, not after timeout (took ${elapsedMs}ms)",
            elapsedMs < 2_000)
    }

    @Test
    fun deferredCompletingDuringWaitReturnsItsValue() = runBlocking {
        val deferred = CompletableDeferred<DeployAwaitResult>()
        val death = CompletableDeferred<Unit>()
        launch { delay(50); deferred.complete(DeployAwaitResult.COMPLETED) }
        assertEquals(
            DeployAwaitResult.COMPLETED,
            awaitDeployOutcome(deferred, death, 5_000),
        )
    }

    @Test
    fun deferredWinsWhenItCompletesBeforeDeath() = runBlocking {
        val deferred = CompletableDeferred<DeployAwaitResult>()
        val death = CompletableDeferred<Unit>()
        launch { delay(50); deferred.complete(DeployAwaitResult.COMPLETED) }
        launch { delay(300); death.complete(Unit) }
        assertEquals(
            DeployAwaitResult.COMPLETED,
            awaitDeployOutcome(deferred, death, 5_000),
        )
    }

    @Test
    fun deathWinsWhenItCompletesBeforeDeferred() = runBlocking {
        val deferred = CompletableDeferred<DeployAwaitResult>()
        val death = CompletableDeferred<Unit>()
        launch { delay(50); death.complete(Unit) }
        launch { delay(300); deferred.complete(DeployAwaitResult.COMPLETED) }
        assertEquals(
            DeployAwaitResult.FAILED,
            awaitDeployOutcome(deferred, death, 5_000),
        )
    }

    @Test
    fun neitherCompletingReturnsNullAfterTimeout() = runBlocking {
        val start = System.nanoTime()
        val result = awaitDeployOutcome(
            CompletableDeferred(), CompletableDeferred(), 150,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(result)
        assertTrue("must wait out the timeout (took ${elapsedMs}ms)", elapsedMs >= 100)
    }
}
