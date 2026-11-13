// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段身份变化锁存（V-中1）：IMS 在 onStartInput 判出的四项身份
 * 变化经本锁存留到视图弹出时，由窗口层消费一次、停云端会话；
 * 与复位门（WO32，LatinIME 公式）分开传下，复位逻辑不动。
 *
 * 状态空间（latched）：
 * - 初始 false；note(false) 不置位；note(true) 置位。
 * - consume 读一次即清零；多次 note(true) 累积不丢（仍为真）。
 * - clear 会话结束清零残留，不跨会话。
 * 锁存仍是四项信号：微信各会话四项全同的 restarting 型切换锁存
 * 不到——与 WO32 复位门同限，不是本处漏记。IMS 两入口的接线
 * （onStartInput 记入、onFinishInput 清零、KWV 消费）与 I-M2 的
 * 门值合流（trackInputField 的 gateChanged）需真机验收（微信切框
 * 语音结算作废、restarting 切框停云端会话）。
 */
class FieldIdentityChangeLatchTest {

    @Test
    fun consumeEmptyIsFalse() {
        val latch = FieldIdentityChangeLatch()
        assertFalse(latch.consume())
    }

    @Test
    fun noteTrueThenConsumeOnce() {
        val latch = FieldIdentityChangeLatch()
        latch.note(true)
        assertTrue(latch.consume())
        assertFalse("consume clears the latch", latch.consume())
    }

    @Test
    fun noteFalseDoesNotLatch() {
        val latch = FieldIdentityChangeLatch()
        latch.note(false)
        assertFalse(latch.consume())
    }

    @Test
    fun multipleNotesAccumulateWithoutLoss() {
        val latch = FieldIdentityChangeLatch()
        latch.note(true)
        latch.note(true)
        assertTrue(latch.consume())
        assertFalse(latch.consume())
    }

    @Test
    fun noteFalseAfterTrueKeepsLatch() {
        val latch = FieldIdentityChangeLatch()
        latch.note(true)
        latch.note(false)
        assertTrue("a later false must not drop an earlier change", latch.consume())
    }

    @Test
    fun clearDropsPendingLatch() {
        val latch = FieldIdentityChangeLatch()
        latch.note(true)
        latch.clear()
        assertFalse(latch.consume())
    }

    @Test
    fun clearOnEmptyStaysEmpty() {
        val latch = FieldIdentityChangeLatch()
        latch.clear()
        assertFalse(latch.consume())
    }
}
