package com.ninthsoft.ime.input.speech

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import timber.log.Timber


abstract class RenderView @JvmOverloads constructor(
    context: Context?, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    private var isStartAnim = false
    private var renderThread: RenderThread? = null

    protected abstract fun doDrawBackground(canvas: Canvas?)

    protected abstract fun onRender(canvas: Canvas?, millisPassed: Long)

    /**
     * 子类返回 true 表示当前需要进入冬眠，本帧将跳过 Canvas 锁定，
     * 改为通过 [awaitWakeUp] 阻塞等待唤醒事件。这样可确保 Canvas 不会被
     * 长时间持有导致冻帧或与 onPause/onWindowFocusChanged 产生死锁。
     */
    protected open fun shouldIdleWait(): Boolean = false

    /**
     * 在不持有 Canvas 的前提下阻塞，直到外部唤醒（例如有新音量到达）。
     * 子类应使用自身的 wait/notify 队列实现。
     */
    protected open fun awaitWakeUp() {
        try {
            Thread.sleep(RENDER_FRAME_INTERVAL_MS)
        } catch (ignored: InterruptedException) {
        }
    }

    init {
        holder.addCallback(this)
    }

    private class RenderThread(renderView: RenderView?) : Thread("RenderThread") {
        private val renderView: WeakReference<RenderView?> = WeakReference<RenderView?>(renderView)

        @Volatile
        var running = false

        @Volatile
        var destroyed = false

        @Volatile
        var isPause = false

        val surfaceHolder: SurfaceHolder?
            get() {
                val rv = renderView.get()
                return rv?.holder
            }

        fun getRenderView(): RenderView? {
            return renderView.get()
        }

        fun setRun(isRun: Boolean) {
            this.running = isRun
        }

        override fun run() {
            val startAt = System.currentTimeMillis()
            while (!destroyed) {
                // 仅在状态栅栏处持锁，绘制阶段不持有 surfaceLock 与 Canvas，
                // 这样 onRender 进入冬眠 wait() 时既不会阻塞 onPause/ onDestroy，
                // 也不会让 Canvas 长时间被锁住造成黑屏/ANR。
                surfaceLock.withLock {
                    while (isPause && !destroyed) {
                        try {
                            surfaceCondition.await()
                        } catch (ignored: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                if (destroyed || !running) {
                    try {
                        sleep(IDLE_SLEEP_TIME)
                    } catch (ignored: InterruptedException) {
                    }
                    continue
                }

                val holder = this.surfaceHolder
                val rv = getRenderView()
                if (holder == null || rv == null) {
                    running = false
                    continue
                }
                // 若子类标记冬眠，则跳过对 Canvas 的锁定，避免在 wait() 时持锁。
                if (rv.shouldIdleWait()) {
                    rv.awaitWakeUp()
                    continue
                }
                drawFrame(holder, rv, System.currentTimeMillis() - startAt)

                try {
                    sleep(IDLE_SLEEP_TIME)
                } catch (ignored: InterruptedException) {
                }
            }
        }

        /**
         * 单帧绘制：仅在本线程内同步执行，绝不在持 surfaceLock 状态下持有 Canvas。
         * 若 onRender 主动阻塞（例如进入冬眠），由子类保证 Canvas 已先行 flush。
         */
        private fun drawFrame(holder: SurfaceHolder, rv: RenderView, millisPassed: Long) {
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    rv.doDrawBackground(canvas)
                    if (rv.isStartAnim) {
                        rv.onRender(canvas, millisPassed)
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Render frame failed: ${rv.javaClass.simpleName}")
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        companion object {
            private const val IDLE_SLEEP_TIME: Long = 16
        }
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        renderThread = RenderThread(this)
        if (isStartAnim) startThread()
    }

    fun onResume() {
        surfaceLock.withLock {
            if (renderThread != null) {
                renderThread!!.isPause = false
                surfaceCondition.signalAll()
            }
        }
    }

    fun onPause() {
        surfaceLock.withLock {
            if (renderThread != null) renderThread!!.isPause = true
        }
    }

    override fun surfaceChanged(p0: SurfaceHolder, height: Int, p2: Int, p3: Int) {}

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        surfaceLock.withLock {
            if (renderThread != null) {
                renderThread!!.setRun(false)
                renderThread!!.destroyed = true
                renderThread!!.isPause = false
                surfaceCondition.signalAll()
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (hasWindowFocus && isStartAnim) startThread() else onPause()
    }

    fun startAnim() {
        isStartAnim = true
        startThread()
    }

    private fun startThread() {
        if (renderThread != null && !renderThread!!.running) {
            renderThread!!.setRun(true)
            surfaceLock.withLock {
                renderThread!!.isPause = false
                surfaceCondition.signalAll()
            }
            try {
                if (renderThread!!.state == Thread.State.NEW) renderThread!!.start()
            } catch (ignored: Exception) {
            }
        }
    }

    open fun stopAnim() {
        isStartAnim = false
        if (renderThread != null && renderThread!!.running) {
            renderThread!!.setRun(false)
        }
    }

    val isRunning: Boolean
        get() = renderThread != null && renderThread!!.running

    open fun release() {
        stopAnim()
        surfaceLock.withLock {
            if (renderThread != null) {
                renderThread!!.destroyed = true
                renderThread!!.isPause = false
                surfaceCondition.signalAll()
            }
        }
        // 仅移除回调，交由 Framework 管理 Surface 生命周期，避免 native 释放竞态
        runCatching { holder.removeCallback(this) }
    }

    companion object {
        private val surfaceLock = ReentrantLock()
        private val surfaceCondition = surfaceLock.newCondition()
        const val RENDER_FRAME_INTERVAL_MS: Long = 16
    }
}
