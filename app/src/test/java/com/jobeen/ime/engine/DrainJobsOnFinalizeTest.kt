// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * drainJobsOnFinalize（E-S3）：finalize 关库后残留的作业永不会被消费
 * 循环取走，逐条调丢弃回调让等待方快速失败。
 *
 * 覆盖：全部残留条目回调各执行一次；单条回调抛错不阻断其余条目；
 * 空通道与已关闭空通道是空操作；无回调的条目不抛错。
 */
class DrainJobsOnFinalizeTest {

    @Test
    fun allPendingJobsGetOnDroppedOnce() {
        val jobs = Channel<RimeEngine.QueuedJob>(10)
        val dropped = mutableListOf<Int>()
        repeat(3) { i ->
            jobs.trySend(RimeEngine.QueuedJob(block = {}, onDropped = { dropped += i }))
        }
        drainJobsOnFinalize(jobs)
        assertEquals(listOf(0, 1, 2), dropped)
    }

    @Test
    fun throwingCallbackDoesNotBlockOtherJobs() {
        val jobs = Channel<RimeEngine.QueuedJob>(10)
        val dropped = mutableListOf<Int>()
        jobs.trySend(RimeEngine.QueuedJob(block = {}, onDropped = { dropped += 0 }))
        jobs.trySend(RimeEngine.QueuedJob(
            block = {},
            onDropped = { throw RuntimeException("boom") },
        ))
        jobs.trySend(RimeEngine.QueuedJob(block = {}, onDropped = { dropped += 2 }))
        drainJobsOnFinalize(jobs)
        assertEquals(listOf(0, 2), dropped)
    }

    @Test
    fun emptyAndClosedChannelsAreNoOps() {
        drainJobsOnFinalize(Channel(10))
        val closed = Channel<RimeEngine.QueuedJob>(10)
        closed.close()
        drainJobsOnFinalize(closed)
    }

    @Test
    fun jobWithoutCallbackIsSkippedSilently() {
        val jobs = Channel<RimeEngine.QueuedJob>(10)
        jobs.trySend(RimeEngine.QueuedJob(block = {}))
        drainJobsOnFinalize(jobs)
    }
}
