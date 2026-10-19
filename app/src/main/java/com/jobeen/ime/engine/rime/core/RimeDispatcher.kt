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

        /**
         * 派发时协程的 Job（dispatch 的 context 携带）。stop() 排空
         * 队列时用它把「任务被丢弃」翻译成协程取消——否则被丢弃的
         * DispatchedTask 永不运行，其 withContext 调用方永久挂起。
         */
        var job: kotlinx.coroutines.Job? = null

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
                        // 被丢弃任务的协程必须以取消收尾，不能让调用
                        // 方永久挂在 withContext 上（见 drainQueuedJobs）
                        val droppedTasks = drainQueuedJobs(
                            "RimeDispatcher startup failed before the job ran"
                        )
                        if (droppedTasks.isNotEmpty()) {
                            Timber.w("Dropped ${droppedTasks.size} queued job(s) after startup failure")
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
                        // 排空保证点：stop() 的持锁排空只是第一道——
                        // rime-main 被长任务（部署、词库导入导出）占住
                        // 超过其 2 秒超时时，stop() 拿不到本锁、返回
                        // 空表就走，残余任务会留在队列里：等待方挂到
                        // 下次 start()，而下次的消费循环会把这些陈旧
                        // 任务在新会话里真执行（旧按键打进新组字）。
                        // 消费循环的退出段是长任务结束后、dispatcher
                        // 被复用前唯一必经的串行点，故无论循环因何
                        // 退出（stop 置位、作用域取消），都在此（仍
                        // 持锁、nativeFinalize 之后）把队列排空，等待
                        // 方必被唤醒。drainTo 原子摘取，与 stop()、
                        // 启动失败两处排空不会重复摘同一任务。
                        drainQueuedJobs("RimeDispatcher stopped before the job ran")
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
                            // 被排空的任务永不执行：逐一取消其协程
                            // Job 并补 run，让挂在 withContext 上的调用
                            // 方以取消收尾，而不是永久挂起（消息消费
                            // 者一旦这样挂死，本进程内消息通道就此停
                            // 摆、无从恢复）。见 drainQueuedJobs。本处
                            // 只是第一道：超时拿不到锁时，残余任务由
                            // 消费循环退出段的排空兜底（start() 的
                            // finally），不会留在队列里。
                            drainQueuedJobs("RimeDispatcher stopped before the job ran")
                        }
                    }
                }
            }.getOrNull() ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 把队列里尚未执行的任务原子摘出并逐一收尾：取消其协程 Job 后
     * 补 run 一次。只 cancel 不够：cancel 只改 Job 状态，挂起方的
     * continuation 要等 DispatchedTask.run() 被调到时才会被以取消
     * 完成——任务已被丢弃、永不 run，不补 run 则等待方永久挂死；
     * 已取消态下 run 走快速失败路径完成等待方，不会真正执行任务
     * 体（无 Job 的裸任务不补 run，维持原样丢弃）。
     *
     * 三个调用点共用：启动失败（start 的 catch）、stop() 持锁排空、
     * 消费循环退出段（start 的 finally）。drainTo 原子摘取，同一
     * 任务只会被一个调用点摘走，不存在双重排空。
     */
    private fun drainQueuedJobs(cancelMessage: String): List<WrappedRunnable> {
        val dropped = mutableListOf<WrappedRunnable>()
        queue.drainTo(dropped)
        for (task in dropped) {
            task.job?.cancel(
                kotlinx.coroutines.CancellationException(cancelMessage)
            )
            if (task.job != null) runCatching { task.run() }
        }
        return dropped
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (!isRunning.get()) {
            throw IllegalStateException("Dispatcher is not in running state!")
        }
        val wrapped = WrappedRunnable(block)
        wrapped.job = context[kotlinx.coroutines.Job]
        queue.offer(wrapped)
        // 入队后复查：stop() 可能在检查与入队之间执行，避免任务入队后无人消费导致挂起。
        // 但不能无条件抛错——按任务此刻的归属分三种：
        // - 消费侧已接管（started=true，执行中或已执行完）：任务已被
        //   接受甚至已经生效，再抛 IllegalStateException 等于把已成
        //   功执行的操作上报为失败，静默返回；
        // - 仍在队列里（remove 成功）：无人会执行它，摘除并抛错，
        //   调用方立即按未运行口径降级，与入口检查一致；
        // - 既不在队列、started 又读到 false：任务已被
        //   drainQueuedJobs 摘走（started 非 volatile，消费线程的写
        //   对本线程不保证可见，故以 remove 的原子结果为准），其
        //   「取消 Job＋补 run」会让等待方以取消收尾，派发侧不再
        //   叠加一个假失败。
        if (!isRunning.get()) {
            if (wrapped.started) return
            if (queue.remove(wrapped)) {
                throw IllegalStateException("Dispatcher is not in running state!")
            }
        }
    }
}