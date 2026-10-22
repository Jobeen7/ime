package com.jobeen.ime

import com.jobeen.ime.engine.FrontendPredictionQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前端提交预测队列的规格（第十六批修订）：条目先于消息可见入
 * 队，emit 抛错按条目令牌回滚本条；条目带入队代次，换框清队
 * 后旧条目不被认领；认领只比队首且文本与代次都要对上，过期
 * 队首不堵新框。代次全部显式喂入，确定性可测。
 */
class FrontendPredictionQueueTest {

    @Test
    fun emitFailureLeavesNoEntry() {
        // emitCommit 抛错＝消息根本不会产生：条目已先行入队
        // （第十六批起顺序反转为先入队），须按令牌回滚摘除，
        // 否则滞留队首、无人认领，其后前端提交的预测全数缺席。
        // 本条断言（异常原样上抛、队空、不可认领）与第十四批的
        // 「先 emit 后入队」实现口径相同，机制已换为入队后回滚
        val queue = FrontendPredictionQueue()
        val boom = RuntimeException("emit failed")
        val thrown = assertThrows(RuntimeException::class.java) {
            runBlocking {
                queue.offerAndEmit("粘贴文本", fieldGen = 3L) { throw boom }
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
            queue.offerAndEmit("你好", fieldGen = 3L) { /* 消息产生成功 */ }
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
            queue.offerAndEmit("旧框粘贴", fieldGen = 3L) { }
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
            queue.offerAndEmit("旧框文本", fieldGen = 3L) { }
            queue.offerAndEmit("新框文本", fieldGen = 4L) { }
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
            queue.offerAndEmit("甲", fieldGen = 1L) { }
            queue.offerAndEmit("乙", fieldGen = 1L) { }
        }
        assertFalse(queue.claim(" ", currentGen = 1L))
        assertFalse(queue.claim("乙", currentGen = 1L))
        assertEquals(2, queue.size())
        assertTrue(queue.claim("甲", currentGen = 1L))
        assertTrue(queue.claim("乙", currentGen = 1L))
    }

    @Test
    fun claimDuringEmitWindowSucceeds() {
        // N1 回归：消息在 emit() 返回前就已对消费者可见，消费者
        // 抢先处理该 Commit 并认领（此处在 emit 挂起体内部模拟
        // 认领到达）——条目已先行入队，认领必须成功；第十四批
        // 「先 emit 后入队」下此处队空、认领必落空
        val queue = FrontendPredictionQueue()
        var claimedDuringEmit = false
        runBlocking {
            queue.offerAndEmit("语音整句", fieldGen = 5L) {
                claimedDuringEmit = queue.claim("语音整句", currentGen = 5L)
            }
        }
        assertTrue(claimedDuringEmit)
        assertEquals(0, queue.size())
    }

    @Test
    fun emitFailureRollsBackOnlyOwnEntry() {
        // 回滚按条目令牌：B 的 emit 抛错只摘 B，先行成功入队的
        // A 不受误伤、仍可被自己的 Commit 认领
        val queue = FrontendPredictionQueue()
        runBlocking {
            queue.offerAndEmit("甲", fieldGen = 1L) { }
        }
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                queue.offerAndEmit("乙", fieldGen = 1L) { throw RuntimeException("boom") }
            }
        }
        assertEquals(1, queue.size())
        assertTrue(queue.claim("甲", currentGen = 1L))
        assertFalse(queue.claim("乙", currentGen = 1L))
        assertEquals(0, queue.size())
    }

    @Test
    fun emitFailureRollbackKeepsSameTextTwin() {
        // 文本与代次全同的两条：第二条 emit 抛错回滚时，不得把
        // 第一条（消息已成功产生、认领尚在途）误摘——令牌为条
        // 目引用本身，摘除只命中自己那一条
        val queue = FrontendPredictionQueue()
        runBlocking {
            queue.offerAndEmit("同文", fieldGen = 2L) { }
        }
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                queue.offerAndEmit("同文", fieldGen = 2L) { throw RuntimeException("boom") }
            }
        }
        // 恰余一条且恰可被认领一次
        assertEquals(1, queue.size())
        assertTrue(queue.claim("同文", currentGen = 2L))
        assertFalse(queue.claim("同文", currentGen = 2L))
        assertEquals(0, queue.size())
    }

    @Test
    fun consecutiveDifferentTextsClaimedDuringEmit_doNotStall() {
        // N1 完整序列：A/B/C 连续前端提交，每条的认领都在自己
        // 的 emit 窗口内抢先到达——全部成功、队列归零，不再出
        // 现「A 迟入队首、B 的认领被队首 A 挡住」的滞留链
        val queue = FrontendPredictionQueue()
        val claims = mutableListOf<Boolean>()
        runBlocking {
            for (text in listOf("甲", "乙", "丙")) {
                queue.offerAndEmit(text, fieldGen = 7L) {
                    claims += queue.claim(text, currentGen = 7L)
                }
            }
        }
        assertEquals(listOf(true, true, true), claims)
        assertEquals(0, queue.size())
    }
}
