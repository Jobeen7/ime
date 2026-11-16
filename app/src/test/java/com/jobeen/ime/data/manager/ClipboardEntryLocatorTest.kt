// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertEquals
import org.junit.Test

/** `clipboard?id=` 落点定位（WO52 4.2 遗留项）：纯函数单测。 */
class ClipboardEntryLocatorTest {

    private fun entry(id: Long, text: String) =
        ClipboardManager.Entry(text = text, timestamp = 100L, id = id)

    @Test
    fun `finds first entry by id`() {
        val entries = listOf(entry(1L, "a"), entry(2L, "b"), entry(3L, "c"))
        assertEquals(0, ClipboardEntryLocator.indexOf(entries, 1L))
        assertEquals(1, ClipboardEntryLocator.indexOf(entries, 2L))
        assertEquals(2, ClipboardEntryLocator.indexOf(entries, 3L))
    }

    @Test
    fun `unknown id returns minus one`() {
        val entries = listOf(entry(1L, "a"), entry(2L, "b"))
        assertEquals(-1, ClipboardEntryLocator.indexOf(entries, 99L))
    }

    @Test
    fun `empty list returns minus one`() {
        assertEquals(-1, ClipboardEntryLocator.indexOf(emptyList(), 1L))
    }

    @Test
    fun `non-positive id never matches`() {
        val entries = listOf(entry(1L, "a"), entry(2L, "b"))
        assertEquals(-1, ClipboardEntryLocator.indexOf(entries, 0L))
        assertEquals(-1, ClipboardEntryLocator.indexOf(entries, -5L))
    }

    @Test
    fun `entries with unknown id zero never match a positive id`() {
        val entries = listOf(entry(0L, "a"), entry(0L, "b"))
        assertEquals(-1, ClipboardEntryLocator.indexOf(entries, 1L))
    }

    @Test
    fun `duplicate ids return the first match`() {
        val entries = listOf(entry(7L, "a"), entry(7L, "b"))
        assertEquals(0, ClipboardEntryLocator.indexOf(entries, 7L))
    }
}
