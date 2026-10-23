package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 低内存档位判定与降级策略（详设 §6，暂定阈值）。 */
class MemoryTierTest {

    @Test
    fun tierBoundaries() {
        assertEquals(MemoryTier.NORMAL, MemoryTier.of(isLowRamDevice = false, memoryClassMb = 512))
        assertEquals(MemoryTier.NORMAL, MemoryTier.of(isLowRamDevice = false, memoryClassMb = 193))
        assertEquals(MemoryTier.LOW, MemoryTier.of(isLowRamDevice = false, memoryClassMb = 192))
        assertEquals(MemoryTier.LOW, MemoryTier.of(isLowRamDevice = true, memoryClassMb = 512))
        assertEquals(MemoryTier.LOW, MemoryTier.of(isLowRamDevice = false, memoryClassMb = 129))
        assertEquals(MemoryTier.EXTREME, MemoryTier.of(isLowRamDevice = false, memoryClassMb = 128))
        assertEquals(MemoryTier.EXTREME, MemoryTier.of(isLowRamDevice = true, memoryClassMb = 64))
    }

    @Test
    fun policyLadder() {
        val normal = DegradationPolicy.forTier(MemoryTier.NORMAL)
        assertFalse(normal.blurDisabled)
        assertEquals(1f, normal.backgroundScale)
        assertEquals(32L * 1024 * 1024, normal.audioBudgetBytes)
        assertTrue(normal.degradations().isEmpty())

        val low = DegradationPolicy.forTier(MemoryTier.LOW)
        assertTrue(low.blurDisabled)
        assertEquals(1f, low.backgroundScale)
        assertEquals(16L * 1024 * 1024, low.audioBudgetBytes)
        assertEquals(setOf(Degradation.BLUR_DISABLED), low.degradations())

        val extreme = DegradationPolicy.forTier(MemoryTier.EXTREME)
        assertTrue(extreme.blurDisabled)
        assertEquals(0.5f, extreme.backgroundScale)
        assertEquals(
            setOf(Degradation.BLUR_DISABLED, Degradation.BACKGROUND_DOWNSAMPLED),
            extreme.degradations(),
        )
    }

    @Test
    fun trimEscalationStepsDownOneTierCapped() {
        assertEquals(MemoryTier.LOW, MemoryTier.NORMAL.escalated())
        assertEquals(MemoryTier.EXTREME, MemoryTier.LOW.escalated())
        assertEquals(MemoryTier.EXTREME, MemoryTier.EXTREME.escalated())
    }
}
