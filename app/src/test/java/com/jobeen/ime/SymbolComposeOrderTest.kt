// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.SymbolComposeOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 符号组字顺序（G45）：组字进行中先提交选中词、符号随后；无组字时符号直送。 */
class SymbolComposeOrderTest {

    @Test
    fun emptyRawInput_commitsSymbolDirectly() {
        assertFalse(SymbolComposeOrder.shouldCommitSelectionFirst(""))
    }

    @Test
    fun nonEmptyRawInput_commitsSelectionFirst() {
        // 拼音码、九宫格数字码、单字符输入皆为组字进行中。
        assertTrue(SymbolComposeOrder.shouldCommitSelectionFirst("ni"))
        assertTrue(SymbolComposeOrder.shouldCommitSelectionFirst("nihao"))
        assertTrue(SymbolComposeOrder.shouldCommitSelectionFirst("526"))
        assertTrue(SymbolComposeOrder.shouldCommitSelectionFirst("n"))
    }
}
