package com.jobeen.ime.ui

/**
 * FAB 共享模糊刷新节流门（第 5 批 §5，纯 JVM 部分）：
 *
 * 对方要求：刷新节流到距上次捕获约 100ms 一次、单飞。
 * 本门只做判定，不触 Compose 与 Backdrop 本体——调用方
 * （FAB 背景内容的异步图片就绪回调链）在图片替换空帧时调
 * [request]，获得 GRANTED 才真正触发一次共享背景重捕；
 * 在途或节流窗口内时记为待处理（pending），调用方在窗口
 * 过后调 [pollDue] 补刷一次，pending 只合成为一次，不堆积。
 *
 * 接线残余（见报告，不硬上）：当前 FAB 背景内容是设置页
 * 静态行（painterResource 同步图），不存在“异步图片就绪”
 * 回调源——没有回调源可挂的刷新触发是凭空重绘，不接。
 * 本门已就绪，待 FAB 背景内容真正出现异步图片时，调用方
 * 只需在图片就绪回调里调 request()，无需再改节流逻辑。
 * 按键玻璃扩展仍属“需视觉先确认”的后续工作，本批不动。
 */
class FabBackdropRefreshGate(
    /** 节流窗口：约 100ms（对方 R7–R9 同值）。 */
    private val throttleMs: Long = 100L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Verdict {
        /** 可立即刷新（调用方刷新后必须调 finish）。 */
        GRANTED,

        /** 已有一次刷新在途：已记 pending，不重复触发。 */
        IN_FLIGHT,

        /** 节流窗口内：已记 pending，窗口过后 pollDue 补刷。 */
        THROTTLED,
    }

    private val lock = Any()
    private var inFlight = false
    private var pending = false
    private var lastGrantedMs: Long? = null

    /** 异步图片就绪时调用：GRANTED 者负责刷新并调 finish。 */
    fun request(): Verdict = synchronized(lock) {
        if (inFlight) {
            pending = true
            return Verdict.IN_FLIGHT
        }
        val last = lastGrantedMs
        val now = clockMs()
        if (last != null && now - last < throttleMs) {
            pending = true
            return Verdict.THROTTLED
        }
        inFlight = true
        lastGrantedMs = now
        Verdict.GRANTED
    }

    /** 刷新完成时调用（GRANTED 者必调，否则门永久在途）。 */
    fun finish() {
        synchronized(lock) { inFlight = false }
    }

    /**
     * 待处理补刷授权：有 pending、当前无在途、且距上次授权已
     * 过节流窗口时返回 true 并同时占位（pending 清零、
     * inFlight 置位、lastGrantedMs 更新），调用方刷新后必须
     * 调 finish；占位后再次 pollDue 不再重复授权（无 pending），
     * 杜绝同一待处理项补刷两次。
     */
    fun pollDue(): Boolean = synchronized(lock) {
        if (!pending || inFlight) return false
        val last = lastGrantedMs
        if (last != null && clockMs() - last < throttleMs) return false
        pending = false
        inFlight = true
        lastGrantedMs = clockMs()
        true
    }

    fun hasPending(): Boolean = synchronized(lock) { pending }

    /** 测试／复位用：清 pending（不碰 lastGrantedMs 与在途态）。 */
    fun clearPending() {
        synchronized(lock) { pending = false }
    }
}
