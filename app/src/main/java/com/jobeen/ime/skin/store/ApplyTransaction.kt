package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.fault.SkinFaultHooks
import java.io.File

/**
 * 应用事务（详设 §2.4 状态机 + §2.5 Last-Good，步骤写死）：
 *
 * ```
 * apply(skin) := build snapshot → write active{pending} → switch runtime
 *              → first render OK → write active{verified} → promote Last-Good
 * ```
 *
 * 与运行态的接缝抽象为 [Ports]：快照构建、运行态切换、首帧等待由
 * T2/T4 的真实实现注入（挂接点已核实：KeyboardWindowView
 * .refreshColors() 与其首帧回调，见详设 §2.4/§2.7 回填）。本类只管
 * 记录与状态机：任一步失败，active 记录与运行态恒停在旧代次
 * （A-1/A-6 口径），新代次从未以 verified 形态被采用。
 */
class ApplyTransaction(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
    private val ports: Ports,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    interface Ports {
        /** 构建快照（冷路径：含背景预处理复用）；false/异常 = 构建失败（A-1）。 */
        fun buildSnapshot(ref: SkinGenRef): Boolean

        /** 切换运行态引用；ref 为 null 表示回到内置默认皮肤。false/异常 = 切换失败。 */
        fun switchRuntime(ref: SkinGenRef?): Boolean

        /** 等待切换后的首帧渲染完成；false = 未完成或异常，按未验证处理（A-3/A-4 口径）。 */
        fun awaitFirstRender(): Boolean
    }

    sealed interface Result {
        data class Applied(val ref: SkinGenRef) : Result
        data class BuildFailed(val ref: SkinGenRef) : Result
        data class SwitchFailedRolledBack(val ref: SkinGenRef) : Result
        data class FirstRenderFailedRolledBack(val ref: SkinGenRef) : Result
        data class TargetInvalid(val reason: String) : Result
        data class RolledBackToLastGood(val ref: SkinGenRef) : Result
        object RevertedToDefault : Result
        data class RollbackUnavailable(val reason: String) : Result
    }

    /** 当前生效状态的内部视图：回退目标记录与其引用。 */
    private data class PreviousState(val record: ActiveRecord, val ref: SkinGenRef?)

    suspend fun apply(ref: SkinGenRef): Result {
        if (!isTargetValidOnDisk(ref)) {
            DiagLog.w(
                "store", "apply_target_invalid",
                mapOf("skinId" to ref.skinId, "gen" to ref.generation),
            )
            return Result.TargetInvalid("target_missing_or_hash_mismatch")
        }
        val previous = currentPreviousState()

        SkinFaultHooks.hit(SkinFaultHooks.Point.A1)
        if (!runPort { ports.buildSnapshot(ref) }) {
            DiagLog.w(
                "store", "apply_build_failed",
                mapOf("skinId" to ref.skinId, "gen" to ref.generation),
            )
            return Result.BuildFailed(ref)
        }

        val pending = ActiveRecord(
            skinId = ref.skinId, hash = ref.hash, generation = ref.generation,
            state = ApplyState.PENDING, appliedAt = clock(),
        )
        store.writeActive(pending)
        SkinFaultHooks.hit(SkinFaultHooks.Point.A2)
        DiagLog.i(
            "store", "apply_pending",
            mapOf("skinId" to ref.skinId, "gen" to ref.generation),
        )

        if (!runPort { ports.switchRuntime(ref) }) {
            restorePrevious(previous, "switch_failed")
            return Result.SwitchFailedRolledBack(ref)
        }
        SkinFaultHooks.hit(SkinFaultHooks.Point.A3)

        if (!runPort { ports.awaitFirstRender() }) {
            restorePrevious(previous, "first_render_failed")
            return Result.FirstRenderFailedRolledBack(ref)
        }
        SkinFaultHooks.hit(SkinFaultHooks.Point.A4)

        val verified = pending.copy(state = ApplyState.VERIFIED)
        store.writeActive(verified)
        // Last-Good 晋升（§2.5.2 三条件已齐：构建成功 ∧ 首渲成功 ∧ 已 verified）
        store.writeLastGood(LastGoodPointer(ref.skinId, ref.hash, ref.generation))
        DiagLog.i(
            "store", "apply_verified",
            mapOf("skinId" to ref.skinId, "gen" to ref.generation, "promoted" to true),
        )
        return Result.Applied(ref)
    }

    /** 手动回退到 Last-Good（§2.5.3）：目标先验可用性，切换成功后才改记录。 */
    suspend fun rollbackToLastGood(): Result {
        val pointer = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return Result.RollbackUnavailable("no_last_good")
        val ref = SkinGenRef(pointer.skinId, pointer.hash, pointer.generation)
        if (!isTargetValidOnDisk(ref)) {
            return Result.RollbackUnavailable("last_good_target_invalid")
        }
        val previous = currentPreviousState()
        if (previous.ref == ref) {
            // 已在 Last-Good 上：只收敛记录，不重复切换
            store.writeActive(
                ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.VERIFIED, clock()),
            )
            return Result.RolledBackToLastGood(ref)
        }
        if (!runPort { ports.buildSnapshot(ref) }) {
            return Result.RollbackUnavailable("build_failed")
        }
        if (!runPort { ports.switchRuntime(ref) }) {
            return Result.RollbackUnavailable("switch_failed")
        }
        if (!runPort { ports.awaitFirstRender() }) {
            runPort { ports.switchRuntime(previous.ref) }
            return Result.RollbackUnavailable("first_render_failed")
        }
        store.writeActive(
            ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.VERIFIED, clock()),
        )
        DiagLog.i(
            "store", "rollback_last_good",
            mapOf("skinId" to ref.skinId, "gen" to ref.generation),
        )
        return Result.RolledBackToLastGood(ref)
    }

    /**
     * 回到内置默认皮肤（§2.4 规则 3：active 记 skinId null）。
     * 内置路径是最终兜底：切换成功后即写记录，不以首帧结果回滚
     * （它失败意味着键盘本身异常，无更下层可退）。Last-Good 指针
     * 保留，用户可再手动回退到它。
     */
    suspend fun revertToDefault(): Result {
        if (!runPort { ports.switchRuntime(null) }) {
            return Result.RollbackUnavailable("switch_failed")
        }
        store.writeActive(ActiveRecord(appliedAt = clock()))
        DiagLog.i("store", "revert_to_default")
        return Result.RevertedToDefault
    }

    // ---- 内部 ----

    /** 恢复旧代次：先原子改回旧记录，再把运行态切回（§2.4 切换异常口径；A-6 注入点）。 */
    private suspend fun restorePrevious(previous: PreviousState, reason: String) {
        SkinFaultHooks.hit(SkinFaultHooks.Point.A6)
        runCatching { store.writeActive(previous.record) }
        runPort { ports.switchRuntime(previous.ref) }
        DiagLog.w(
            "store", "apply_rolled_back",
            mapOf(
                "reason" to reason,
                "toSkinId" to previous.record.skinId,
                "toGen" to previous.record.generation,
            ),
        )
    }

    /**
     * 读当前生效态并归一化为回退目标：active 若处于 pending/损坏
     * （进程内正常不应出现，防御性处理），回退目标取 Last-Good 裁决
     * 结果，与启动对账同口径（[ActiveResolver]）。
     */
    private suspend fun currentPreviousState(): PreviousState {
        val activeRead = store.readActive()
        val lastGoodRead = store.readLastGood()
        val resolution = ActiveResolver.resolve(activeRead, lastGoodRead, ::genStateOnDisk, clock())
        return when (resolution) {
            is ActiveResolver.Resolution.Verified ->
                PreviousState(resolution.record, resolution.record.toGenRef())
            is ActiveResolver.Resolution.RollbackToLastGood ->
                PreviousState(resolution.target, resolution.target.toGenRef())
            else -> PreviousState(ActiveRecord(), null)
        }
    }

    private fun genStateOnDisk(skinId: String, generation: Int): ActiveResolver.GenState {
        val dir = layout.skinGenDir(skinId, generation)
        if (!layout.isSkinGenComplete(dir)) {
            return ActiveResolver.GenState(complete = false, skinJsonHash = null)
        }
        val hash = runCatching { DigestUtil.sha256Hex(File(dir, "skin.json")) }.getOrNull()
        return ActiveResolver.GenState(complete = true, skinJsonHash = hash)
    }

    private fun isTargetValidOnDisk(ref: SkinGenRef): Boolean =
        ActiveResolver.isTargetValid(ref.skinId, ref.generation, ref.hash, ::genStateOnDisk)

    private fun ActiveRecord.toGenRef(): SkinGenRef? {
        val id = skinId ?: return null
        val h = hash ?: return null
        return SkinGenRef(id, h, generation)
    }

    /** Port 调用统一收敛：抛异常与返回 false 同义（失败）。 */
    private fun runPort(block: () -> Boolean): Boolean =
        runCatching(block).getOrDefault(false)
}
