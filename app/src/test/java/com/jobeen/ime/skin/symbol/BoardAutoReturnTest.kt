// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.skin.model.KeyboardTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 板提交后自动返回决策（审计 G）：未锁定的两块板才返回。 */
class BoardAutoReturnTest {

    @Test
    fun unlockedBoardsReturn() {
        assertTrue(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.SYMBOLIC, locked = false),
        )
        assertTrue(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.EMOJIS, locked = false),
        )
    }

    @Test
    fun lockedBoardsStay() {
        assertFalse(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.SYMBOLIC, locked = true),
        )
        assertFalse(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.EMOJIS, locked = true),
        )
    }

    @Test
    fun nonBoardTypesNeverReturn() {
        assertFalse(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.PINYIN, locked = false),
        )
        assertFalse(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.NUMERIC, locked = false),
        )
    }
}
