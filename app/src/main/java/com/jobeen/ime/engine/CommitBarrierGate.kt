// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 上屏落屏完成等待的配对门（WO40，CommitAndAct 的竞态修法）。
 *
 * 问题：组字中回车的一按上屏＋执行动作（WO37）里，上屏走
 * notificationQueue→messageChannel 异步投递，动作经 Main.immediate
 * 直达编辑器，动作可能先于文字落屏执行。
 *
 * 解法（栅栏信标）：等待方先 [arm] 取一个 id，引擎把
 * [com.jobeen.ime.engine.rime.core.RimeMessage.BarrierMessage]（id）
 * 投进与上屏 Commit 同一条 notificationQueue——同 FIFO、同一单线程
 * 消费者、同一条 messageChannel 观察者链；IMS 观察者按序处理到栅栏
 * 时，其前的 Commit 必已落屏（[com.jobeen.ime.input.keyboard.window.MessageHandler]
 * 的栅栏分支调 [com.jobeen.ime.engine.RimeEngine.completeCommitBarrier]）。
 * 等待方 [await] 的就是这个真实落屏信号，不睡、不轮询。
 *
 * 配对令牌：id 自 1 递增；[complete] 只完成当前武装的等待——超时后
 * 迟到的栅栏、或已过期轮次的栅栏一律忽略，绝不完成下一轮等待。
 * [await] 有界：[withTimeoutOrNull] 到时即放行（返回 false），永不
 * 卡键；调用方超时后照常执行动作（仅记日志），退化为 WO37 行为。
 *
 * 并发：串行作业队列保证同一时刻至多一个等待方，[arm] 在生产路径
 * 上恒在上一轮等待结束（await 的 finally 已清武装）后才调用；
 * 若 [arm] 在某轮等待未结束时被调用，旧武装被取代、其栅栏随后
 * 到达亦被忽略，旧等待方仅靠自身超时收敛（永不无限挂起）。
 * 无 Android 依赖，可在 JVM 单测。
 */
internal class CommitBarrierGate {

    private val seq = AtomicLong(0L)

    @Volatile
    private var pending: Pair<Long, CompletableDeferred<Unit>>? = null

    /**
     * 武装一轮等待，返回栅栏 id（调用方把该 id 投进 notificationQueue）。
     * id 自 1 起递增；-1 永不作为真实 id（见 BarrierMessage 的参数
     * 形态不符回落）。
     */
    fun arm(): Long {
        val id = seq.incrementAndGet()
        pending = id to CompletableDeferred()
        return id
    }

    /** 完成当前武装的等待（仅当 [id] 与武装 id 一致）；其余一律忽略。 */
    fun complete(id: Long) {
        val current = pending
        if (current != null && current.first == id) {
            current.second.complete(Unit)
        }
    }

    /**
     * 等待栅栏到达。返回 true = 栅栏在 [timeoutMs] 内到达（上屏已落屏）；
     * false = 超时（调用方放行执行动作，仅记日志）或本轮等待未武装／
     * 已被更新的武装取代。等待结束时清理武装（仅当仍是本轮）。
     * 协程取消时抛 CancellationException（作业已死，无动作可执行）。
     */
    suspend fun await(id: Long, timeoutMs: Long): Boolean {
        val current = pending
        if (current == null || current.first != id) return false
        return try {
            withTimeoutOrNull(timeoutMs) { current.second.await() } != null
        } finally {
            if (pending?.first == id) pending = null
        }
    }
}
