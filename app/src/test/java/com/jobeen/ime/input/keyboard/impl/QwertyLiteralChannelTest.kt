package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * commit_direct 字面通道（裁定 B2 / 详设 §3.4-5 验收样本）：
 * 在 Qwerty 变换层，LiteralCommitAction 必须绕行标点全半角
 * 转换与 Caps 大写；出厂 CommitAction / KeySequenceAction
 * 的既有变换逐项不变。T9/T15 无变换层，字面性天然成立。
 */
class QwertyLiteralChannelTest {

    private fun transform(
        action: KeyboardAction,
        caps: QwertyKeyboard.CapsState = QwertyKeyboard.CapsState.None,
        fullWidth: Boolean = true,
    ) = QwertyKeyboard.transformAction(action, caps, fullWidth)

    @Test
    fun commitDirectCommaStaysLiteralUnderFullWidth() {
        val (forwarded, capsTarget) = transform(KeyboardAction.LiteralCommitAction(","))
        assertEquals(KeyboardAction.LiteralCommitAction(","), forwarded)
        assertNull(capsTarget)
    }

    @Test
    fun factoryCommaStillConvertsUnderFullWidth() {
        val (forwarded, _) = transform(KeyboardAction.CommitAction(","))
        assertEquals(KeyboardAction.CommitAction("，"), forwarded)
        val (half, _) = transform(KeyboardAction.CommitAction(","), fullWidth = false)
        assertEquals(KeyboardAction.CommitAction(","), half)
    }

    @Test
    fun commitDirectIgnoresCaps() {
        val (forwarded, capsTarget) = transform(
            KeyboardAction.LiteralCommitAction("a"),
            caps = QwertyKeyboard.CapsState.Lock,
        )
        assertEquals(KeyboardAction.LiteralCommitAction("a"), forwarded)
        assertNull(capsTarget) // 字面提交不消费 Caps 状态
    }

    @Test
    fun textTypeStillParticipatesInCaps() {
        val (once, onceTarget) = transform(
            KeyboardAction.KeySequenceAction("a"),
            caps = QwertyKeyboard.CapsState.Once,
        )
        assertEquals(KeyboardAction.KeySequenceAction("A"), once)
        assertEquals(QwertyKeyboard.CapsState.None, onceTarget) // Once 用后即消

        val (lock, lockTarget) = transform(
            KeyboardAction.KeySequenceAction("a"),
            caps = QwertyKeyboard.CapsState.Lock,
        )
        assertEquals(KeyboardAction.KeySequenceAction("A"), lock)
        assertNull(lockTarget) // Lock 保持

        val (none, _) = transform(KeyboardAction.KeySequenceAction("a"))
        assertEquals(KeyboardAction.KeySequenceAction("a"), none)
    }

    @Test
    fun capsActionCyclesAndIsConsumedInPlace() {
        val cycle = listOf(
            QwertyKeyboard.CapsState.None to QwertyKeyboard.CapsState.Once,
            QwertyKeyboard.CapsState.Once to QwertyKeyboard.CapsState.Lock,
            QwertyKeyboard.CapsState.Lock to QwertyKeyboard.CapsState.None,
        )
        for ((from, to) in cycle) {
            val (forwarded, capsTarget) = transform(KeyboardAction.CapsAction, caps = from)
            assertNull(forwarded)
            assertEquals(to, capsTarget)
        }
    }

    @Test
    fun otherActionsPassThroughUnchanged() {
        val (forwarded, _) = transform(KeyboardAction.DeleteForwardAction)
        assertEquals(KeyboardAction.DeleteForwardAction, forwarded)
        val (backspace, _) = transform(KeyboardAction.BackspaceAction)
        assertEquals(KeyboardAction.BackspaceAction, backspace)
    }
}
