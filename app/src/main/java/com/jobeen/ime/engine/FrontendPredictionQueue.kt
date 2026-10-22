package com.jobeen.ime.engine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 前端提交（剪贴板/常用语/语音/符号键等经 RimeEngine.requestCommit）
 * 待发起预测的文本队列（FIFO）：requestCommit 的 job 先把文本入
 * 队、再产生直送 Commit 消息，RimeEngine.handleRimeMessage 在
 * 对应直送 Commit 落屏后按队首认领并发起预测。
 *
 * 三条不变量：
 * - 条目先于消息可见（[offerAndEmit]）：emitCommit 的消息在
 *   emit() 返回**之前**就已对消费者可见（trySend 落队即发布，
 *   消费链全程异线程），认领可能抢在 emit() 返回前发生——入队
 *   必须排在 emit 之前，认领到达时条目已在队首。旧顺序（先
 *   emit 后入队）下消费者抢先认领必落空、条目随后滞留队首，
 *   同框会话内其后不同文本的认领被持续挡住（第十四批自引入
 *   回归，第十六批按令牌回滚方案修复）。emit 抛错（消息根本
 *   不会产生）时按条目令牌只摘除自己那一条并原样上抛：没有
 *   消息就没有认领方，不回滚则条目滞留队首、后果同上。令牌
 *   即条目对象本身（[Entry] 非 data class，相等性为引用同
 *   一），文本与代次都相同的另一条不会被误摘。
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

    /**
     * 队列条目。**刻意不用 data class**：相等性必须为引用同一
     * ——条目对象本身就是回滚令牌，[offerAndEmit] 失败时按引用
     * 摘除；若按值相等，文本与代次相同的另一条会被误摘。
     */
    private class Entry(val text: String, val fieldGen: Long)

    private val queue = ConcurrentLinkedQueue<Entry>()

    /**
     * 先把条目入队，再执行 [emit] 产生直送 Commit 消息；
     * [emit] 抛错则按令牌只摘除本条目、异常原样上抛。
     */
    suspend fun offerAndEmit(text: String, fieldGen: Long, emit: suspend () -> Unit) {
        val entry = Entry(text, fieldGen)
        queue.offer(entry)
        try {
            emit()
        } catch (t: Throwable) {
            // 按引用摘除：ConcurrentLinkedQueue.remove 用相等性
            // 定位，Entry 的相等性为引用同一，只会命中本条目
            queue.remove(entry)
            throw t
        }
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
