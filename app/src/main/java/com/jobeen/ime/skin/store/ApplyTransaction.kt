package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.fault.SkinFaultHooks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        /**
         * 构建快照（冷路径：含背景预处理复用）；false/异常 = 构建失败（A-1）。
         * 挂起函数：构建含文件读＋解析，调用方不得在主线程同步等它
         *（审计 #2）；实现须把重活移出调用线程（见 SkinApplier）。
         */
        suspend fun buildSnapshot(ref: SkinGenRef): Boolean

        /**
         * 切换运行态引用；ref 为 null 表示回到内置默认皮肤。false/异常 = 切换失败。
         * 挂起函数：切换含背景解码与状态写，调用方不得在主线程同步等它
         *（审计 #2）。
         */
        suspend fun switchRuntime(ref: SkinGenRef?): Boolean

        /**
         * 等待切换后的首帧渲染完成；false = 未完成或异常，按未验证处理（A-3/A-4 口径）。
         * 挂起函数：真实实现须把阻塞等待移出调用线程（见 SkinApplier），
         * 否则调用线程若是主线程，窗口刷新 post 将无法执行、等待必然超时。
         */
        suspend fun awaitFirstRender(): Boolean
    }

    sealed interface Result {
        data class Applied(val ref: SkinGenRef) : Result
        /**
         * 延迟路径暂存（键盘隐藏时应用）：记录已是 PENDING、快照已安装，
         * 下次绘制时验证（通过则晋升 VERIFIED + Last-Good，失败则回滚）。
         * 与已验证 [Applied] 严格区分，UI 不得显示为普通“已应用”。
         */
        data class AppliedPending(val ref: SkinGenRef) : Result
        data class BuildFailed(val ref: SkinGenRef) : Result
        /**
         * 回滚后实际生效的引用：null = 内置默认皮肤。
         * 调用方须据此提示用户实际生效的皮肤，不得只报失败皮肤。
         */
        data class SwitchFailedRolledBack(val ref: SkinGenRef, val restoredRef: SkinGenRef? = null) : Result
        data class FirstRenderFailedRolledBack(val ref: SkinGenRef, val restoredRef: SkinGenRef? = null) : Result
        /**
         * 回滚失败／持久状态不确定（2026-11-05 审计 #1 项 2）：
         * 恢复上一代或默认的任一步失败时返回本结果，绝不返回声称
         * 已恢复的 RolledBack 结果。[stage] 为触发回滚的应用阶段
         * （switch_failed / first_render_failed），[reason] 为失败
         * 的恢复步骤（restore_default_switch_failed /
         * restore_active_write_failed / restore_default_write_failed；
         * 上一好构建／切换失败本身只记日志、随后退默认，默认步骤
         * 成功时仍按已恢复默认计）。调用方不得按默认理解当前
         * 皮肤：运行态与磁盘记录以诊断日志与实际状态为准。
         */
        data class RollbackFailed(val ref: SkinGenRef, val stage: String, val reason: String) : Result
        data class TargetInvalid(val reason: String) : Result
        data class RolledBackToLastGood(val ref: SkinGenRef) : Result
        object RevertedToDefault : Result
        data class RollbackUnavailable(val reason: String) : Result
    }

    /** 当前生效状态的内部视图：回退目标记录与其引用。 */
    private data class PreviousState(val record: ActiveRecord, val ref: SkinGenRef?)

    /**
     * 应用事务本体一律在 IO 线程跑：快照构建、索引／布局解析、
     * 状态文件读写、背景解码全是文件／解码活，主线程只负责
     * 发起与收结果（审计 #2）。首帧等待本身已在 IO 线程
     *（awaitFirstDrawLatch），窗口刷新 post 与回调不受影响。
     */
    suspend fun apply(ref: SkinGenRef): Result =
        withContext(Dispatchers.IO) { applyOnIo(ref) }

    private suspend fun applyOnIo(ref: SkinGenRef): Result {
        if (!isTargetValidOnDisk(ref)) {
            DiagLog.w(
                "store", "apply_target_invalid",
                mapOf("skinId" to ref.skinId, "gen" to ref.generation),
            )
            return Result.TargetInvalid("target_missing_or_hash_mismatch")
        }
        val previous = currentPreviousState()

        SkinFaultHooks.hit(SkinFaultHooks.Point.A1)
        if (!runPort("build", ref) { ports.buildSnapshot(ref) }) {
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

        if (!runPort("switch", ref) { ports.switchRuntime(ref) }) {
            return when (val restored = restorePrevious(previous, "switch_failed", ref)) {
                is RestoreOutcome.Restored ->
                    Result.SwitchFailedRolledBack(ref, restored.ref)
                is RestoreOutcome.Failed ->
                    Result.RollbackFailed(ref, "switch_failed", restored.reason)
            }
        }
        SkinFaultHooks.hit(SkinFaultHooks.Point.A3)

        if (!runPort("first_render", ref) { ports.awaitFirstRender() }) {
            return when (val restored = restorePrevious(previous, "first_render_failed", ref)) {
                is RestoreOutcome.Restored ->
                    Result.FirstRenderFailedRolledBack(ref, restored.ref)
                is RestoreOutcome.Failed ->
                    Result.RollbackFailed(ref, "first_render_failed", restored.reason)
            }
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
    suspend fun rollbackToLastGood(): Result =
        withContext(Dispatchers.IO) { rollbackToLastGoodOnIo() }

    private suspend fun rollbackToLastGoodOnIo(): Result {
        val pointer = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return Result.RollbackUnavailable("no_last_good").also {
                DiagLog.w("store", "rollback_unavailable", mapOf("reason" to "no_last_good"))
            }
        val ref = SkinGenRef(pointer.skinId, pointer.hash, pointer.generation)
        if (!isTargetValidOnDisk(ref)) {
            return Result.RollbackUnavailable("last_good_target_invalid").also {
                DiagLog.w(
                    "store", "rollback_unavailable",
                    mapOf(
                        "reason" to "last_good_target_invalid",
                        "skinId" to ref.skinId, "gen" to ref.generation,
                    ),
                )
            }
        }
        val previous = currentPreviousState()
        if (previous.ref == ref) {
            // 已在 Last-Good 上：只收敛记录，不重复切换
            store.writeActive(
                ActiveRecord(ref.skinId, ref.hash, ref.generation, ApplyState.VERIFIED, clock()),
            )
            return Result.RolledBackToLastGood(ref)
        }
        if (!runPort("rollback_build", ref) { ports.buildSnapshot(ref) }) {
            return Result.RollbackUnavailable("build_failed").also {
                DiagLog.w(
                    "store", "rollback_unavailable",
                    mapOf(
                        "reason" to "build_failed",
                        "skinId" to ref.skinId, "gen" to ref.generation,
                    ),
                )
            }
        }
        if (!runPort("rollback_switch", ref) { ports.switchRuntime(ref) }) {
            return Result.RollbackUnavailable("switch_failed").also {
                DiagLog.w(
                    "store", "rollback_unavailable",
                    mapOf(
                        "reason" to "switch_failed",
                        "skinId" to ref.skinId, "gen" to ref.generation,
                    ),
                )
            }
        }
        if (!runPort("rollback_first_render", ref) { ports.awaitFirstRender() }) {
            // 首渲失败：运行态不得停留在未验证的回退候选上，按
            // 应用失败同口径恢复到回退前状态（重建上一好快照再
            // 切，见 restorePrevious；结果仍如实报回退未完成）。
            // 恢复本身失败也只记日志：返回值已是失败结果，不再
            // 叠加声称恢复的断言（2026-11-05 审计 #1 项 2）。
            val restored = restorePrevious(previous, "rollback_first_render_failed", ref)
            if (restored is RestoreOutcome.Failed) {
                DiagLog.w(
                    "store", "rollback_restore_failed",
                    mapOf("reason" to restored.reason, "skinId" to ref.skinId),
                )
            }
            return Result.RollbackUnavailable("first_render_failed").also {
                DiagLog.w(
                    "store", "rollback_unavailable",
                    mapOf(
                        "reason" to "first_render_failed",
                        "skinId" to ref.skinId, "gen" to ref.generation,
                    ),
                )
            }
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
    suspend fun revertToDefault(): Result =
        withContext(Dispatchers.IO) { revertToDefaultOnIo() }

    private suspend fun revertToDefaultOnIo(): Result {
        if (!runPort("revert_switch", null) { ports.switchRuntime(null) }) {
            return Result.RollbackUnavailable("switch_failed").also {
                DiagLog.w("store", "revert_unavailable", mapOf("reason" to "switch_failed"))
            }
        }
        // 切换成功后写记录失败：运行态已是默认、记录仍指旧皮肤，
        // 不得返回声称已恢复的成功结果（与 restorePrevious 同口径）。
        if (!writeActiveChecked(ActiveRecord(appliedAt = clock()))) {
            return Result.RollbackUnavailable("active_write_failed").also {
                DiagLog.w("store", "revert_unavailable", mapOf("reason" to "active_write_failed"))
            }
        }
        DiagLog.i("store", "revert_to_default")
        return Result.RevertedToDefault
    }

    // ---- 内部 ----

    /**
     * 恢复旧代次（2026-11-05 审计 #1 项 2 重写）：每一步的结果
     * 都参与返回值，任一步失败都不声称恢复成功。
     *
     * 顺序（与 rollbackToLastGood“切换成功后才改记录”同口径，
     * 审计建议的“先确认可用目标、再切运行态、最后持久化”）：
     * 先重建上一好快照、再切运行态、切成功后才写 active 记录。
     * 旧实现先写记录再切换：切换失败时记录已声称上一好、运行
     * 态仍是失败皮肤，且 writeActive 的抛错被 runCatching 吞掉。
     *
     * 返回 [RestoreOutcome.Restored] 仅当运行态与记录双双落在
     * 同一目标（ref = 上一好，null = 内置默认）；上一好重建／
     * 切换失败时退默认，默认的切换／写记录再失败则返回
     * [RestoreOutcome.Failed]（运行态与记录均未获确认，调用方
     * 不得按默认理解）。Last-Good 指针保留，用户可手动再回退。
     */
    private suspend fun restorePrevious(
        previous: PreviousState,
        reason: String,
        failedRef: SkinGenRef,
    ): RestoreOutcome {
        SkinFaultHooks.hit(SkinFaultHooks.Point.A6)
        val outcome: RestoreOutcome = if (previous.ref == null) {
            restoreDefault()
        } else if (
            !runPort("restore_build", previous.ref) { ports.buildSnapshot(previous.ref) }
        ) {
            // 上一好重建失败：退默认（2026-11-03 既有口径）。
            DiagLog.w(
                "store", "restore_previous_failed",
                mapOf("reason" to "restore_build_failed", "skinId" to previous.ref.skinId),
            )
            restoreDefault()
        } else if (
            !runPort("restore_switch", previous.ref) { ports.switchRuntime(previous.ref) }
        ) {
            DiagLog.w(
                "store", "restore_previous_failed",
                mapOf("reason" to "restore_switch_failed", "skinId" to previous.ref.skinId),
            )
            restoreDefault()
        } else if (!writeActiveChecked(previous.record)) {
            // 运行态已切回上一好、记录未落盘：不声称已恢复。
            RestoreOutcome.Failed("restore_active_write_failed")
        } else {
            RestoreOutcome.Restored(previous.ref)
        }
        DiagLog.w(
            "store", "apply_rolled_back",
            mapOf(
                "reason" to reason,
                "failedSkinId" to failedRef.skinId,
                "failedGen" to failedRef.generation,
                "restored" to (outcome is RestoreOutcome.Restored),
                "toSkinId" to (outcome as? RestoreOutcome.Restored)?.ref?.skinId,
                "toGen" to ((outcome as? RestoreOutcome.Restored)?.ref?.generation ?: 0),
                "restoreFailure" to (outcome as? RestoreOutcome.Failed)?.reason,
            ),
        )
        return outcome
    }

    /**
     * 退到内置默认：切换成功后才写默认记录，两步都须成功才算
     * 恢复到默认；任一步失败返回 [RestoreOutcome.Failed]。
     */
    private suspend fun restoreDefault(): RestoreOutcome {
        if (!runPort("restore_switch_default", null) { ports.switchRuntime(null) }) {
            return RestoreOutcome.Failed("restore_default_switch_failed")
        }
        return if (writeActiveChecked(ActiveRecord(appliedAt = clock()))) {
            RestoreOutcome.Restored(null)
        } else {
            RestoreOutcome.Failed("restore_default_write_failed")
        }
    }

    /** 恢复内部结果：已恢复（运行态与记录一致）／失败（均未获确认）。 */
    private sealed interface RestoreOutcome {
        data class Restored(val ref: SkinGenRef?) : RestoreOutcome
        data class Failed(val reason: String) : RestoreOutcome
    }

    /**
     * 写 active 记录并报真实结果：抛错（含磁盘写失败）返回 false，
     * 调用方不得忽略。取消异常原样抛出，不按写失败计。
     */
    private suspend fun writeActiveChecked(record: ActiveRecord): Boolean {
        return try {
            store.writeActive(record)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w(
                "store", "apply_active_write_failed",
                mapOf(
                    "skinId" to record.skinId,
                    "error" to e.javaClass.simpleName,
                ),
                e,
            )
            false
        }
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
            // 保留的 pending 即当前 live 态：新应用失败时回退到它
            // （仍是 pending，下次绘制时继续验证，不凭空回默认）。
            is ActiveResolver.Resolution.KeepPending ->
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

    /**
     * Port 调用统一收敛：抛异常与返回 false 同义（失败）。
     * 异常不再静默吞掉：记 WARN（阶段 + 目标 id + 异常类名），
     * 供导出诊断日志定位端口失败（2026-10-27 硬要求）。
     */
    private suspend fun runPort(stage: String, ref: SkinGenRef?, block: suspend () -> Boolean): Boolean {
        return try {
            block()
        } catch (e: Exception) {
            DiagLog.w(
                "store", "apply_port_failed",
                mapOf(
                    "stage" to stage,
                    "skinId" to ref?.skinId,
                    "gen" to ref?.generation,
                    "error" to e.javaClass.simpleName,
                ),
                e,
            )
            false
        }
    }
}
