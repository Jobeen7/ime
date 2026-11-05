package com.jobeen.ime.ui.screen

import com.jobeen.ime.skin.store.ApplyTransaction
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回滚提示必须报实际生效的皮肤（2026-10-28 修复轮），而非只报失败皮肤。 */
class ApplyResultTextTest {

    private val names = mapOf("a" to "皮肤A", "b" to "皮肤B")
    private val resolve: (String) -> String? = { names[it] }
    private val refA = SkinGenRef("a", "h".repeat(64), 1)
    private val refB = SkinGenRef("b", "h".repeat(64), 2)

    @Test
    fun appliedNamesTarget() {
        assertEquals("已应用：皮肤A", applyResultMessage(ApplyTransaction.Result.Applied(refA), resolve))
    }

    @Test
    fun appliedPendingReportsSwitched() {
        // 2026-11-02 用户定：点下即报“已切换到”（快照已装好），不再报“已暂存”。
        assertEquals(
            "已切换到：皮肤A",
            applyResultMessage(ApplyTransaction.Result.AppliedPending(refA), resolve),
        )
    }

    @Test
    fun firstRenderRollbackNamesRestoredSkin() {
        assertEquals(
            "首次渲染失败，已回退到：皮肤B",
            applyResultMessage(
                ApplyTransaction.Result.FirstRenderFailedRolledBack(refA, refB), resolve,
            ),
        )
    }

    @Test
    fun switchRollbackToDefaultNamesBuiltin() {
        assertEquals(
            "切换失败，已回退到：内置键盘",
            applyResultMessage(
                ApplyTransaction.Result.SwitchFailedRolledBack(refA, null), resolve,
            ),
        )
    }

    @Test
    fun unknownNameFallsBackToId() {
        assertEquals(
            "已应用：unknown-id",
            applyResultMessage(
                ApplyTransaction.Result.Applied(SkinGenRef("unknown-id", "h".repeat(64), 1)),
                resolve,
            ),
        )
    }

    @Test
    fun rollbackFailedNeverClaimsRestoredSkin() {
        // 2026-11-05 审计 #1 项 2：回滚本身失败时不得报“已回退到”。
        assertEquals(
            "回滚失败：皮肤状态不确定，请重启键盘",
            applyResultMessage(
                ApplyTransaction.Result.RollbackFailed(refA, "switch_failed", "restore_default_switch_failed"),
                resolve,
            ),
        )
    }

    @Test
    fun otherResultsMapped() {
        assertEquals("已恢复内置键盘", applyResultMessage(ApplyTransaction.Result.RevertedToDefault, resolve))
        assertEquals("皮肤无效，无法应用", applyResultMessage(ApplyTransaction.Result.TargetInvalid("x"), resolve))
        assertEquals(
            "回退不可用：没有可回退的皮肤",
            applyResultMessage(ApplyTransaction.Result.RollbackUnavailable("no_last_good"), resolve),
        )
        assertEquals(
            "回退不可用：记录写入失败",
            applyResultMessage(ApplyTransaction.Result.RollbackUnavailable("active_write_failed"), resolve),
        )
    }
}
