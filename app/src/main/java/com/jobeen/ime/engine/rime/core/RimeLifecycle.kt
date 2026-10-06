// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

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
    val signalled = AtomicBoolean(false)
    val failed = AtomicBoolean(false)
    val continuation = AtomicReference<Continuation<Unit>?>()
    val observer = LifecycleObserver { s ->
        when (s) {
            RimeLifecycle.State.READY -> {
                signalled.set(true)
                continuation.getAndSet(null)?.resume(Unit)
            }
            RimeLifecycle.State.STOPPED -> {
                // 等待期间引擎被完全停掉：唤醒等待方并让它失败，
                // 而不是无限期等一个不会再来的 READY。
                failed.set(true)
                continuation.getAndSet(null)?.resume(Unit)
            }
            else -> Unit
        }
    }
    registry.addObserver(observer)
    try {
        // READY/STOPPED may have been emitted between the initial check and observer registration.
        when (currentState) {
            RimeLifecycle.State.READY -> {
                signalled.set(true)
                continuation.getAndSet(null)?.resume(Unit)
            }
            RimeLifecycle.State.STOPPED -> {
                failed.set(true)
                continuation.getAndSet(null)?.resume(Unit)
            }
            else -> Unit
        }
        suspendCancellableCoroutine { cont ->
            continuation.set(cont)
            if ((signalled.get() || failed.get()) && continuation.compareAndSet(cont, null)) {
                cont.resume(Unit)
            }
        }
        if (failed.get()) {
            throw RimeStoppedException()
        }
        return block(lifecycleScope)
    } finally {
        registry.removeObserver(observer)
    }
}

/** 引擎在就绪之前已被完全停止时由 [whenReady] 抛出，等待方应按本次操作失败处理。 */
class RimeStoppedException : IllegalStateException("Rime stopped before becoming ready")
