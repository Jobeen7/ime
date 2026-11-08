// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 展开候选页返回（施工单 10 H2-6）：只认 BACK 下按首击＋展开态。 */
class ExpandBackPolicyTest {

    @Test
    fun expanded_backDownFirstPress_collapses() {
        assertTrue(
            ExpandBackPolicy.shouldCollapseOnBack(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.ACTION_DOWN,
                0,
                isExpanded = true,
            ),
        )
    }

    @Test
    fun notExpanded_backDown_doesNotCollapse() {
        assertFalse(
            ExpandBackPolicy.shouldCollapseOnBack(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.ACTION_DOWN,
                0,
                isExpanded = false,
            ),
        )
    }

    @Test
    fun expanded_otherKey_doesNotCollapse() {
        assertFalse(
            ExpandBackPolicy.shouldCollapseOnBack(
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.ACTION_DOWN,
                0,
                isExpanded = true,
            ),
        )
    }

    @Test
    fun expanded_backUp_doesNotCollapse() {
        assertFalse(
            ExpandBackPolicy.shouldCollapseOnBack(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.ACTION_UP,
                0,
                isExpanded = true,
            ),
        )
    }

    @Test
    fun expanded_backRepeat_doesNotCollapse() {
        assertFalse(
            ExpandBackPolicy.shouldCollapseOnBack(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.ACTION_DOWN,
                1,
                isExpanded = true,
            ),
        )
    }
}
