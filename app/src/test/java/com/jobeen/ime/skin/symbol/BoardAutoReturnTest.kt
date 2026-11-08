// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.skin.model.KeyboardTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 板提交后自动返回决策（审计 G）：板锁已删，三块板提交恒返回。 */
class BoardAutoReturnTest {

    @Test
    fun boardsAlwaysReturn() {
        assertTrue(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.SYMBOLIC),
        )
        assertTrue(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.EMOJIS),
        )
        assertTrue(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.KAOMOJI),
        )
    }

    @Test
    fun nonBoardTypesNeverReturn() {
        assertFalse(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.PINYIN),
        )
        assertFalse(
            BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.NUMERIC),
        )
    }
}
