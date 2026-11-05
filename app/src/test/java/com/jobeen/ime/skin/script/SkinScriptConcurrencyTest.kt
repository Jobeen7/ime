package com.jobeen.ime.skin.script

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤脚本全局并发：分发层排队许可（信号量）与取消语义。
 *
 * 测的是 [SkinScriptSandbox.acquireRunPermit] 这一层（按键分发
 * 的排队点）：许可数与沙箱硬上限同值（4 槽）。KeyActionListener
 * 的同 scriptId 单飞取消不另测（它只是在此许可之外先 cancel 旧
 * 任务；排队中取消即退队的语义由本文件的取消测试覆盖）。
 */
class SkinScriptConcurrencyTest {

    @Test
    fun acquireRunPermit_fourSlotsFifthTimesOut() = runBlocking {
        // 占满 4 槽：第 5 个取许可在短超时内返回 false（不执行、不占槽）。
        val acquired = CountDownLatch(SkinScriptSandbox.MAX_CONCURRENT_SCRIPT_RUNS)
        val holders = (1..SkinScriptSandbox.MAX_CONCURRENT_SCRIPT_RUNS).map {
            async(Dispatchers.Default) {
                try {
                    assertTrue(SkinScriptSandbox.acquireRunPermit())
                    acquired.countDown()
                    delay(2000)
                } finally {
                    SkinScriptSandbox.releaseRunPermit()
                }
            }
        }
        assertTrue(acquired.await(10, TimeUnit.SECONDS))
        assertFalse(SkinScriptSandbox.acquireRunPermit(timeoutMs = 100))
        holders.forEach { it.cancelAndJoin() }
        // 槽空后取许可成功（取消的持有者已在 finally 里归还）。
        assertTrue(SkinScriptSandbox.acquireRunPermit(timeoutMs = 1000))
        SkinScriptSandbox.releaseRunPermit()
    }

    @Test
    fun acquireRunPermit_waitingJobCancelledLeavesQueue() = runBlocking {
        // 占满 4 槽；第 5 个在排队中等许可时被取消：取消即退出队列，
        // 不占槽（同 scriptId 再按取消旧任务即此语义——排队中的旧
        // 任务随取消退出，不会稍后又占槽执行）。
        val acquired = CountDownLatch(SkinScriptSandbox.MAX_CONCURRENT_SCRIPT_RUNS)
        val holders = (1..SkinScriptSandbox.MAX_CONCURRENT_SCRIPT_RUNS).map {
            async(Dispatchers.Default) {
                try {
                    assertTrue(SkinScriptSandbox.acquireRunPermit())
                    acquired.countDown()
                    delay(2000)
                } finally {
                    SkinScriptSandbox.releaseRunPermit()
                }
            }
        }
        assertTrue(acquired.await(10, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default) {
            SkinScriptSandbox.acquireRunPermit(timeoutMs = 10_000)
        }
        delay(200)
        assertFalse(waiter.isCompleted)
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
        holders.forEach { it.cancelAndJoin() }
        // 取消的排队者没占槽：许可仍可取。
        assertTrue(SkinScriptSandbox.acquireRunPermit(timeoutMs = 1000))
        SkinScriptSandbox.releaseRunPermit()
    }
}
