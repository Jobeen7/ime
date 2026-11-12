// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import com.jobeen.ime.engine.rime.core.RimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shouldClearSuppressOnDrop 真值表（E-L6）：Commit 置起的
 * suppressNextEmptyCandidates 由随后第一帧 Candidates 消费；该帧若
 * 在队列满时被丢弃，标志须随丢弃同步清掉，否则滞留吞掉下一帧合法
 * 空候选。只有产出 EngineMessage.Candidates 的两类（Candidate
 * 首屏／Menu 菜单）是标志的消费对象，其余类型不触标志。
 */
class SuppressOnDropTest {

    @Test
    fun candidateAndMenuClearTheFlagWhenSet() {
        assertTrue(shouldClearSuppressOnDrop(RimeMessage.MessageType.Candidate, true))
        assertTrue(shouldClearSuppressOnDrop(RimeMessage.MessageType.Menu, true))
    }

    @Test
    fun otherTypesNeverClearTheFlag() {
        val others = RimeMessage.MessageType.entries - setOf(
            RimeMessage.MessageType.Candidate,
            RimeMessage.MessageType.Menu,
        )
        assertEquals("expected every non-consumer type covered",
            RimeMessage.MessageType.entries.size - 2, others.size)
        for (type in others) {
            assertFalse("dropping $type must not clear the flag",
                shouldClearSuppressOnDrop(type, true))
        }
    }

    @Test
    fun unsetFlagNeverClearsForAnyType() {
        for (type in RimeMessage.MessageType.entries) {
            assertFalse("unset flag must stay unset for $type",
                shouldClearSuppressOnDrop(type, false))
        }
    }
}
