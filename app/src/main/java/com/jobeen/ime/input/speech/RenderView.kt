package com.jobeen.ime.input.speech

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
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
                // 宿主视图已被回收：本线程再无绘制对象（surfaceHolder 也取
                // 不到），直接退出，不再 16ms 空转轮询
                val rv0 = renderView.get()
                if (rv0 == null) {
                    return
                }
                // 仅在状态栅栏处持锁，绘制阶段不持有 surfaceLock 与 Canvas，
                // 这样 onRender 进入冬眠 wait() 时既不会阻塞 onPause/ onDestroy，
                // 也不会让 Canvas 长时间被锁住造成黑屏/ANR。
                rv0.surfaceLock.withLock {
                    // 暂停或已停动画时都阻塞等待信号，不再 16ms 轮询空转
                    // （startThread/onResume/surfaceDestroyed 都会 signalAll）
                    while ((isPause || !running) && !destroyed) {
                        try {
                            rv0.surfaceCondition.await()
                        } catch (ignored: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                if (destroyed || !running) {
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

                // Choreographer 定帧：请求下一帧 vsync 后阻塞等它到达，
                // 替代 Thread.sleep(16) 的自由跑定帧（帧率与显示刷新对齐，
                // 相位不再漂移）。await 的超时片只用于复检 destroyed；
                // 无 vsync（熄屏等）时停在等待里，不绘制、不空转。
                val tickBefore = rv.frameTickCount
                rv.requestNextFrame()
                while (!destroyed && rv.frameTickCount == tickBefore) {
                    rv.awaitFrameTick(tickBefore)
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

    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        // startThread() 可能已在 surface 有效时重建过线程，这里只补建，避免泄漏出第二个绘制线程
        val rt = renderThread
        if (rt == null || rt.destroyed || rt.state == Thread.State.TERMINATED) {
            renderThread = RenderThread(this)
        }
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
        pokeFramePacer()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (hasWindowFocus && isStartAnim) startThread() else onPause()
    }

    fun startAnim() {
        isStartAnim = true
        startThread()
    }

    private fun startThread() {
        var rt = renderThread
        // 旧线程可能已随 surface 销毁而死亡（destroyed/TERMINATED），此时 setRun/唤醒都是空操作，
        // 必须丢弃，否则动画静默起不来（偶尔没有动画的根因）
        if (rt != null && (rt.destroyed || rt.state == Thread.State.TERMINATED)) {
            renderThread = null
            rt = null
        }
        if (rt == null) {
            // surface 有效时直接重建线程；surface 尚未就绪则等待 surfaceCreated 回调重建
            val surface = try { holder?.surface } catch (_: Throwable) { null }
            if (surface != null && surface.isValid) {
                rt = RenderThread(this).also { renderThread = it }
            } else {
                return
            }
        }
        if (!rt.running) {
            rt.setRun(true)
            surfaceLock.withLock {
                rt.isPause = false
                surfaceCondition.signalAll()
            }
            try {
                if (rt.state == Thread.State.NEW) rt.start()
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
        pokeFramePacer()
        // 仅移除回调，交由 Framework 管理 Surface 生命周期，避免 native 释放竞态
        runCatching { holder.removeCallback(this) }
    }

    // ── Choreographer 定帧 ────────────────────────────────────
    // 渲染线程每画完一帧，经主线程 Choreographer 请求一次 vsync 回调，
    // 回调推进 frameTickCount 后才画下一帧。等待期间线程阻塞在条件
    // 变量上（await 会释放 framePacerLock），不持 Canvas、不空转。
    private val framePacerLock = ReentrantLock()
    private val framePacerCondition = framePacerLock.newCondition()

    @Volatile
    internal var frameTickCount = 0L
        private set

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val vsyncCallback = Choreographer.FrameCallback {
        framePacerLock.withLock {
            frameTickCount++
            framePacerCondition.signalAll()
        }
    }

    private val nextFrameRequest = Runnable {
        Choreographer.getInstance().postFrameCallback(vsyncCallback)
    }

    /** 由渲染线程调用：向主线程 Choreographer 请求下一帧 vsync。 */
    internal fun requestNextFrame() {
        mainHandler.post(nextFrameRequest)
    }

    /** 在定帧等待里睡一小片（≤50ms）：tick 推进即被唤醒，超时供复检存活。 */
    internal fun awaitFrameTick(tick: Long) {
        framePacerLock.withLock {
            if (frameTickCount == tick) {
                try {
                    framePacerCondition.await(50, TimeUnit.MILLISECONDS)
                } catch (ignored: InterruptedException) {
                    // 本线程的中断无业务含义（停止靠 destroyed+poke 唤醒）：
                    // 不恢复中断标志，否则后续 await 会立即抛错让定帧循环空转
                }
            }
        }
    }

    /** 立即唤醒定帧等待（销毁/释放路径用，不必等满一个超时片）。 */
    internal fun pokeFramePacer() {
        framePacerLock.withLock { framePacerCondition.signalAll() }
    }

    // 渲染锁改为实例字段：此前在 companion 里两类波形视图的所有渲染
    // 线程共用一把锁一个条件变量，任一视图的 signal 都会唤醒全部线程
    // 空转复检。现每实例一把，互不干扰
    internal val surfaceLock = ReentrantLock()
    internal val surfaceCondition = surfaceLock.newCondition()

    companion object {
        const val RENDER_FRAME_INTERVAL_MS: Long = 16
    }
}
