// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import java.util.concurrent.ConcurrentLinkedQueue

interface RimeLifecycle {
    val currentState: State
    val lifecycleScope: CoroutineScope

    enum class State {
        STARTING, READY, STOPPING, STOPPED,
    }
}

interface RimeLifecycleOwner {
    val lifecycle: RimeLifecycle
}

val RimeLifecycleOwner.lifecycleScope: CoroutineScope
    get() = lifecycle.lifecycleScope

internal fun interface LifecycleObserver {
    fun onChanged(state: RimeLifecycle.State)
}

class RimeLifecycleRegistry : RimeLifecycle {
    private val observers = ConcurrentLinkedQueue<LifecycleObserver>()
    @Volatile
    private var state = RimeLifecycle.State.STOPPED

    override val currentState: RimeLifecycle.State
        get() = state

    override val lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob())

    fun emitState(newState: RimeLifecycle.State) {
        synchronized(this) { state = newState }
        observers.forEach { it.onChanged(newState) }
        if (newState.ordinal >= RimeLifecycle.State.STOPPING.ordinal) {
            lifecycleScope.coroutineContext.cancelChildren()
        }
    }

    internal fun addObserver(observer: LifecycleObserver) {
        observers.add(observer)
    }

    internal fun removeObserver(observer: LifecycleObserver) {
        observers.remove(observer)
    }
}

suspend fun <T> RimeLifecycle.whenReady(block: suspend CoroutineScope.() -> T): T {
    if (currentState == RimeLifecycle.State.READY) {
        return block(lifecycleScope)
    }
    if (currentState == RimeLifecycle.State.STOPPED) {
        // 引擎已完全停止且不会自行再启动：立即失败，
        // 否则等待方会永久挂起（旧实现只等 READY 一个信号）。
        throw RimeStoppedException()
    }
    val registry = this as? RimeLifecycleRegistry
        ?: throw IllegalStateException("whenReady requires a RimeLifecycleRegistry")
    // STOPPED 后紧跟一次重启时，该 STOPPED 不是终态（E-M5）：STOPPING 期
    // createSession 登记的 startupPending 由 finalize 末尾消费、同步重走
    // startup——等待方若在 STOPPED 上直接失败，停机窗口内建的会话注定
    // 失败一次。失败判定后复检当前态：已在重走启动（STARTING／STOPPING／
    // READY）时继续等下一轮信号；仍为 STOPPED 时才是真终态，再抛。
    // 每轮都挂起等信号，不空转；重启本身失败会再发 STOPPED，届时再抛。
    //
    // 信号用 CompletableDeferred 承接：观察者只 complete 它，不直接
    // resume 等待方手存的 Continuation——后者恢复后抛错时，异常会越过
    // 等待协程的 Deferred、直接逃到外层作用域（waiterFailsWhenStopped-
    // WithoutRestart 实证：RimeStoppedException 从 runBlocking 逃出，
    // 而不是由 deferred.await() 捕获）；await() 是库管理的挂起，之后
    // 抛错走正常协程失败路径，可被调用方按单条失败捕获。
    while (true) {
        val signal = CompletableDeferred<RimeLifecycle.State>()
        val observer = LifecycleObserver { s ->
            if (s == RimeLifecycle.State.READY || s == RimeLifecycle.State.STOPPED) {
                signal.complete(s)
            }
        }
        registry.addObserver(observer)
        try {
            // READY/STOPPED 可能在注册观察者之前已发出：复检当前态，
            // 已是终态信号时直接完成，避免挂死在不会再来的信号上
            // （complete 已完成时是 no-op，不会覆盖先到的信号值）。
            when (val cur = currentState) {
                RimeLifecycle.State.READY -> signal.complete(RimeLifecycle.State.READY)
                RimeLifecycle.State.STOPPED -> signal.complete(RimeLifecycle.State.STOPPED)
                else -> Unit
            }
            when (signal.await()) {
                RimeLifecycle.State.READY -> return block(lifecycleScope)
                RimeLifecycle.State.STOPPED -> {
                    if (currentState != RimeLifecycle.State.STOPPED) continue
                    throw RimeStoppedException()
                }
                else -> continue
            }
        } finally {
            registry.removeObserver(observer)
        }
    }
}

/**
 * 引擎停止/任务被丢弃时抛出，等待方应按本次操作失败处理。
 * 默认文案对应 [whenReady] 的「就绪之前已被完全停止」场景；dispatcher
 * 排空丢弃已就绪引擎的在途任务时文案不符（引擎早已就绪），由转抛点
 * 传入丢弃语义的文案，避免日志排查被误导。
 */
class RimeStoppedException(
    message: String = "Rime stopped before becoming ready",
) : IllegalStateException(message)
