package com.jobeen.ime.engine

import com.jobeen.ime.engine.data.EngineMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

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
 *
 * 复位：引擎复位（resetState/resetComposition 汇入的同一复位
 * 点）调用 [clearPending] 清空待补发槽——复位前的滞留值是陈旧
 * 快照，不应抢在复位后的新消息之前补发到收集侧（审查 A-2）。
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

    // —— 临时取证设施（第十七批）：wode 根因真机坐实 ——
    //
    // wode 的根因（旧实现易变消息在缓冲满时被 tryEmit 静默丢弃、
    // 最后一帧永不达）至今只有「主机确定性复现＋真机签名吻合」
    // 的推断级证据，无真机日志实锤。本设施在**同一事件点**计数：
    // 易变帧因拥塞未能直投（进待补发槽、或覆盖槽内同类旧值）——
    // 旧实现下同一时刻的 tryEmit 失败即被静默丢弃，故称「丢弃
    // 等价事件」。真机日志里若出现 dropEqTotal>0、且其后收集侧
    // 组字帧计数（deliveredPreedit）持续增长到完整末帧，即坐实
    // 「旧版会丢的帧在新版全部到达」。
    //
    // 隐私红线：日志只记消息类型、长度量级（字符数/条数）与计
    // 数，**绝不记组字/候选原文**。开关默认开、随预览版上机取
    // 证；坐实后下一批连同本段一并移除。

    /** 丢弃等价事件计数开关（临时取证，默认开）。 */
    @Volatile
    var dropDiagnosticsEnabled: Boolean = true

    private val dropEquivalentCounter = AtomicLong(0)
    private val deliveredPreeditCounter = AtomicLong(0)

    /** 通道开启以来的丢弃等价事件累计数（取证对账/测试用）。 */
    internal val volatileDropEquivalentCount: Long
        get() = dropEquivalentCounter.get()

    /** 收集侧已交付的组字（DynamicPreedit）帧累计数（取证对账/测试用）。 */
    internal val deliveredDynamicPreeditCount: Long
        get() = deliveredPreeditCounter.get()

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
                noteDropEquivalent(msg)
            } else if (!flow.tryEmit(msg)) {
                // 缓冲满：不丢弃，进槽等补发（最新帧必达的承重点）
                pendingVolatile[key] = msg
                noteDropEquivalent(msg)
            }
        }
    }

    /**
     * 清空待补发槽（复位专用）：组字/状态复位后，复位前滞留的易
     * 变值已是陈旧快照，不应再被下一次 emit 的补发抢先送到收集
     * 侧（同批内最终态虽由新消息决定，仍可多一闪旧帧——审查
     * A-2）。只清槽：不动 [flow] 本身（已入缓冲的消息按原顺序
     * 交付）、不动控制类语义。取 [volatileLock] 与易变路径的摘
     * 槽/入槽串行化，非挂起、可在任意线程调用。
     */
    fun clearPending() {
        synchronized(volatileLock) { pendingVolatile.clear() }
    }

    /**
     * 记一次丢弃等价事件（临时取证，见类内取证段注释）：只记类
     * 型、长度量级与累计计数，绝不记原文。调用方须持
     * [volatileLock]（在 [emit] 易变分支的两个入槽点调用）。
     */
    private fun noteDropEquivalent(msg: EngineMessage) {
        if (!dropDiagnosticsEnabled) return
        val total = dropEquivalentCounter.incrementAndGet()
        Timber.d(
            "volatileDropEq type=%s size=%d dropEqTotal=%d deliveredPreedit=%d",
            msg.javaClass.simpleName, msg.sizeHint(), total,
            deliveredPreeditCounter.get()
        )
    }

    /** 长度量级：文本类取字符数、列表类取条数。只供取证日志，绝不含原文。 */
    private fun EngineMessage.sizeHint(): Int = when (this) {
        is EngineMessage.Composition -> preedit.length
        is EngineMessage.InlinePreedit -> preedit.length
        is EngineMessage.DynamicPreedit -> preedits.sumOf { it.text.length }
        is EngineMessage.Candidates -> list.size
        is EngineMessage.CandidateMenu -> candidates.size
        is EngineMessage.PossibleCandidatePinYin -> possibleCandidatePinYins.size
        else -> 0
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
            // 取证对账：收集侧实际交付的组字帧计数（多收集者时为
            // 各收集者的交付总和，真机判读只看其随时间的增长与
            // 末帧完整性，不要求与发出侧逐一相等）
            if (dropDiagnosticsEnabled &&
                message is EngineMessage.DynamicPreedit
            ) {
                deliveredPreeditCounter.incrementAndGet()
            }
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
