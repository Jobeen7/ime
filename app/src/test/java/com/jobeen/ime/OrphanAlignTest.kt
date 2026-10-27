// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.OrphanDeployTarget
import com.jobeen.ime.engine.orphanDeployNeedsRealign
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超时孤儿对齐判定（案-O5）的规格测试，规格来源（非实现反推）：
 * - 孤儿 = 等待方已放弃等待（超时/取消），作业仍在后台编。正常完成
 *   的部署不存在“回滚后指针不一致”，任何快照变化都不触发重编。
 * - 无回滚的超时（如 FAB 部署：指针与镜像全程不动）native 表即当前
 *   目标，重编纯属浪费一次全量编译，必须不触发。
 * - 回滚分两形，均须当场重编当前指针目录，不留到下次部署：切换/导入
 *   回滚指针＋镜像（目录名变）；管理页回滚同目录 custom＋镜像
 *   （目录名不变、镜像变）。镜像顺序变也算变：首位方案由顺序定。
 * - 快照读不到时无法证明回滚发生，盲目重编会把无回滚超时拖进双倍
 *   编译，故按不变处理、走旧行为。
 * 引擎本体依赖 Android/native，无法在单元测试中实例化，故判定以纯
 * 函数抽出、由 deployAndAwait 的孤儿作业收尾共用。
 */
class OrphanAlignTest {

    private val before = OrphanDeployTarget(dir = "new-dir", mirror = "a,b")

    @Test
    fun normalCompletion_neverRealignsEvenWhenTargetChanged() {
        assertFalse(
            orphanDeployNeedsRealign(
                waitingGaveUp = false,
                before = before,
                after = OrphanDeployTarget(dir = "old-dir", mirror = "c"),
            )
        )
    }

    @Test
    fun normalCompletion_neverRealignsWhenTargetUnchanged() {
        assertFalse(
            orphanDeployNeedsRealign(
                waitingGaveUp = false,
                before = before,
                after = before,
            )
        )
    }

    @Test
    fun orphan_unchangedTargetDoesNotRealign() {
        assertFalse(
            orphanDeployNeedsRealign(
                waitingGaveUp = true,
                before = before,
                after = before,
            )
        )
    }

    @Test
    fun orphan_pointerRollbackRealigns() {
        assertTrue(
            orphanDeployNeedsRealign(
                waitingGaveUp = true,
                before = before,
                after = OrphanDeployTarget(dir = "old-dir", mirror = "c"),
            )
        )
    }

    @Test
    fun orphan_mirrorOnlyRollbackRealigns() {
        assertTrue(
            orphanDeployNeedsRealign(
                waitingGaveUp = true,
                before = before,
                after = OrphanDeployTarget(dir = "new-dir", mirror = "a"),
            )
        )
    }

    @Test
    fun orphan_mirrorOrderChangeRealigns() {
        assertTrue(
            orphanDeployNeedsRealign(
                waitingGaveUp = true,
                before = before,
                after = OrphanDeployTarget(dir = "new-dir", mirror = "b,a"),
            )
        )
    }

    @Test
    fun orphan_missingBeforeSnapshotDoesNotRealign() {
        assertFalse(
            orphanDeployNeedsRealign(
                waitingGaveUp = true,
                before = null,
                after = OrphanDeployTarget(dir = "old-dir", mirror = "c"),
            )
        )
    }

    @Test
    fun orphan_missingAfterSnapshotDoesNotRealign() {
        assertFalse(
            orphanDeployNeedsRealign(
                waitingGaveUp = true,
                before = before,
                after = null,
            )
        )
    }
}
