// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 延迟重投的代次判定（I-M4）：旧帧不得覆盖新表。 */
class KawaiiPanelGenerationTest {

    @Test
    fun currentGenerationApplies() {
        assertTrue(shouldApplyDeferredCandidates(3, 3))
        assertTrue(shouldApplyDeferredCandidates(0, 0))
    }

    @Test
    fun staleGenerationDrops() {
        // 期间已有更新的 setCandidates 进入：旧帧过期。
        assertFalse(shouldApplyDeferredCandidates(2, 3))
        assertFalse(shouldApplyDeferredCandidates(0, 1))
    }

    @Test
    fun futureGenerationNeverApplies() {
        // 捕获代次超前于当前代次（不可能出现）同样不执行。
        assertFalse(shouldApplyDeferredCandidates(4, 3))
    }
}
