package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分发期状态解析（审计 E：preedit/uppercased/滑动方向）与
 * 按住上档纯决策。均不建视图，只验纯函数。
 */
class StateActionResolutionTest {

    private val normal = KeyboardAction.CommitAction("a")
    private val preedit = KeyboardAction.CommitAction("p")
    private val uppercased = KeyboardAction.CommitAction("A")

    private fun press(
        preeditAction: KeyboardAction? = null,
        uppercasedAction: KeyboardAction? = null,
    ) = KeyDef.Behavior.Press(normal, preeditAction, uppercasedAction)

    @Test
    fun pressResolutionPriorityIsPreeditThenUppercasedThenNormal() {
        // 两态齐全时组合胜
        assertEquals(
            preedit,
            resolvePressAction(press(preedit, uppercased), true, true),
        )
        // 组合无 preedit 时大写仍生效（两态独立）
        assertEquals(
            uppercased,
            resolvePressAction(press(null, uppercased), true, true),
        )
        // 仅大写
        assertEquals(
            uppercased,
            resolvePressAction(press(null, uppercased), false, true),
        )
        // 均无时普通
        assertEquals(
            normal,
            resolvePressAction(press(), false, false),
        )
        assertEquals(
            normal,
            resolvePressAction(press(), true, true),
        )
    }

    @Test
    fun swipeDirectionMatchesLegacyUpBranch() {
        // 横向：有左右动作且 |X| >= |Y|（含平手）走横向
        assertEquals(SwipeDirection.LEFT, swipeDirectionFor(true, -5, 3))
        assertEquals(SwipeDirection.RIGHT, swipeDirectionFor(true, 5, 5))
        // 无左右动作时纵向
        assertEquals(SwipeDirection.UP, swipeDirectionFor(false, -9, -3))
        // totalX 为 0 时不走横向
        assertEquals(SwipeDirection.DOWN, swipeDirectionFor(true, 0, 4))
        // 零位移无方向
        assertNull(swipeDirectionFor(true, 0, 0))
    }

    @Test
    fun swipeResolutionPrefersPreeditPerDirection() {
        val swipe = KeyDef.Behavior.Swipe(
            upAction = normal,
            downAction = null,
            leftAction = null,
            rightAction = null,
            preeditUpAction = preedit,
        )
        assertEquals(preedit, resolveSwipeAction(swipe, SwipeDirection.UP, true))
        assertEquals(normal, resolveSwipeAction(swipe, SwipeDirection.UP, false))
        assertNull(resolveSwipeAction(swipe, SwipeDirection.DOWN, false))
        assertNull(resolveSwipeAction(swipe, null, true))
    }

    @Test
    fun momentaryShiftTransitions() {
        val none = QwertyKeyboard.CapsState.None
        val once = QwertyKeyboard.CapsState.Once
        val lock = QwertyKeyboard.CapsState.Lock
        val momentary = QwertyKeyboard.CapsState.Momentary
        // 仅 None 按下进 Momentary
        assertEquals(momentary, QwertyKeyboard.momentaryDownTarget(none))
        assertNull(QwertyKeyboard.momentaryDownTarget(once))
        assertNull(QwertyKeyboard.momentaryDownTarget(lock))
        assertNull(QwertyKeyboard.momentaryDownTarget(momentary))
        // 仅激活且仍 Momentary 时抬手回 None
        assertEquals(none, QwertyKeyboard.momentaryUpTarget(momentary, true))
        assertNull(QwertyKeyboard.momentaryUpTarget(momentary, false))
        assertNull(QwertyKeyboard.momentaryUpTarget(once, true))
        assertNull(QwertyKeyboard.momentaryUpTarget(lock, true))
    }

    @Test
    fun transformActionMomentaryUppercasesWithoutConsuming() {
        val seq = KeyboardAction.KeySequenceAction("a")
        val (action, target) = QwertyKeyboard.transformAction(
            seq, QwertyKeyboard.CapsState.Momentary, false,
        )
        assertEquals(KeyboardAction.KeySequenceAction("A"), action)
        assertNull(target)
        // 按住期间 caps 键不改状态
        val (capsAction, capsTarget) = QwertyKeyboard.transformAction(
            KeyboardAction.CapsAction,
            QwertyKeyboard.CapsState.Momentary,
            false,
        )
        assertNull(capsAction)
        assertEquals(QwertyKeyboard.CapsState.Momentary, capsTarget)
    }
}
