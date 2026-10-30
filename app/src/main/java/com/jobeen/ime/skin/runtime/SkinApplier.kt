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

    private val ports = object : ApplyTransaction.Ports {
        override fun buildSnapshot(ref: SkinGenRef): Boolean {
            val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            staged = snapshot
            return snapshot != null
        }

        override fun switchRuntime(ref: SkinGenRef?): Boolean {
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
            if (!SkinRuntime.canVerifyInline()) return false
            val latch = CountDownLatch(1)
            SkinRuntime.requestRefresh { latch.countDown() }
            return awaitFirstDrawLatch(latch, FIRST_RENDER_TIMEOUT_MS)
        }
    }

    private fun transaction() = ApplyTransaction(layout, store, ports)

    suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result = mutex.withLock {
        // 性能埋点（2026-10-29 item-1 B）：皮肤应用事务耗时
        // （含首帧验证等待；失败路径同样记时，只记元数据）。
        val startMs = android.os.SystemClock.elapsedRealtime()
        try {
            // 选路以「窗口正在绘制」为准而非「窗口已挂载」：IME 窗口隐藏时
            // 视图仍 attached，误判活窗口会让首帧等待必然 2s 超时回滚。
            // 2026-10-28 修复：首帧闩等待已移到 IO 线程（awaitFirstDrawLatch），
            // 主线程应用不再自锁；真正不绘制的窗口仍 2s 超时回滚。
            if (SkinRuntime.canVerifyInline()) {
                transaction().apply(ref)
            } else {
                deferredApply(ref)
            }
        } finally {
            com.jobeen.ime.skin.perf.PerfMonitor.record(
                com.jobeen.ime.skin.perf.PerfMonitor.Event.SKIN_APPLY,
                android.os.SystemClock.elapsedRealtime() - startMs,
                mapOf("skinId" to ref.skinId),
            )
        }
    }

    suspend fun rollbackToLastGood(): ApplyTransaction.Result = mutex.withLock {
        if (SkinRuntime.canVerifyInline()) {
            return@withLock transaction().rollbackToLastGood()
        }
        val pointer = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return@withLock ApplyTransaction.Result.RollbackUnavailable("no_last_good").also {
                DiagLog.w("runtime", "rollback_unavailable", mapOf("reason" to "no_last_good"))
            }
        val ref = SkinGenRef(pointer.skinId, pointer.hash, pointer.generation)
        if (!isTargetValid(ref)) {
            return@withLock ApplyTransaction.Result.RollbackUnavailable("last_good_target_invalid")
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
            ?: return@withLock ApplyTransaction.Result.RollbackUnavailable("build_failed").also {
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
        ApplyTransaction.Result.RolledBackToLastGood(ref)
    }

    suspend fun revertToDefault(): ApplyTransaction.Result = mutex.withLock {
        SkinRuntime.disarmPendingVerification()
        transaction().revertToDefault()
    }

    /**
     * 以当前 active 记录重建并安装快照（B3 §4.6 删被引用布局后）：
     * 皮肤内容本身未变，只是其 layoutId 引用已悬空——重解析后
     * 快照按未绑定布局构建（回退默认布局），运行态即时收敛。
     * 无 active 皮肤时返回 true（无事可做）。
     */
    suspend fun reinstallActiveSnapshot(): Boolean = mutex.withLock {
        val active = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return@withLock true
        val skinId = active.skinId ?: return@withLock true
        val hash = active.hash ?: return@withLock false.also {
            DiagLog.w("runtime", "reinstall_active_failed", mapOf("skinId" to skinId, "reason" to "hash_missing"))
        }
        val ref = SkinGenRef(skinId, hash, active.generation)
        val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            ?: return@withLock false.also {
                DiagLog.w(
                    "runtime", "reinstall_active_failed",
                    mapOf("skinId" to skinId, "gen" to active.generation, "reason" to "build_failed"),
                )
            }
        SkinSnapshotProvider.install(appContext, snapshot)
        SkinRuntime.requestRefresh()
        true
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
