package com.jobeen.ime.skin.runtime

import android.content.Context
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ActiveResolver
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.ApplyTransaction
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 首帧闩等待（2026-10-28 修复轮：真机 first_render_failed 根因）。
 *
 * 旧实现在调用线程上阻塞 `latch.await(2s)`；而窗口刷新与 onDraw
 * 监听注册是 `post` 到主线程的——当应用入口在主线程（如皮肤库
 * 点击）时，主线程被闩阻塞、刷新块永远排不上队，监听从未注册，
 * 每次可见窗口应用都恰好 2s 超时回滚（真机 10:24 ×1、10:29 ×3，
 * 间隔恰为超时值；键盘隐藏时走延迟路径不受影响，故表现为间歇）。
 * 本函数把阻塞等待移到 IO 线程：主线程保持空闲，刷新与首帧回调
 * 得以执行；真正不绘制的窗口仍按 2s 超时回滚（兜底语义不变）。
 */
internal suspend fun awaitFirstDrawLatch(latch: CountDownLatch, timeoutMs: Long): Boolean =
    withContext(Dispatchers.IO) {
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

/**
 * 应用事务的真实端口装配（详设 §2.4/§2.7）：
 *
 * - 窗口正在绘制（键盘正显示）：走 [ApplyTransaction] 全状态机，
 *   首帧等待 = 窗口刷新后的一次性 onDraw 闩（超时 2s 按失败回滚）。
 *   判据是可见性而非挂载：键盘隐藏时窗口视图仍 attached，从设置
 *   页应用若误走全事务，首帧永不到达、必然超时回滚。
 * - 窗口不可见（设置页内应用的常态）：延迟验证路径——构建快照 →
 *   写 active{pending} → 切换 provider → 在 [SkinRuntime] 武装，
 *   下一个窗口首帧完成 verified 推进与 Last-Good 晋升。
 * - 回退 Last-Good 的目标是已验证代次，无窗口时直接以 verified
 *   写回（无须再验证）；回默认不依赖窗口，两种情形同路。
 */
class SkinApplier(context: Context) {

    private val appContext = context.applicationContext
    private val layout get() = SkinLibrary.layout
    private val store get() = SkinLibrary.store
    private val mutex = Mutex()

    @Volatile
    private var staged: SkinSnapshot? = null

    /**
     * 应用事务的真实端口装配（详设 §2.4/§2.7）。端口每次调用新建：
     * 首帧内联判据（[SkinRuntime.canVerifyInline]）读窗口视图
     * 状态，必须在调用线程（主线程）上快照，绝不能随事务一起
     * 移到 IO 线程（审计 #2）；快照值经参数传入，事务全程用它。
     */
    private fun ports(verifyInline: Boolean) = object : ApplyTransaction.Ports {
        override suspend fun buildSnapshot(ref: SkinGenRef): Boolean {
            val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            staged = snapshot
            return snapshot != null
        }

        override suspend fun switchRuntime(ref: SkinGenRef?): Boolean {
            if (ref == null) {
                SkinSnapshotProvider.install(appContext, null)
            } else {
                val snapshot = staged?.takeIf { it.ref == ref } ?: return false
                SkinSnapshotProvider.install(appContext, snapshot)
            }
            SkinRuntime.requestRefresh()
            return true
        }

        override suspend fun awaitFirstRender(): Boolean {
            if (!verifyInline) return false
            val latch = CountDownLatch(1)
            SkinRuntime.requestRefresh { latch.countDown() }
            return awaitFirstDrawLatch(latch, FIRST_RENDER_TIMEOUT_MS)
        }
    }

    private fun transaction(verifyInline: Boolean) = ApplyTransaction(layout, store, ports(verifyInline))

    /**
     * 皮肤应用入口：快照构建、索引／布局解析、状态文件读写、
     * 背景解码全部在 IO 线程（审计 #2）；主线程只做发起与
     * 收结果。内联判据在调用线程快照——读窗口视图状态不得
     * 离主线程；快照后窗口若消失，首帧等待最多 2s 超时回滚，
     * 结果与改前一致（仅等待变长，见 [ports]）。
     */
    suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result = mutex.withLock {
        // 性能埋点（2026-10-29 item-1 B）：皮肤应用事务耗时
        // （含首帧验证等待；失败路径同样记时，只记元数据）。
        val startMs = android.os.SystemClock.elapsedRealtime()
        // 内联判据先在调用线程快照，再切 IO 跑事务。
        val verifyInline = SkinRuntime.canVerifyInline()
        try {
            val result = withContext(Dispatchers.IO) {
                // 选路以「窗口正在绘制」为准而非「窗口已挂载」：IME 窗口隐藏时
                // 视图仍 attached，误判活窗口会让首帧等待必然 2s 超时回滚。
                // 2026-10-28 修复：首帧闩等待已移到 IO 线程（awaitFirstDrawLatch），
                // 主线程应用不再自锁；真正不绘制的窗口仍 2s 超时回滚。
                if (verifyInline) {
                    transaction(verifyInline).apply(ref)
                } else {
                    deferredApply(ref)
                }
            }
            // 皮肤应用诊断（2026-11-02 修复批）：每次应用记结果类型
            // 与代次，与 undo_guard / voice_wave 同批进诊断日志。
            DiagLog.d(
                "skin_apply", result::class.simpleName ?: "unknown",
                mapOf("skinId" to ref.skinId, "gen" to ref.generation),
            )
            result
        } finally {
            com.jobeen.ime.skin.perf.PerfMonitor.record(
                com.jobeen.ime.skin.perf.PerfMonitor.Event.SKIN_APPLY,
                android.os.SystemClock.elapsedRealtime() - startMs,
                mapOf("skinId" to ref.skinId),
            )
        }
    }

    suspend fun rollbackToLastGood(): ApplyTransaction.Result = mutex.withLock {
        // 内联判据在调用线程快照（读窗口视图状态不得离主线程）。
        val verifyInline = SkinRuntime.canVerifyInline()
        withContext(Dispatchers.IO) { rollbackToLastGoodOnIo(verifyInline) }
    }

    private suspend fun rollbackToLastGoodOnIo(
        verifyInline: Boolean,
    ): ApplyTransaction.Result {
        if (verifyInline) {
            return transaction(verifyInline).rollbackToLastGood()
        }
        val pointer = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return ApplyTransaction.Result.RollbackUnavailable("no_last_good").also {
                DiagLog.w("runtime", "rollback_unavailable", mapOf("reason" to "no_last_good"))
            }
        val ref = SkinGenRef(pointer.skinId, pointer.hash, pointer.generation)
        if (!isTargetValid(ref)) {
            return ApplyTransaction.Result.RollbackUnavailable("last_good_target_invalid")
                .also {
                    DiagLog.w(
                        "runtime", "rollback_unavailable",
                        mapOf(
                            "reason" to "last_good_target_invalid",
                            "skinId" to ref.skinId, "gen" to ref.generation,
                        ),
                    )
                }
        }
        val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            ?: return ApplyTransaction.Result.RollbackUnavailable("build_failed").also {
                DiagLog.w(
                    "runtime", "rollback_unavailable",
                    mapOf(
                        "reason" to "build_failed",
                        "skinId" to ref.skinId, "gen" to ref.generation,
                    ),
                )
            }
        SkinRuntime.disarmPendingVerification()
        SkinSnapshotProvider.install(appContext, snapshot)
        store.writeActive(
            ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.VERIFIED,
                System.currentTimeMillis()),
        )
        DiagLog.i(
            "runtime", "rollback_last_good_deferred",
            mapOf("skinId" to ref.skinId, "gen" to ref.generation),
        )
        return ApplyTransaction.Result.RolledBackToLastGood(ref)
    }

    suspend fun revertToDefault(): ApplyTransaction.Result = mutex.withLock {
        SkinRuntime.disarmPendingVerification()
        // disarm 只写 @Volatile 变量，主线程安全；事务本体在 IO 跑。
        val verifyInline = SkinRuntime.canVerifyInline()
        withContext(Dispatchers.IO) { transaction(verifyInline).revertToDefault() }
    }

    /**
     * 以当前 active 记录重建并安装快照（B3 §4.6 删被引用布局后）：
     * 皮肤内容本身未变，只是其 layoutId 引用已悬空——重解析后
     * 快照按未绑定布局构建（回退默认布局），运行态即时收敛。
     * 无 active 皮肤时返回 true（无事可做）。
     */
    suspend fun reinstallActiveSnapshot(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { reinstallActiveSnapshotOnIo() }
    }

    private suspend fun reinstallActiveSnapshotOnIo(): Boolean {
        val active = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return true
        val skinId = active.skinId ?: return true
        val hash = active.hash ?: return false.also {
            DiagLog.w("runtime", "reinstall_active_failed", mapOf("skinId" to skinId, "reason" to "hash_missing"))
        }
        val ref = SkinGenRef(skinId, hash, active.generation)
        val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            ?: return false.also {
                DiagLog.w(
                    "runtime", "reinstall_active_failed",
                    mapOf("skinId" to skinId, "gen" to active.generation, "reason" to "build_failed"),
                )
            }
        SkinSnapshotProvider.install(appContext, snapshot)
        SkinRuntime.requestRefresh()
        return true
    }

    /** 延迟验证路径：与事务同口径先验目标，再写 pending 并武装（R5）。 */
    private suspend fun deferredApply(ref: SkinGenRef): ApplyTransaction.Result {
        if (!isTargetValid(ref)) {
            DiagLog.w(
                "runtime", "apply_target_invalid",
                mapOf("skinId" to ref.skinId, "gen" to ref.generation, "path" to "deferred"),
            )
            return ApplyTransaction.Result.TargetInvalid("target_missing_or_hash_mismatch")
        }
        val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            ?: return ApplyTransaction.Result.BuildFailed(ref)
        store.writeActive(
            ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.PENDING,
                System.currentTimeMillis()),
        )
        SkinSnapshotProvider.install(appContext, snapshot)
        SkinRuntime.armPendingVerification(ref)
        DiagLog.i(
            "runtime", "apply_pending_deferred",
            mapOf("skinId" to ref.skinId, "gen" to ref.generation),
        )
        return ApplyTransaction.Result.AppliedPending(ref)
    }

    private fun isTargetValid(ref: SkinGenRef): Boolean =
        ActiveResolver.isTargetValid(ref.skinId, ref.generation, ref.hash) { id, gen ->
            val dir = layout.skinGenDir(id, gen)
            val complete = layout.isSkinGenComplete(dir)
            val hash = if (complete) {
                runCatching { DigestUtil.sha256Hex(File(dir, "skin.json")) }.getOrNull()
            } else {
                null
            }
            ActiveResolver.GenState(complete, hash)
        }

    private companion object {
        const val FIRST_RENDER_TIMEOUT_MS = 2_000L
    }
}
