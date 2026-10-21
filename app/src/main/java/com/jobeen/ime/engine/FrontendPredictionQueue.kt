package com.jobeen.ime.engine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 前端提交（剪贴板/常用语/语音/符号键等经 RimeEngine.requestCommit）
 * 待发起预测的文本队列（FIFO）：requestCommit 的 job 产生直送
 * Commit 消息成功后把文本入队，RimeEngine.handleRimeMessage 在
 * 对应直送 Commit 落屏后按队首认领并发起预测。
 *
 * 三条不变量：
 * - 入队与消息产生同成败（[offerAfterEmit]）：消息产生抛错时不
 *   入队——没有消息就没有认领方，提前入队的条目会滞留队首，
 *   其后每条前端提交的认领都比对这条旧队首，前端提交联想从
 *   此静默缺席且队列无界增长。
 * - 条目带入队时的输入框代次：换框推进代次时整队清空
 *   （[clear]，由 RimeEngine 在代次推进点调用）；认领时队首
 *   代次与当前代次不符的按过期丢弃并继续比对（清队漏网的兜
 *   底），旧框条目不会被新框认领、也不会堵住新框队首。
 * - 认领只比队首（FIFO）：队首文本与到达的直送 Commit 不等
 *   时不认领、原样保留——空格/回车的直送 Commit 不入队，其到
 *   达不消费任何条目。
 *
 * 写点在 jobs 串行线（入队）、读/消费点在 reduce 线（认领）与
 * 主线程（换框清队），故底层用并发队列；认领的 peek/poll 非
 * 原子组合在本队列的单认领方前提下成立。
 */
internal class FrontendPredictionQueue {

    private data class Entry(val text: String, val fieldGen: Long)

    private val queue = ConcurrentLinkedQueue<Entry>()

    /**
     * 先执行 [emit] 产生直送 Commit 消息，成功返回后才入队；
     * [emit] 抛错则异常原样上抛、不入队。
     */
    suspend fun offerAfterEmit(text: String, fieldGen: Long, emit: suspend () -> Unit) {
        emit()
        queue.offer(Entry(text, fieldGen))
    }

    /**
     * 按队首认领文本 [text]：队首条目文本相同、且入队代次仍为
     * [currentGen] 时移除队首并返回 true。途中遇代次已过期的
     * 队首先丢弃再继续比对；队首文本不等（或队列已空）返回
     * false 且不消费任何当前代次条目。
     */
    fun claim(text: String, currentGen: Long): Boolean {
        while (true) {
            val head = queue.peek() ?: return false
            if (head.fieldGen != currentGen) {
                // 过期队首：丢弃后继续看下一条，不让旧框残余
                // 堵住新框的认领
                queue.poll()
                continue
            }
            if (head.text != text) return false
            queue.poll()
            return true
        }
    }

    /** 换框（输入框代次推进）时整队清空：旧框条目再无认领方。 */
    fun clear() {
        queue.clear()
    }

    /** 当前条目数（测试与诊断用）。 */
    fun size(): Int = queue.size
}
