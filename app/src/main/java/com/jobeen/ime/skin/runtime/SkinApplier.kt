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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 应用事务的真实端口装配（详设 §2.4/§2.7）：
 *
 * - 有活动窗口（IME 进程内）：走 [ApplyTransaction] 全状态机，
 *   首帧等待 = 窗口刷新后的一次性 onDraw 闩（超时 2s 按失败回滚）。
 * - 无活动窗口（设置进程内应用）：延迟验证路径——构建快照 →
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

        override fun awaitFirstRender(): Boolean {
            if (!SkinRuntime.hasLiveWindow()) return false
            val latch = CountDownLatch(1)
            SkinRuntime.requestRefresh { latch.countDown() }
            return runCatching { latch.await(FIRST_RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
        }
    }

    private fun transaction() = ApplyTransaction(layout, store, ports)

    suspend fun applySkin(ref: SkinGenRef): ApplyTransaction.Result = mutex.withLock {
        if (SkinRuntime.hasLiveWindow()) {
            transaction().apply(ref)
        } else {
            deferredApply(ref)
        }
    }

    suspend fun rollbackToLastGood(): ApplyTransaction.Result = mutex.withLock {
        if (SkinRuntime.hasLiveWindow()) {
            return@withLock transaction().rollbackToLastGood()
        }
        val pointer = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return@withLock ApplyTransaction.Result.RollbackUnavailable("no_last_good")
        val ref = SkinGenRef(pointer.skinId, pointer.hash, pointer.generation)
        if (!isTargetValid(ref)) {
            return@withLock ApplyTransaction.Result.RollbackUnavailable("last_good_target_invalid")
        }
        val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            ?: return@withLock ApplyTransaction.Result.RollbackUnavailable("build_failed")
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
        val hash = active.hash ?: return@withLock false
        val ref = SkinGenRef(skinId, hash, active.generation)
        val snapshot = SkinSnapshotFactory.buildFromGenDir(appContext, layout, ref)
            ?: return@withLock false
        SkinSnapshotProvider.install(appContext, snapshot)
        SkinRuntime.requestRefresh()
        true
    }

    /** 延迟验证路径：与事务同口径先验目标，再写 pending 并武装（R5）。 */
    private suspend fun deferredApply(ref: SkinGenRef): ApplyTransaction.Result {
        if (!isTargetValid(ref)) {
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
        return ApplyTransaction.Result.Applied(ref)
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
