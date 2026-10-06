package com.jobeen.ime.engine.rime.core

import com.jobeen.ime.engine.rime.core.Rime.Companion.handleMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class RimeDispatcher(
    private val controller: RimeController,
    private val onStartupFailed: (Throwable) -> Unit = {},
) : CoroutineDispatcher() {
    interface RimeController {
        fun nativeStartup()

        fun nativeFinalize()
    }

    class WrappedRunnable(
        val runnable: Runnable,
        private val name: String? = null,
    ) : Runnable by runnable {
        private val time = System.currentTimeMillis()
        var started = false
            private set

        private val delta
            get() = System.currentTimeMillis() - time

        override fun run() {
            if (delta > JOB_WAITING_LIMIT) {
                Timber.w("${toString()} has waited $delta ms to get run since created!")
            }
            started = true
            runnable.run()
        }

        override fun toString(): String = "WrappedRunnable[${name ?: hashCode()}]"

        companion object {
            val Empty = WrappedRunnable({}, "Empty")
        }
    }

    companion object {
        private const val JOB_WAITING_LIMIT = 2000L // ms
    }

    private val internalDispatcher = Executors.newSingleThreadExecutor {
        Thread(it, "rime-main")
    }.asCoroutineDispatcher()

    // SupervisorJob：一次启动失败不能毒化作用域，否则后续 start() 的 launch
    // 会被父 Job 的取消状态静默吞掉，引擎再也起不来
    private val internalScope = CoroutineScope(internalDispatcher + SupervisorJob())

    private val mutex = Mutex()

    private val queue = LinkedBlockingQueue<WrappedRunnable>()

    private val isRunning = AtomicBoolean(false)

    /**
     * Start the dispatcher
     * This function returns immediately
     */
    fun start() {
        Timber.d("RimeDispatcher start()")
        internalScope.launch {
            mutex.withLock {
                if (isRunning.compareAndSet(false, true)) {
                    Timber.d("nativeStartup()")
                    try {
                        controller.nativeStartup()
                    } catch (t: Throwable) {
                        // 启动失败必须复位并显形：isRunning 留在 true 会让
                        // dispatch() 继续把任务塞进无人消费的队列、按键永久
                        // 挂起。复位后回调把生命周期打回 STOPPED，等待方立即
                        // 失败、上层可以重新 startup() 重试。
                        Timber.e(t, "nativeStartup() failed; dispatcher reset to stopped")
                        isRunning.set(false)
                        val dropped = queue.size
                        queue.clear()
                        if (dropped > 0) {
                            Timber.w("Dropped $dropped queued job(s) after startup failure")
                        }
                        onStartupFailed(t)
                        return@withLock
                    }
                    try {
                        while (isActive && isRunning.get()) {
                            val block = queue.take()
                            try {
                                block.run()
                            } catch (t: Throwable) {
                                Timber.e(t, "Rime job failed")
                            }
                        }
                    } finally {
                        isRunning.set(false)
                        try {
                            Timber.i("nativeFinalize()")
                            controller.nativeFinalize()
                        } catch (t: Throwable) {
                            Timber.e(t, "nativeFinalize() failed")
                        }
                    }
                }
            }
        }
    }

    /**
     * Stop the dispatcher
     * This function blocks until fully stopped
     */
    fun stop(): List<Runnable> {
        Timber.i("RimeDispatcher stop()")
        return if (isRunning.compareAndSet(true, false)) {
            // 正常路径只是入队一个 Empty 唤醒消费循环退出 + 持锁排空队列，
            // 瞬时完成。加 2 秒超时兜底：异常时（锁被长任务占住等）不再让
            // runBlocking 无限等、把引擎销毁流程卡死；超时返回空表即可，
            // 停止流程里的残余任务本就可丢弃。
            runCatching {
                runBlocking {
                    withTimeoutOrNull(2_000L) {
                        queue.offer(WrappedRunnable.Empty)
                        mutex.withLock {
                            val rest = mutableListOf<WrappedRunnable>()
                            queue.drainTo(rest)
                            rest
                        }
                    }
                }
            }.getOrNull() ?: emptyList()
        } else {
            emptyList()
        }
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (!isRunning.get()) {
            throw IllegalStateException("Dispatcher is not in running state!")
        }
        val wrapped = WrappedRunnable(block)
        queue.offer(wrapped)
        // 入队后复查：stop() 可能在检查与入队之间执行，避免任务入队后无人消费导致挂起
        if (!isRunning.get()) {
            queue.remove(wrapped)
            throw IllegalStateException("Dispatcher is not in running state!")
        }
    }
}