package com.jobeen.ime

import com.jobeen.ime.engine.EngineMessageChannel
import com.jobeen.ime.engine.data.EngineMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎消息通道合流的回归规格（第十六批，由 VolatileDropReproTest
 * 的复现实验转正——复刻管道换成生产 [EngineMessageChannel] 本体）：
 *
 * 旧语义下易变类消息走 tryEmit、缓冲满静默丢弃：一串快打的最后
 * 一批组字被丢后无下一批自愈，胶囊永久滞留旧值（wode 截图）。
 * 新语义：易变类最新值合流——中间帧可被同类更新值合流掉，同类
 * 严格保序，**最新一帧必达**；控制类（Commit 等）语义不变
 * （emit 不丢）。
 *
 * 场景与原复现逐项对应：收集者收到哨兵后挂起（模拟 Main 迟
 * 滞），生产者连发 99 条易变消息——第 64 条是中途组字 STALE，
 * 第 99 条是最后一批组字 FULL。缓冲 64 槽装满后，FULL 只能进
 * 待补发槽；收集者恢复排空缓冲时必须被补发。
 */
class EngineMessageChannelTest {

    private fun preedit(text: String) = EngineMessage.DynamicPreedit(
        listOf(
            EngineMessage.DynamicPreedit.DynamicPreeditItem(
                text, EngineMessage.DynamicPreedit.DynamicPreeditType.Normal
            )
        )
    )

    /** 填充易变消息：模拟真实扇出（每键多条易变消息），三种易变类轮换。 */
    private fun filler(i: Int): EngineMessage = when (i % 3) {
        0 -> EngineMessage.Composition(preedit = "f$i", cursorPos = 0)
        1 -> EngineMessage.Candidates(list = emptyList(), highlighted = 0, page = 0)
        else -> EngineMessage.CandidateMenu(candidates = emptyList())
    }

    private fun burstSequence(): List<EngineMessage> = (0 until 99).map { i ->
        when (i) {
            63 -> preedit(STALE)
            98 -> preedit(FULL)
            else -> filler(i)
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!cond()) delay(1)
        }
    }

    @Test
    fun burstWhileCollectorStalled_finalPreeditStillDelivered() = runBlocking {
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receivedCount = AtomicInteger(0)
        val receivedPreedits = CopyOnWriteArrayList<String>()
        val collectorBusy = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        try {
            // 收集者：收到第一条（哨兵）后挂起，模拟 Main 被堵住
            channel.observe(scope) { msg ->
                receivedCount.incrementAndGet()
                if (msg is EngineMessage.DynamicPreedit) {
                    receivedPreedits += msg.preedits.first().text
                }
                if (receivedCount.get() == 1) {
                    collectorBusy.complete(Unit)
                    releaseCollector.await()
                }
            }

            // 等订阅建立后再发哨兵：无订阅者时易变消息直投即逝
            awaitUntil { channel.subscriptionCount.value >= 1 }
            channel.emit(EngineMessage.Composition("sentinel", 0))
            collectorBusy.await()

            // 生产者快速连发 99 条易变消息（易变路径从不挂起）
            burstSequence().forEach { channel.emit(it) }

            // 释放收集者：排空缓冲（哨兵 + 64 条入缓冲）后，待补
            // 发槽内每类最新值被逐个补发：Composition / Candidates /
            // CandidateMenu 各一条 + DynamicPreedit 的 FULL
            releaseCollector.complete(Unit)
            awaitUntil { receivedCount.get() == 69 }

            // 组字按序到达且最后一批是 FULL——不再丢失、不再滞留
            assertEquals(listOf(STALE, FULL), receivedPreedits.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun slowSendWithoutStall_allDeliveredInOrder() = runBlocking {
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receivedCount = AtomicInteger(0)
        val receivedPreedits = CopyOnWriteArrayList<String>()
        try {
            channel.observe(scope) { msg ->
                receivedCount.incrementAndGet()
                if (msg is EngineMessage.DynamicPreedit) {
                    receivedPreedits += msg.preedits.first().text
                }
            }

            awaitUntil { channel.subscriptionCount.value >= 1 }
            val all = listOf(EngineMessage.Composition("sentinel", 0)) + burstSequence()
            for ((idx, msg) in all.withIndex()) {
                channel.emit(msg)
                // 慢速：等收集者确认收到本条再发下一条，缓冲不积压
                awaitUntil { receivedCount.get() == idx + 1 }
            }

            // 不溢出时合流不介入：100 条全达、组字两批按序到达
            assertEquals(100, receivedCount.get())
            assertEquals(listOf(STALE, FULL), receivedPreedits.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun controlCommitDuringBurst_neverDropped() = runBlocking {
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val received = CopyOnWriteArrayList<EngineMessage>()
        val collectorBusy = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        try {
            channel.observe(scope) { msg ->
                received += msg
                if (received.size == 1) {
                    collectorBusy.complete(Unit)
                    releaseCollector.await()
                }
            }

            awaitUntil { channel.subscriptionCount.value >= 1 }
            channel.emit(EngineMessage.Composition("sentinel", 0))
            collectorBusy.await()

            // 易变突发灌满缓冲后，控制类 Commit 的 emit 挂起等待
            // （旧语义如此、新语义不变），收集者恢复后必达
            val producer = async(Dispatchers.Default) {
                burstSequence().forEach { channel.emit(it) }
                channel.emit(EngineMessage.Commit("已上屏"))
            }
            // 给生产者时间灌满缓冲并挂在 Commit 的 emit 上
            delay(200)
            assertFalse(producer.isCompleted)

            releaseCollector.complete(Unit)
            withTimeout(10_000) { producer.await() }
            awaitUntil { received.filterIsInstance<EngineMessage.Commit>().size == 1 }

            // Commit 恰达一次；同批组字最新帧 FULL 亦达
            val preedits = received.filterIsInstance<EngineMessage.DynamicPreedit>()
                .map { it.preedits.first().text }
            assertEquals(listOf(STALE, FULL), preedits)
            assertTrue(EngineMessage.Commit("已上屏") in received)
        } finally {
            scope.cancel()
        }
    }

    /** 收集者挂起在哨兵上的测试台：灌突发后由 [StallRig.release] 放行排空。 */
    private class StallRig(
        val channel: EngineMessageChannel,
        private val scope: CoroutineScope,
        val receivedCount: AtomicInteger,
        val receivedPreedits: CopyOnWriteArrayList<String>,
        val receivedCommits: CopyOnWriteArrayList<String>,
        private val releaseGate: CompletableDeferred<Unit>
    ) {
        fun release() {
            releaseGate.complete(Unit)
        }

        fun close() {
            scope.cancel()
        }
    }

    private suspend fun startStalledCollector(): StallRig {
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receivedCount = AtomicInteger(0)
        val receivedPreedits = CopyOnWriteArrayList<String>()
        val receivedCommits = CopyOnWriteArrayList<String>()
        val collectorBusy = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        channel.observe(scope) { msg ->
            receivedCount.incrementAndGet()
            when (msg) {
                is EngineMessage.DynamicPreedit ->
                    receivedPreedits += msg.preedits.first().text
                is EngineMessage.Commit -> receivedCommits += msg.text
                else -> Unit
            }
            if (receivedCount.get() == 1) {
                collectorBusy.complete(Unit)
                releaseGate.await()
            }
        }
        awaitUntil { channel.subscriptionCount.value >= 1 }
        channel.emit(EngineMessage.Composition("sentinel", 0))
        collectorBusy.await()
        return StallRig(
            channel, scope, receivedCount, receivedPreedits,
            receivedCommits, releaseGate
        )
    }

    /**
     * A-2 回归（第十七批）：复位清槽。突发后最后一帧 FULL 滞留
     * 待补发槽；复位（clearPending）后放行收集者，FULL 这张复位
     * 前的陈旧快照不得被补发；清槽不得伤及通道本身——其后新组
     * 字与控制类 Commit 照常到达。空槽时调用 clearPending 无害。
     */
    @Test
    fun clearPendingAfterStall_stalePreeditNeverFlushed() = runBlocking {
        val rig = startStalledCollector()
        try {
            // 空槽清槽：无害、不改变后续行为
            rig.channel.clearPending()

            burstSequence().forEach { rig.channel.emit(it) }
            // FULL 已进待补发槽（尚未交付），复位清槽
            assertEquals(emptyList<String>(), rig.receivedPreedits.toList())
            rig.channel.clearPending()

            rig.release()
            // 只交付哨兵＋64 条入缓冲（含 STALE），槽内的 FULL 不出现
            awaitUntil { rig.receivedCount.get() == 65 }
            assertEquals(listOf(STALE), rig.receivedPreedits.toList())

            // 清槽不伤通道：复位后的新组字与 Commit 照常到达
            rig.channel.emit(preedit("xin de"))
            rig.channel.emit(EngineMessage.Commit("已上屏"))
            awaitUntil { rig.receivedCommits.size == 1 }
            assertEquals(listOf(STALE, "xin de"), rig.receivedPreedits.toList())
            assertEquals(listOf("已上屏"), rig.receivedCommits.toList())
        } finally {
            rig.close()
        }
    }

    /**
     * 临时取证计数（第十七批）：拥塞下未能直投的易变帧即旧实现
     * 会静默丢弃的丢弃等价事件，必须被计数——99 条突发中 64 条
     * 入缓冲、其余 35 条（进槽或覆盖槽）逐条计数；收集侧交付的
     * 组字帧（STALE＋FULL）另行计数，供真机日志对账。
     */
    @Test
    fun dropDiagnostics_countsDropEquivalentsAndDeliveries() = runBlocking {
        val rig = startStalledCollector()
        try {
            assertTrue(rig.channel.dropDiagnosticsEnabled)
            burstSequence().forEach { rig.channel.emit(it) }
            assertEquals(35L, rig.channel.volatileDropEquivalentCount)

            rig.release()
            awaitUntil { rig.receivedCount.get() == 69 }
            assertEquals(listOf(STALE, FULL), rig.receivedPreedits.toList())
            assertEquals(2L, rig.channel.deliveredDynamicPreeditCount)
        } finally {
            rig.close()
        }
    }

    /** 取证开关关闭时两个计数都停摆，且合流交付语义本身不变。 */
    @Test
    fun dropDiagnosticsDisabled_countsNothingAndDeliveryUnchanged() = runBlocking {
        val channel = EngineMessageChannel()
        channel.dropDiagnosticsEnabled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receivedCount = AtomicInteger(0)
        val receivedPreedits = CopyOnWriteArrayList<String>()
        val collectorBusy = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        try {
            channel.observe(scope) { msg ->
                receivedCount.incrementAndGet()
                if (msg is EngineMessage.DynamicPreedit) {
                    receivedPreedits += msg.preedits.first().text
                }
                if (receivedCount.get() == 1) {
                    collectorBusy.complete(Unit)
                    releaseCollector.await()
                }
            }

            awaitUntil { channel.subscriptionCount.value >= 1 }
            channel.emit(EngineMessage.Composition("sentinel", 0))
            collectorBusy.await()
            burstSequence().forEach { channel.emit(it) }
            assertEquals(0L, channel.volatileDropEquivalentCount)

            releaseCollector.complete(Unit)
            awaitUntil { receivedCount.get() == 69 }
            assertEquals(listOf(STALE, FULL), receivedPreedits.toList())
            assertEquals(0L, channel.deliveredDynamicPreeditCount)
        } finally {
            scope.cancel()
        }
    }

    /**
     * A-1 回归（第十六批收尾审查）：补发的「摘槽＋tryEmit」与易
     * 变分支的直投若不互斥，摘槽后槽位瞬时为空，窗口内并发到达
     * 的同类新值会直投成功、随后旧值也被投递，收集侧收到
     * [新值, 旧值] 逆序对且旧值滞留为最终显示（失败形态正是
     * wode 症状）。双线程交错：收集者恢复排空的同时，生产侧在
     * 排空开始的瞬间并发直投同类新值；多轮重复，断言同类消息
     * 绝不逆序、收集侧最终值恒为最新值。
     */
    @Test
    fun flushRacingDirectEmit_sameClassNeverReversed() = runBlocking {
        repeat(RACE_ROUNDS) { round -> raceRound(round) }
    }

    /** 一轮：灌满缓冲＋旧组字进槽 → 排空与同类新值直投并发交错。 */
    private suspend fun raceRound(round: Int) {
        val oldText = "OLD-$round"
        val newText = "NEW-$round"
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deliveredCount = AtomicInteger(0)
        val receivedPreedits = CopyOnWriteArrayList<String>()
        val collectorBusy = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        try {
            channel.observe(scope) { msg ->
                val n = deliveredCount.incrementAndGet()
                if (msg is EngineMessage.DynamicPreedit) {
                    receivedPreedits += msg.preedits.first().text
                }
                if (n == 1) {
                    collectorBusy.complete(Unit)
                    releaseCollector.await()
                }
            }

            awaitUntil { channel.subscriptionCount.value >= 1 }
            channel.emit(EngineMessage.Composition("sentinel", 0))
            collectorBusy.await()

            // 灌满缓冲（64 条入缓冲、其余覆盖 Composition 槽），
            // 再发一条组字：直投失败、进 DynamicPreedit 待补发槽
            repeat(70) { channel.emit(EngineMessage.Composition("f$it", 0)) }
            channel.emit(preedit(oldText))

            // 生产侧：收集者开始排空（第 2 条交付进入 onMessage）
            // 的瞬间并发 emit 同类新值，与收集侧补发的摘槽/投递
            // 交错——正是 A-1 的逆序窗口
            val producer = scope.async(Dispatchers.Default) {
                val deadline = System.nanoTime() + RACE_SPIN_NANOS
                while (deliveredCount.get() < 2 &&
                    System.nanoTime() < deadline
                ) {
                    Thread.yield()
                }
                channel.emit(preedit(newText))
            }

            releaseCollector.complete(Unit)
            withTimeout(10_000) { producer.await() }
            // 等新值到达且交付计数稳定（本轮全部交付完毕）
            awaitUntil { newText in receivedPreedits }
            awaitStableCount(deliveredCount)

            // 绝不逆序：旧值若未被新值合流掉，必须排在新值之前；
            // 收集侧最终值恒为最新值
            val preedits = receivedPreedits.toList()
            assertEquals("round $round: $preedits", newText, preedits.last())
            val oldIdx = preedits.indexOf(oldText)
            val newIdx = preedits.indexOf(newText)
            assertTrue(
                "round $round: $preedits", oldIdx == -1 || oldIdx < newIdx
            )
        } finally {
            scope.cancel()
        }
    }

    /** 等交付计数连续数次采样不变（本轮排空收尾的判据）。 */
    private suspend fun awaitStableCount(counter: AtomicInteger) {
        withTimeout(10_000) {
            var last = counter.get()
            var stablePolls = 0
            while (stablePolls < 3) {
                delay(10)
                val now = counter.get()
                if (now == last) {
                    stablePolls++
                } else {
                    stablePolls = 0
                    last = now
                }
            }
        }
    }

    private companion object {
        const val STALE = "wo"
        const val FULL = "wo de wo de"
        const val RACE_ROUNDS = 50
        const val RACE_SPIN_NANOS = 5_000_000_000L
    }
}
