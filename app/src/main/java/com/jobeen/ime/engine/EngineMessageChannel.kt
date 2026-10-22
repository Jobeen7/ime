package com.jobeen.ime.engine

import com.jobeen.ime.engine.data.EngineMessage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * 引擎 UI 消息的统一出口（RimeEngine 的全部 UI 消息经此投递）。
 *
 * 消息分两类，投递语义不同：
 * - 控制类（上屏 Commit、切方案 Schema、部署 Deploy、状态 Status、
 *   候选分页追加页 Candidates(append=true) 等一次性事件）：走
 *   [flow] 的 emit 挂起等待，永不丢、保序。
 * - 易变类（候选/组字/联想等状态型消息，每键都来、下一批即取代）：
 *   最新值合流——直投 [flow] 成功即达；缓冲满直投失败时不丢弃，
 *   而是写入该类型的待补发槽（每类至多一条、新值覆盖旧值），由
 *   后续任一 [emit] 调用与收集侧每交付一条后的补发把槽内最新值
 *   送进 [flow]。中间帧可被更新值合流掉，**最新一帧必达**。
 *
 * 为什么必须合流（第十六批，wode 截图定案）：旧实现易变类走
 * tryEmit、缓冲满静默丢自己，其隐含假设「下一批同类消息自愈」
 * 对一串快打的**最后一批**不成立——最后一批组字被丢后没有下一
 * 批，胶囊永久滞留旧值（快打 9633'9633 时胶囊停在 "wo"、候选
 * 却是最终态）。合流后最后一批即使在缓冲满时产生，也只是进槽
 * 滞留，收集者排空缓冲的过程中必被补发。
 *
 * 顺序不变量：
 * - 同类易变消息严格保序：某类已有待补发值时，新值只覆盖槽、
 *   不直投（直投会让新值越过尚未补发的旧值，收集侧收到逆序的
 *   一对状态快照）。此不变量由 [volatileLock] 兜住：补发的
 *   「摘槽＋投递」与易变分支的「补发＋槽检查＋直投」共用同一把
 *   锁串行化——否则摘槽后槽位瞬时为空，窗口内并发直投的新值会
 *   越过随后投递的旧值，形成 [新值, 旧值] 逆序对且旧值滞留为
 *   最终显示（第十六批收尾审查 A-1）。
 * - 控制类之间、以及未溢出时的全部消息，维持原生产顺序不变。
 * - 溢出窗口内易变消息相对其后控制消息的次序可放宽（滞留值在
 *   控制消息之后补发）：此时旧实现是整条丢弃，放宽次序严格优
 *   于丢失；且引擎在 Commit 后必同步发出新组字态，同类槽会被
 *   新态覆盖，陈旧值在补发前即被取代。
 *
 * 收集侧可多方（IMS 主线程、AppStartup 进程作用域各一份）：
 * [flow] 本身多播；待补发槽的摘取是原子操作（remove(key,
 * value)），多方补发不会把同一条值重复送进 [flow]。
 */
internal class EngineMessageChannel {

    // 缓冲本身用 SUSPEND 保序、永不自动丢弃（与旧实现一致）
    private val flow = MutableSharedFlow<EngineMessage>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** 易变类待补发槽：键为消息运行时类，每类至多一条最新值。 */
    private val pendingVolatile =
        ConcurrentHashMap<Class<out EngineMessage>, EngineMessage>()

    /**
     * 易变路径临界区串行锁：[flushPendingLocked] 全体与 [emit]
     * 易变分支（补发＋槽检查＋tryEmit）全程在此锁内执行，这几段
     * 都不挂起；控制类分支的挂起 `flow.emit` 必须留在锁外（不得
     * 持锁挂起）。审查 A-1，见类注释的顺序不变量。
     */
    private val volatileLock = Any()

    /** 当前收集者数量（测试等待订阅建立、诊断用）。 */
    internal val subscriptionCount: kotlinx.coroutines.flow.StateFlow<Int>
        get() = flow.subscriptionCount

    /** 易变类：状态快照，丢中间帧可由同类更新值合流；其余皆为控制类 */
    private fun EngineMessage.isVolatile(): Boolean = when (this) {
        is EngineMessage.Candidates -> !append
        is EngineMessage.Composition, is EngineMessage.InlinePreedit,
        is EngineMessage.DynamicPreedit, is EngineMessage.PossibleCandidatePinYin,
        is EngineMessage.CandidateMenu -> true
        else -> false
    }

    /** 全部 UI 消息的统一出口：易变类合流必达，控制类 emit 不丢 */
    suspend fun emit(msg: EngineMessage) {
        if (!msg.isVolatile()) {
            // 先补发滞留值：它们生产在先，尽力维持生产顺序。补发
            // 在锁内串行化；本条的挂起投递在锁外（不得持锁挂起）
            synchronized(volatileLock) { flushPendingLocked() }
            flow.emit(msg)
            return
        }
        val key = msg.javaClass
        // 易变分支全程持锁且不挂起：补发＋槽检查＋直投与任一补发
        // 的摘槽＋投递互斥，同类消息不可能逆序（审查 A-1）
        synchronized(volatileLock) {
            flushPendingLocked()
            if (pendingVolatile.containsKey(key)) {
                // 同类已有待补发值：新值覆盖槽（合流），不直投
                // ——见类注释的同类保序不变量
                pendingVolatile[key] = msg
            } else if (!flow.tryEmit(msg)) {
                // 缓冲满：不丢弃，进槽等补发（最新帧必达的承重点）
                pendingVolatile[key] = msg
            }
        }
    }

    /**
     * 在 [scope] 内收集并逐条交付；每交付一条后立即补发一次待
     * 补发槽——收集者排空缓冲的每一步都腾出位置，突发末尾滞留
     * 的最新帧靠这一笔达成，不必等下一条生产消息。
     */
    fun observe(
        scope: CoroutineScope, onMessage: suspend (EngineMessage) -> Unit
    ): Job = scope.launch {
        flow.collect { message ->
            onMessage(message)
            synchronized(volatileLock) { flushPendingLocked() }
        }
    }

    /**
     * 把待补发槽内的最新值逐类送进 [flow]。**调用方必须持
     * [volatileLock]**：摘槽与随后的 tryEmit 必须与易变分支的直
     * 投互斥，否则摘槽后槽位瞬时为空、并发新值的直投会越过本
     * 次投递的旧值（审查 A-1）。摘槽用 remove(key, value) 原子
     * 比对：摘不下来说明该值已被更新值覆盖（跳过，新值自有其
     * 补发机会）；送不进（缓冲仍满）则放回槽位，期间若已有同
     * 类新值入槽则以新值为准（旧值已被合流取代）。全程只用
     * tryEmit，不挂起。
     */
    private fun flushPendingLocked() {
        if (pendingVolatile.isEmpty()) return
        for ((key, msg) in pendingVolatile.entries.toList()) {
            if (!pendingVolatile.remove(key, msg)) continue
            if (!flow.tryEmit(msg)) {
                pendingVolatile.putIfAbsent(key, msg)
            }
        }
    }
}
