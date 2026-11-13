// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueuedJob.started 守卫（I-L1）：消费循环的 CancellationException
 * 分支只在作业体未执行时才调 onDropped——作业体执行中被取消时
 * 其 finally 已放过一次，onDropped 再放一次即双放（releaseRunning
 * 是普通置位，两次释放之间另一路抢到部署位会被第二放误删）。
 *
 * 本文件只钉 started 的单向锁存语义（纯状态、可 JVM 测）：初始
 * false；置真后不可再变回 false；第二次置真可被调用方识别为“已
 * 执行”。消费循环的接线（runOnReady 块内紧贴 block 调用前置真、
 * 两 catch 分支只在 !started 时调 onDropped）与作业体内释放点
 * 唯一性（只留 finally 一处）是代码自审项，JVM 测不了协程循环；
 * 既有 DataAuditFixesTest 的 EngineDeployState 收放断言不受影响。
 */
class QueuedJobStartedGuardTest {

    @Test
    fun startedIsFalseInitially() {
        val job = RimeEngine.QueuedJob(block = {})
        assertFalse(job.started.get())
    }

    @Test
    fun startedIsOneWayLatch() {
        val job = RimeEngine.QueuedJob(block = {})
        // 消费循环在作业体执行前置真；置真后 getAndSet 再返回 true，
        // 调用方凭此识别“已执行”、不再调 onDropped。
        assertFalse("first set wins", job.started.getAndSet(true))
        assertTrue(job.started.get())
        assertTrue("second set sees already-started", job.started.getAndSet(true))
        assertTrue(job.started.get())
    }

    @Test
    fun compareAndSetModelsGuardCheck() {
        val job = RimeEngine.QueuedJob(block = {})
        assertTrue("unstarted job passes the !started guard",
            job.started.compareAndSet(false, true))
        assertFalse("started job fails the !started guard",
            job.started.compareAndSet(false, true))
    }
}
