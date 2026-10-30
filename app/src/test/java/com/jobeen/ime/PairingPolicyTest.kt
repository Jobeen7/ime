// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.data.PairCommitQueue
import com.jobeen.ime.input.PairingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 符号成对：开关/组字/密码门、引号前文守卫、全角对、侧队列 FIFO。 */
class PairingPolicyTest {

    @Test
    fun pairText_mapsOpeners() {
        assertEquals("()", PairingPolicy.pairText('('))
        assertEquals("[]", PairingPolicy.pairText('['))
        assertEquals("（）", PairingPolicy.pairText('（'))
        assertEquals("《》", PairingPolicy.pairText('《'))
        assertEquals(null, PairingPolicy.pairText('a'))
        assertEquals(null, PairingPolicy.pairText(')'))
    }

    @Test
    fun shouldPair_gates() {
        assertFalse(PairingPolicy.shouldPair("(", false, false, false, ""))
        assertFalse(PairingPolicy.shouldPair("(", true, true, false, ""))
        assertFalse(PairingPolicy.shouldPair("(", true, false, true, ""))
        assertFalse(PairingPolicy.shouldPair("ab", true, false, false, ""))
        assertFalse(PairingPolicy.shouldPair("x", true, false, false, ""))
        assertTrue(PairingPolicy.shouldPair("(", true, false, false, ""))
        assertTrue(PairingPolicy.shouldPair("【", true, false, false, "hello"))
    }

    @Test
    fun shouldPair_quoteGuard() {
        assertTrue(PairingPolicy.shouldPair("\"", true, false, false, ""))
        assertTrue(PairingPolicy.shouldPair("\"", true, false, false, " "))
        assertTrue(PairingPolicy.shouldPair("\"", true, false, false, "，"))
        assertFalse(PairingPolicy.shouldPair("\"", true, false, false, null))
        assertFalse(PairingPolicy.shouldPair("\"", true, false, false, "a"))
        assertFalse(PairingPolicy.shouldPair("'", true, false, false, "中"))
        assertTrue(PairingPolicy.shouldPair("'", true, false, false, "（"))
    }

    @Test
    fun pairCommitQueue_fifoAndEmptyZero() {
        assertEquals(0, PairCommitQueue.poll())
        PairCommitQueue.offer(1)
        PairCommitQueue.offer(1)
        assertEquals(1, PairCommitQueue.poll())
        assertEquals(1, PairCommitQueue.poll())
        assertEquals(0, PairCommitQueue.poll())
        PairCommitQueue.offer(1)
        PairCommitQueue.removeLast()
        assertEquals(0, PairCommitQueue.poll())
    }
}
