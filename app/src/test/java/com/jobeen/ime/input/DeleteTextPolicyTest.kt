// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除选中文本纯判定（#deleteText）：仅非组字＋非空选区可删。 */
class DeleteTextPolicyTest {

    @Test
    fun deletesNonEmptySelectionWhenNotComposing() {
        assertTrue(DeleteTextPolicy.shouldDelete(false, "选中"))
    }

    @Test
    fun neverDeletesWhileComposing() {
        assertFalse(DeleteTextPolicy.shouldDelete(true, "选中"))
        assertFalse(DeleteTextPolicy.shouldDelete(true, ""))
        assertFalse(DeleteTextPolicy.shouldDelete(true, null))
    }

    @Test
    fun noSelectionIsNoOpNeverBackspace() {
        assertFalse(DeleteTextPolicy.shouldDelete(false, null))
        assertFalse(DeleteTextPolicy.shouldDelete(false, ""))
    }
}
