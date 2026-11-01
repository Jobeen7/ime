// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.notify.ConditionCurrent
import com.jobeen.ime.skin.notify.ConditionStyleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 板锁定态（审计 G）：两把锁独立翻转、条件键、会话复位。 */
class BoardLockStateTest {

    @Test
    fun locksStartUnlocked() {
        val state = BoardLockState()
        assertFalse(state.symbolicLocked)
        assertFalse(state.emojiLocked)
        assertFalse(state.isLocked(KeyboardTypes.SYMBOLIC))
        assertFalse(state.isLocked(KeyboardTypes.EMOJIS))
    }

    @Test
    fun togglesAreIndependent() {
        val state = BoardLockState()
        assertTrue(state.toggleSymbolic())
        assertTrue(state.symbolicLocked)
        assertFalse(state.emojiLocked)
        assertTrue(state.toggleEmoji())
        assertFalse(state.toggleSymbolic())
        assertFalse(state.symbolicLocked)
        assertTrue(state.emojiLocked)
    }

    @Test
    fun nonBoardTypesNeverLocked() {
        val state = BoardLockState()
        state.toggleSymbolic()
        state.toggleEmoji()
        assertFalse(state.isLocked(KeyboardTypes.PINYIN))
        assertFalse(state.isLocked(KeyboardTypes.ALPHABETIC))
        assertFalse(state.isLocked("custom-board"))
    }

    @Test
    fun conditionCurrentCarriesBothLockKeys() {
        val state = BoardLockState()
        state.toggleEmoji()
        assertEquals(
            mapOf(
                ConditionStyleParser.SYMBOLIC_LOCK_KEY to ConditionCurrent.Bool(false),
                ConditionStyleParser.EMOJI_LOCK_KEY to ConditionCurrent.Bool(true),
            ),
            state.conditionCurrent(),
        )
    }

    @Test
    fun resetClearsBothLocks() {
        val state = BoardLockState()
        state.toggleSymbolic()
        state.toggleEmoji()
        state.reset()
        assertFalse(state.symbolicLocked)
        assertFalse(state.emojiLocked)
    }
}
