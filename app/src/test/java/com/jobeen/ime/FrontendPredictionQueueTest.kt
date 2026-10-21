package com.jobeen.ime

import com.jobeen.ime.engine.FrontendPredictionQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前端提交预测队列（第十四批回修）的规格：入队与消息产生同成
 * 败；条目带入队代次，换框清队后旧条目不被认领；认领只比队
 * 首且文本与代次都要对上，过期队首不堵新框。代次全部显式喂
 * 入，确定性可测。
 */
class FrontendPredictionQueueTest {

    @Test
    fun emitFailureLeavesNoEntry() {
        // emitCommit 抛错＝消息根本不会产生：不入队，否则条目
        // 滞留队首、无人认领，其后前端提交的预测全数缺席
        val queue = FrontendPredictionQueue()
        val boom = RuntimeException("emit failed")
        val thrown = assertThrows(RuntimeException::class.java) {
            runBlocking {
                queue.offerAfterEmit("粘贴文本", fieldGen = 3L) { throw boom }
            }
        }
        assertEquals(boom, thrown)
        assertEquals(0, queue.size())
        assertFalse(queue.claim("粘贴文本", currentGen = 3L))
    }

    @Test
    fun emitSuccessEntryClaimableExactlyOnce() {
        val queue = FrontendPredictionQueue()
        runBlocking {
            queue.offerAfterEmit("你好", fieldGen = 3L) { /* 消息产生成功 */ }
        }
        assertEquals(1, queue.size())
        assertTrue(queue.claim("你好", currentGen = 3L))
        // 认领即消费：同一条目不能被第二条 Commit 重复认领
        assertFalse(queue.claim("你好", currentGen = 3L))
        assertEquals(0, queue.size())
    }

    @Test
    fun fieldChangeClearDropsOldEntries() {
        // 换框推进代次时整队清空（RimeEngine 在推进点调
        // clear）：旧框条目不被新框的同文本 Commit 认领
        val queue = FrontendPredictionQueue()
        runBlocking {
            queue.offerAfterEmit("旧框粘贴", fieldGen = 3L) { }
        }
        queue.clear()
        assertEquals(0, queue.size())
        assertFalse(queue.claim("旧框粘贴", currentGen = 4L))
    }

    @Test
    fun staleGenHeadIsNotClaimedAndDoesNotBlock() {
        // 清队漏网的兜底：队首代次已过期时认领不成立，且过期
        // 队首被丢弃、不堵住其后当前代次条目的认领
        val queue = FrontendPredictionQueue()
        runBlocking {
            queue.offerAfterEmit("旧框文本", fieldGen = 3L) { }
            queue.offerAfterEmit("新框文本", fieldGen = 4L) { }
        }
        assertFalse(queue.claim("旧框文本", currentGen = 4L))
        assertTrue(queue.claim("新框文本", currentGen = 4L))
        assertEquals(0, queue.size())
    }

    @Test
    fun headMismatchDoesNotConsume() {
        // FIFO 只比对队首：空格/回车等不入队的直送 Commit 到达
        // 时文本不等、不认领也不消费，队首留给自己的消息
        val queue = FrontendPredictionQueue()
        runBlocking {
            queue.offerAfterEmit("甲", fieldGen = 1L) { }
            queue.offerAfterEmit("乙", fieldGen = 1L) { }
        }
        assertFalse(queue.claim(" ", currentGen = 1L))
        assertFalse(queue.claim("乙", currentGen = 1L))
        assertEquals(2, queue.size())
        assertTrue(queue.claim("甲", currentGen = 1L))
        assertTrue(queue.claim("乙", currentGen = 1L))
    }
}
