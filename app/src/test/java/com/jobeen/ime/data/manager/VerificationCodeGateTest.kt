// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 5.2 门禁：七门全为真才展示，每门各一例。 */
class VerificationCodeGateTest {

    private fun show(
        listening: Boolean = true,
        manual: Boolean = false,
        prompt: Boolean = true,
        length: Int = 20,
        toolbar: Boolean = true,
        extracted: Boolean = true,
        written: Boolean = true,
    ) = VerificationCodeGate.shouldShow(
        listening, manual, prompt, length, toolbar, extracted, written
    )

    @Test
    fun allGatesPass() {
        assertTrue(show())
    }

    @Test
    fun listeningOffBlocks() {
        assertFalse(show(listening = false))
    }

    @Test
    fun manualSourceBlocks() {
        assertFalse(show(manual = true))
    }

    @Test
    fun promptOffBlocks() {
        assertFalse(show(prompt = false))
    }

    @Test
    fun textOver300Blocks() {
        assertTrue(show(length = 300))
        assertFalse(show(length = 301))
    }

    @Test
    fun noToolbarBlocks() {
        assertFalse(show(toolbar = false))
    }

    @Test
    fun extractFailedBlocks() {
        assertFalse(show(extracted = false))
    }

    @Test
    fun writeFailedBlocks() {
        assertFalse(show(written = false))
    }
}
