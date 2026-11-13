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
 * SkinEditorSaver 调用的应用事务端口（可注入假件供单测）：
 * 仅含编辑器实际调用的两个入口。实现为具名类 [SkinApplier] 的
 * 显式 override（无 `by` 委托对象，故不触发接口委托增量陷阱——
 * 详见 ~/workspace/AGENTS.md；新增方法须同步检查委托点）。
 */
interface SkinApplyPort {
    suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result
    suspend fun reinstallActiveSnapshot(): Boolean
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
class SkinApplier(context: Context) : SkinApplyPort {

    private val appContext = context.applicationContext
    private val layout get() = SkinLibrary.layout
    private val store get() = SkinLibrary.store

    @Volatile
    private var staged: SkinSnapshot? = null

    /**
     * 应用事务的真实端口装配（详设 §2.4/§2.7）。端口每次调用新建：
     * 首帧内联判据（[SkinRuntime.canVerifyInline]）读窗口视图
     * 状态，必须在调用线程（主线程）上快照，绝不能随事务一起
     * 移到 IO 线程（审计 #2）；快照值经参数传入，事务全程用它。
     */
    private fun ports(verifyInline: Boolean, requestToken: Long) = object : ApplyTransaction.Ports {
        /**
         * 本次事务内最近一次切换是否因被更新请求取代（K-S4）：
         * install 只在 token／代次过期时返回 false，即一定有更新的
         * 安装请求已开始；staged 未命中则不是取代（构建失配）。
         * 事务层凭此区分取代与真失败，取代时不走回滚恢复。
         */
        private var superseded = false

        override fun wasSuperseded(): Boolean = superseded

        override suspend fun buildSnapshot(ref: SkinGenRef): Boolean {
            val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            staged = snapshot
            return snapshot != null
        }

        override suspend fun switchRuntime(ref: SkinGenRef?): Boolean {
            val accepted = if (ref == null) {
                SkinSnapshotProvider.install(appContext, null, requestToken)
            } else {
                val snapshot = staged?.takeIf { it.ref == ref } ?: return false
                SkinSnapshotProvider.install(appContext, snapshot, requestToken)
            }
            // 被更新的请求取代时 install 返回 false：事务层按取代
            // 处理（不回滚、不写 verified／晋升），不得按切换失败
            // 走恢复分支、更不得按成功继续。
            if (!accepted) {
                superseded = true
                return false
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

    private fun transaction(verifyInline: Boolean, requestToken: Long) =
        ApplyTransaction(layout, store, ports(verifyInline, requestToken))

    /**
     * 皮肤应用入口：快照构建、索引／布局解析、状态文件读写、
     * 背景解码全部在 IO 线程（审计 #2）；主线程只做发起与
     * 收结果。内联判据在调用线程快照——读窗口视图状态不得
     * 离主线程；快照后窗口若消失，首帧等待最多 2s 超时回滚，
     * 结果与改前一致（仅等待变长，见 [ports]）。
     */
    override suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result = applyMutex.withLock {
        // 性能埋点（2026-10-29 item-1 B）：皮肤应用事务耗时
        // （含首帧验证等待；失败路径同样记时，只记元数据）。
        val startMs = android.os.SystemClock.elapsedRealtime()
        // 安装请求 token 在入口分配（.23 审计问题 2）：快照构建
        // 是准备的一部分，token 必须在构建之前分配，提交时才
        // 分配无法区分“先开始、后完成”的旧请求。同一实例的连
        // 续应用由 mutex 串行；双实例／启动加载／覆盖层刷新与
        // 本请求的先后以 token 裁决，晚开始的请求胜出。
        val requestToken = SkinSnapshotProvider.allocateInstallToken()
        // 内联判据先在调用线程快照，再切 IO 跑事务。
        val verifyInline = SkinRuntime.canVerifyInline()
        try {
            val result = withContext(Dispatchers.IO) {
                // 选路以「窗口正在绘制」为准而非「窗口已挂载」：IME 窗口隐藏时
                // 视图仍 attached，误判活窗口会让首帧等待必然 2s 超时回滚。
                // 2026-10-28 修复：首帧闩等待已移到 IO 线程（awaitFirstDrawLatch），
                // 主线程应用不再自锁；真正不绘制的窗口仍 2s 超时回滚。
                if (verifyInline) {
                    transaction(verifyInline, requestToken).apply(ref)
                } else {
                    deferredApply(ref, requestToken)
                }
            }
            // 皮肤应用诊断（2026-11-02 修复批）：每次应用记结果类型
            // 与代次，与 undo_guard / voice_wave 同批进诊断日志。
            // 事件名取稳定标签：旧 7 个结果名在 Result 11→5 合并后只剩
            // Applied/Failed 两类名，pending 与失败级会丢进同一名
            // （WO45 自审 B-F1）；按结果派生标签，诊断粒度与旧口径一致。
            val resultLabel = when (val r = result) {
                is ApplyTransaction.Result.Applied -> if (r.pending) "AppliedPending" else "Applied"
                is ApplyTransaction.Result.Failed -> "Failed:${r.stage}"
                is ApplyTransaction.Result.Superseded -> "Superseded"
                is ApplyTransaction.Result.RolledBackToLastGood -> "RolledBackToLastGood"
                is ApplyTransaction.Result.RevertedToDefault -> "RevertedToDefault"
            }
            DiagLog.d(
                "skin_apply", resultLabel,
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

    suspend fun rollbackToLastGood(): ApplyTransaction.Result = applyMutex.withLock {
        // 内联判据在调用线程快照（读窗口视图状态不得离主线程）。
        val verifyInline = SkinRuntime.canVerifyInline()
        val requestToken = SkinSnapshotProvider.allocateInstallToken()
        withContext(Dispatchers.IO) { rollbackToLastGoodOnIo(verifyInline, requestToken) }
    }

    private suspend fun rollbackToLastGoodOnIo(
        verifyInline: Boolean,
        requestToken: Long,
    ): ApplyTransaction.Result {
        if (verifyInline) {
            return transaction(verifyInline, requestToken).rollbackToLastGood()
        }
        val pointer = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return ApplyTransaction.Result.Failed(null, ApplyTransaction.Result.Stage.ROLLBACK_UNAVAILABLE, "no_last_good").also {
                DiagLog.w("runtime", "rollback_unavailable", mapOf("reason" to "no_last_good"))
            }
        val ref = SkinGenRef(pointer.skinId, pointer.hash, pointer.generation)
        if (!isTargetValid(ref)) {
            return ApplyTransaction.Result.Failed(null, ApplyTransaction.Result.Stage.ROLLBACK_UNAVAILABLE, "last_good_target_invalid")
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
            ?: return ApplyTransaction.Result.Failed(null, ApplyTransaction.Result.Stage.ROLLBACK_UNAVAILABLE, "build_failed").also {
                DiagLog.w(
                    "runtime", "rollback_unavailable",
                    mapOf(
                        "reason" to "build_failed",
                        "skinId" to ref.skinId, "gen" to ref.generation,
                    ),
                )
            }
        SkinRuntime.disarmPendingVerification()
        // 被更新的请求取代时不写 VERIFIED、不报回退成功：运行态
        // 仍在更新的皮肤上，写记录会把 active 指到未生效的皮肤。
        if (!SkinSnapshotProvider.install(appContext, snapshot, requestToken)) {
            // 被更新请求取代：获胜请求已生效，回退本次未生效不是
            // 失败（K-S4 同口径）；不得报回退不可用（Failed/ROLLBACK_UNAVAILABLE）。
            return ApplyTransaction.Result.Superseded(ref)
        }
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

    suspend fun revertToDefault(): ApplyTransaction.Result = applyMutex.withLock {
        SkinRuntime.disarmPendingVerification()
        // disarm 只写 @Volatile 变量，主线程安全；事务本体在 IO 跑。
        val verifyInline = SkinRuntime.canVerifyInline()
        val requestToken = SkinSnapshotProvider.allocateInstallToken()
        withContext(Dispatchers.IO) { transaction(verifyInline, requestToken).revertToDefault() }
    }

    /**
     * 以当前 active 记录重建并安装快照（B3 §4.6 删被引用布局后）：
     * 皮肤内容本身未变，只是其 layoutId 引用已悬空——重解析后
     * 快照按未绑定布局构建（回退默认布局），运行态即时收敛。
     * 无 active 皮肤时返回 true（无事可做）。
     */
    override suspend fun reinstallActiveSnapshot(): Boolean = applyMutex.withLock {
        val requestToken = SkinSnapshotProvider.allocateInstallToken()
        withContext(Dispatchers.IO) { reinstallActiveSnapshotOnIo(requestToken) }
    }

    private suspend fun reinstallActiveSnapshotOnIo(requestToken: Long): Boolean {
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
        if (!SkinSnapshotProvider.install(appContext, snapshot, requestToken)) return false
        SkinRuntime.requestRefresh()
        return true
    }

    /** 延迟验证路径：与事务同口径先验目标，再写 pending 并武装（R5）。 */
    private suspend fun deferredApply(ref: SkinGenRef, requestToken: Long): ApplyTransaction.Result {
        if (!isTargetValid(ref)) {
            DiagLog.w(
                "runtime", "apply_target_invalid",
                mapOf("skinId" to ref.skinId, "gen" to ref.generation, "path" to "deferred"),
            )
            return ApplyTransaction.Result.Failed(null, ApplyTransaction.Result.Stage.TARGET, "target_missing_or_hash_mismatch")
        }
        val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            ?: return ApplyTransaction.Result.Failed(ref, ApplyTransaction.Result.Stage.BUILD, "snapshot_rebuild_failed")
        // 写 PENDING 前先记切换前记录（K-S3）：安装被取代时把它
        // 写回——只当它仍是当前记录时（获胜方已写记录时不动它），
        // 绝不盲写整份索引；否则被取代皮肤的 PENDING 残留磁盘，
        // 重启后经 KeepPending 复活、覆盖更新的皮肤。
        val previousActive =
            (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
        store.writeActive(
            ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.PENDING,
                System.currentTimeMillis()),
        )
        // 被更新的请求取代时不武装、不报 Applied(pending)：运行态
        // 仍在更新的皮肤上，武装旧 ref 会让下次首帧把旧皮肤晋升
        // 为 verified；同时收敛本请求刚写的 PENDING 记录（见上）。
        if (!SkinSnapshotProvider.install(appContext, snapshot, requestToken)) {
            restorePendingActive(previousActive, ref)
            return ApplyTransaction.Result.Superseded(ref)
        }
        SkinRuntime.armPendingVerification(ref)
        DiagLog.i(
            "runtime", "apply_pending_deferred",
            mapOf("skinId" to ref.skinId, "gen" to ref.generation),
        )
        return ApplyTransaction.Result.Applied(ref, pending = true)
    }

    /**
     * 取代后的 PENDING 收敛（K-S3，与 ApplyTransaction.restorePendingRecord
     * 同口径）：当前记录仍是本请求刚写的 PENDING 时，才把它写回切换
     * 前记录（previousActive 为 null 即无前记录时写空记录）；获胜方
     * 已写记录时不动。只写这一条 active 记录，不碰索引。
     */
    private suspend fun restorePendingActive(previousActive: ActiveRecord?, ref: SkinGenRef) {
        val current = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return
        if (current.skinId == ref.skinId && current.hash == ref.hash &&
            current.generation == ref.generation && current.state == ApplyState.PENDING
        ) {
            runCatching { store.writeActive(previousActive ?: ActiveRecord()) }
                .onFailure {
                    DiagLog.w(
                        "runtime", "superseded_pending_restore_failed",
                        mapOf("skinId" to ref.skinId),
                    )
                }
        }
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

        /**
         * 应用串行锁（2026-11-12 审计 K-S5）：类级共享——编辑器、
         * 皮肤库页、开发页各持一个 SkinApplier 实例，实例级锁拦不住
         * 跨实例交错；token 只保护 install 提交瞬间，不保护“写
         * PENDING → 切换 → 写 VERIFIED／晋升 Last-Good”多步序列。
         * 全部应用／回退／重装入口共用此锁。
         */
        private val applyMutex = Mutex()
    }
}
