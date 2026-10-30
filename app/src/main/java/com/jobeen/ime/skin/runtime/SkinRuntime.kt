package com.jobeen.ime.skin.runtime

import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.LastGoodPointer
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 运行态窗口注册表（详设 §2.7）：应用事务与键盘窗口之间的唯一
 * 接缝。窗口在 onAttachedToWindow 注册、onDetachedFromWindow 注销；
 * 设置进程内无窗口时应用走「延迟验证」路径（R5 两态）：先写
 * pending 并武装本对象，下一个窗口挂载后的首帧回调把记录推进
 * verified 并晋升 Last-Good；进程在此之前死亡则启动对账按
 * pending 未验证回滚（与八.3 口径一致）。
 */
object SkinRuntime {

    /** 键盘窗口对运行态的最小接口（窗口类实现，避免反向依赖 UI 层）。 */
    interface SkinWindow {
        /**
         * 在窗口线程执行一次配色刷新；[onFirstDraw] 非空时，
         * 在这次刷新后的首个 onDraw 回调一次（一次性监听）。
         */
        fun refreshSkinTheme(onFirstDraw: (() -> Unit)?)

        /**
         * 窗口当前是否真在屏幕上绘制（已挂载 ∧ 可见）。
         * IME 窗口隐藏时视图仍保持 attached——只看挂载会误判为
         * 「活窗口」，首帧永远等不到（真机日志实证：应用事务
         * 三连 2s 超时回滚）。应用事务选路必须以此为准。
         */
        fun isVisibleNow(): Boolean
    }

    @Volatile
    private var window: SkinWindow? = null

    @Volatile
    private var pendingVerification: SkinGenRef? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 内存压力升级标记（详设 §6.3）：下一次快照构建按更低档位执行，由 T11 置位/消费。 */
    @Volatile
    var trimEscalated: Boolean = false

    fun attachWindow(w: SkinWindow) {
        window = w
    }

    fun detachWindow(w: SkinWindow) {
        if (window === w) window = null
    }

    fun hasLiveWindow(): Boolean = window != null

    /** 已挂载窗口当前是否可见（无窗口/查询异常一律按不可见处理）。 */
    fun isWindowVisible(): Boolean =
        runCatching { window?.isVisibleNow() == true }.getOrDefault(false)

    /**
     * 能否走「即时验证」全事务：窗口已挂载且正在绘制，首帧回调
     * 才可能在超时内到达。键盘隐藏时（设置页内应用恒属此情形）
     * 必须走延迟验证，否则首帧等待必然超时回滚、皮肤永远应用不上。
     */
    fun canVerifyInline(): Boolean = window != null && isWindowVisible()

    /** 请求当前窗口刷新；无窗口时静默无操作（快照已在 provider 内切换，下次建窗自然生效）。 */
    fun requestRefresh(onFirstDraw: (() -> Unit)? = null) {
        window?.refreshSkinTheme(onFirstDraw)
    }

    fun armPendingVerification(ref: SkinGenRef) {
        pendingVerification = ref
        // 窗口可能早已挂载只是隐藏中（设置页内应用的常态）：此时不会
        // 再有 onWindowAttached，必须立即挂下一帧绘制钩子，用户下次
        // 打开键盘的首帧即完成验证推进；等不到新挂载就永不验证、
        // 下次启动对账会把 pending 回滚掉，等于应用无效。
        hookPendingVerificationOnNextDraw()
    }

    fun disarmPendingVerification() {
        pendingVerification = null
    }

    /**
     * 窗口挂载后调用：存在待验证应用时，挂首帧回调完成验证推进。
     * 回调时若 provider 当前快照已不是待验证目标（期间被回退/回
     * 默认），放弃推进，记录保持 pending 交启动对账处理。
     */
    fun onWindowAttached() {
        hookPendingVerificationOnNextDraw()
    }

    /** 有待验证应用且已有窗口时，把「下一帧绘制完成验证」挂到窗口上。 */
    private fun hookPendingVerificationOnNextDraw() {
        val ref = pendingVerification ?: return
        if (window == null) return
        requestRefresh {
            scope.launch {
                if (pendingVerification != ref) return@launch
                if (SkinSnapshotProvider.peek()?.ref != ref) {
                    pendingVerification = null
                    return@launch
                }
                val store = SkinLibrary.store
                val now = System.currentTimeMillis()
                store.writeActive(
                    ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.VERIFIED, now),
                )
                store.writeLastGood(LastGoodPointer(ref.skinId, ref.hash, ref.generation))
                pendingVerification = null
                DiagLog.i(
                    "runtime", "apply_verified_deferred",
                    mapOf("skinId" to ref.skinId, "gen" to ref.generation),
                )
            }
        }
    }
}
