package com.jobeen.ime.skin.perf

/**
 * 主线程阻塞看门狗（P4，与帧间隔探针互补：帧探针只计掉帧数，
 * 本看门狗量主线程单次停顿的时长）。
 *
 * 机制：向主 Looper 投一个带时间戳的 runnable；它跑起来时算
 * posted→run 的滞后，超阈值上报一次，随后立即重投（下一次停顿
 * 再报一次，不刷屏）。停顿期间只有一个待跑的 runnable，故一次
 * 长阻塞只产生一次上报——不存在按帧重复报警。
 *
 * post/remove/clock 全部可注入：生产走主 Handler + elapsedRealtime，
 * 单测走手动调度。stop 后已投的 runnable 即使被跑到也是 no-op，
 * 且 onLag 里 stop 也不会再重投。
 */
class MainThreadWatchdog(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val clockMs: () -> Long,
    private val thresholdMs: Long = MAIN_LAG_THRESHOLD_MS,
    private val onLag: (Long) -> Unit,
) {
    companion object {
        /** 主线程停顿上报阈值：1 秒（帧掉阈 100ms 已由 SLOW_FRAME 覆盖；此处只抓真阻塞，ANR 线是 5 秒）。 */
        const val MAIN_LAG_THRESHOLD_MS = 1000L
    }

    private val tick = Runnable { onTick() }

    private var running = false
    private var postedAtMs = 0L

    fun start() {
        if (running) return
        running = true
        postedAtMs = clockMs()
        post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        remove(tick)
    }

    private fun onTick() {
        if (!running) return
        val now = clockMs()
        val lag = now - postedAtMs
        if (lag > thresholdMs) onLag(lag)
        if (!running) return
        postedAtMs = now
        post(tick)
    }
}
