// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.ForwardDeleteDecision
import com.jobeen.ime.engine.ForwardDeletePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前向删除（重点候选 8-9）：组字中只删编码右侧、宿主侧 emoji 簇单位。 */
class ForwardDeleteDecisionTest {

    @Test
    fun composing_caretBeforeCodeRight_deletes() {
        assertTrue(ForwardDeleteDecision.shouldDeleteCodeRight("nihao", 0))
        assertTrue(ForwardDeleteDecision.shouldDeleteCodeRight("nihao", 2))
        assertTrue(ForwardDeleteDecision.shouldDeleteCodeRight("你好", 1))
    }

    @Test
    fun composing_caretAtEndOrBeyond_isNoop() {
        assertFalse(ForwardDeleteDecision.shouldDeleteCodeRight("nihao", 5))
        assertFalse(ForwardDeleteDecision.shouldDeleteCodeRight("nihao", 6))
        assertFalse(ForwardDeleteDecision.shouldDeleteCodeRight("nihao", -1))
        assertFalse(ForwardDeleteDecision.shouldDeleteCodeRight("", 0))
    }

    @Test
    fun host_firstCluster_emojiIsOneUnit() {
        val emoji = ForwardDeletePlanner.firstCluster("😀a")
        assertEquals(1, emoji?.codePoints)
        assertEquals(2, emoji?.utf16Length)
        val plain = ForwardDeletePlanner.firstCluster("ab")
        assertEquals(1, plain?.codePoints)
        assertEquals(1, plain?.utf16Length)
        assertNull(ForwardDeletePlanner.firstCluster(""))
    }

    @Test
    fun host_firstCluster_flagIsOneUnit() {
        // 国旗由两个区域指示符（2 码点、4 UTF-16 单位）组成一个字素簇，不得劈开。
        val flag = ForwardDeletePlanner.firstCluster("🇨🇳x")
        assertEquals(2, flag?.codePoints)
        assertEquals(4, flag?.utf16Length)
    }
}
