// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** combine delay 切分（纯函数）：首个 delay 处断开，前缀同步、余部延时。 */
class CombineDelaySplitTest {

    private fun commit(text: String) = KeyboardAction.CommitAction(text)

    @Test
    fun `no delay returns whole list as prefix`() {
        val actions = listOf(commit("a"), commit("b"))
        val split = splitCombineAtDelay(actions)
        assertEquals(actions, split.prefix)
        assertNull(split.delayMs)
        assertTrue(split.rest.isEmpty())
    }

    @Test
    fun `first delay splits prefix delay and rest`() {
        val actions = listOf(
            commit("a"),
            KeyboardAction.DelayAction(200),
            commit("b"),
            KeyboardAction.DelayAction(100),
            commit("c"),
        )
        val split = splitCombineAtDelay(actions)
        assertEquals(listOf(commit("a")), split.prefix)
        assertEquals(200, split.delayMs)
        // 余部含第二个 delay：续分发时递归再切。
        assertEquals(3, split.rest.size)
        assertTrue(split.rest[1] is KeyboardAction.DelayAction)
    }

    @Test
    fun `delay first gives empty prefix`() {
        val actions = listOf(
            KeyboardAction.DelayAction(50),
            commit("a"),
        )
        val split = splitCombineAtDelay(actions)
        assertTrue(split.prefix.isEmpty())
        assertEquals(50, split.delayMs)
        assertEquals(listOf(commit("a")), split.rest)
    }

    @Test
    fun `delay last gives empty rest`() {
        val actions = listOf(
            commit("a"),
            KeyboardAction.DelayAction(0),
        )
        val split = splitCombineAtDelay(actions)
        assertEquals(listOf(commit("a")), split.prefix)
        assertEquals(0, split.delayMs)
        assertTrue(split.rest.isEmpty())
    }

    @Test
    fun `empty list splits empty`() {
        val split = splitCombineAtDelay(emptyList())
        assertTrue(split.prefix.isEmpty())
        assertNull(split.delayMs)
        assertTrue(split.rest.isEmpty())
    }
}
