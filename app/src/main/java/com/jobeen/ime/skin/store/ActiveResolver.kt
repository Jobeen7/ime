package com.jobeen.ime.skin.store

/**
 * 生效记录的启动裁决（详设 §2.4 状态机 + §2.8.2 的纯逻辑部分）：
 *
 * - active 缺失/损坏 → 无 v3 生效（走旧路径，§2.8.2）
 * - state=pending → 上次应用未验证完成，回退 Last-Good（R5 两态口径）
 * - state=verified 但所指代次缺失/不完整/hash 不符 → 同样回退（A-7）
 * - Last-Good 本身无效 → 回退内置默认（skinId null 记录）
 *
 * 代次状态经 [genState] 回调取得，本类不碰文件系统，可直接单测。
 * 裁决结果的落盘由调用方（对账）执行。
 */
object ActiveResolver {

    /** 某代次在磁盘上的状态：是否完整 + skin.json 实际内容 hash。 */
    data class GenState(val complete: Boolean, val skinJsonHash: String?)

    sealed interface Resolution {
        /** active 有效，运行态可直接采用。 */
        data class Verified(val record: ActiveRecord) : Resolution

        /** 无 v3 皮肤生效（缺失/损坏/未设），走现行内置主题路径。 */
        data class NoSkin(val reason: String) : Resolution

        /** 回退到 Last-Good：调用方须把 [target] 写回 active.json（verified 态）。 */
        data class RollbackToLastGood(val target: ActiveRecord, val reason: String) : Resolution

        /** 无可用 Last-Good：调用方须把 active.json 写为 skinId=null。 */
        data class RollbackToDefault(val reason: String) : Resolution
    }

    fun resolve(
        active: SkinLibraryStore.ReadResult<ActiveRecord>,
        lastGood: SkinLibraryStore.ReadResult<LastGoodPointer>,
        genState: (skinId: String, generation: Int) -> GenState,
        now: Long,
    ): Resolution {
        val record = when (active) {
            is SkinLibraryStore.ReadResult.Ok -> active.value
            SkinLibraryStore.ReadResult.Missing -> return Resolution.NoSkin("active_missing")
            is SkinLibraryStore.ReadResult.Corrupt -> return Resolution.NoSkin("active_corrupt")
        }
        val skinId = record.skinId ?: return Resolution.NoSkin("no_skin_selected")
        return when (record.state) {
            ApplyState.PENDING ->
                lastGoodResolution(lastGood, genState, now, "pending_at_startup")
            ApplyState.VERIFIED ->
                if (isTargetValid(skinId, record.generation, record.hash, genState)) {
                    Resolution.Verified(record)
                } else {
                    lastGoodResolution(lastGood, genState, now, "active_target_invalid")
                }
        }
    }

    /** 校验 Last-Good 指针目标并给出回退裁决。 */
    fun lastGoodResolution(
        lastGood: SkinLibraryStore.ReadResult<LastGoodPointer>,
        genState: (skinId: String, generation: Int) -> GenState,
        now: Long,
        reason: String,
    ): Resolution {
        val pointer = (lastGood as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return Resolution.RollbackToDefault(reason)
        return if (isTargetValid(pointer.skinId, pointer.generation, pointer.hash, genState)) {
            Resolution.RollbackToLastGood(
                target = ActiveRecord(
                    skinId = pointer.skinId,
                    hash = pointer.hash,
                    generation = pointer.generation,
                    state = ApplyState.VERIFIED,
                    appliedAt = now,
                ),
                reason = reason,
            )
        } else {
            Resolution.RollbackToDefault(reason)
        }
    }

    /** 手动回退/恢复时校验某个引用是否仍可用（代次完整且 hash 一致）。 */
    fun isTargetValid(
        skinId: String,
        generation: Int,
        expectedHash: String?,
        genState: (skinId: String, generation: Int) -> GenState,
    ): Boolean {
        if (expectedHash == null) return false
        val state = genState(skinId, generation)
        return state.complete && state.skinJsonHash == expectedHash
    }
}
