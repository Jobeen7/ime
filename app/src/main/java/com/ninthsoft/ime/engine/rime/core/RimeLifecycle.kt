// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

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
    val registry = this as? RimeLifecycleRegistry
        ?: throw IllegalStateException("whenReady requires a RimeLifecycleRegistry")
    val signalled = AtomicBoolean(false)
    val continuation = AtomicReference<Continuation<Unit>?>()
    val observer = LifecycleObserver { s ->
        if (s == RimeLifecycle.State.READY) {
            signalled.set(true)
            continuation.getAndSet(null)?.resume(Unit)
        }
    }
    registry.addObserver(observer)
    try {
        // READY may have been emitted between the initial check and observer registration.
        if (currentState == RimeLifecycle.State.READY) {
            signalled.set(true)
            continuation.getAndSet(null)?.resume(Unit)
        }
        suspendCancellableCoroutine { cont ->
            continuation.set(cont)
            if (signalled.get() && continuation.compareAndSet(cont, null)) {
                cont.resume(Unit)
            }
        }
        return block(lifecycleScope)
    } finally {
        registry.removeObserver(observer)
    }
}
