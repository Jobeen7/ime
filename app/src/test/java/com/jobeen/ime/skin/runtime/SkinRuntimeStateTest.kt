// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.skin.notify.ConditionCurrent
import com.jobeen.ime.skin.notify.ConditionStyle
import com.jobeen.ime.skin.notify.ConditionValue
import com.jobeen.ime.skin.notify.NotificationEvent
import com.jobeen.ime.skin.notify.NotificationSource
import com.jobeen.ime.skin.notify.SkinNotification
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 皮肤运行态（接线轮 R1/R2）：锁定变化检测、条件门控集、监听触发。 */
class SkinRuntimeStateTest {

    private fun notification(
        source: NotificationSource,
        key: String?,
        value: String?,
        target: String,
    ) = SkinNotification(
        source = source, matchKey = key, matchValue = value, targetStyle = target,
        backgroundStyle = null, foregroundStyle = null, action = null,
        swipeUpAction = null, swipeDownAction = null,
    )

    @Test
    fun onEvent_changeDetection() {
        SkinRuntimeState.reset(
            notifications = listOf(
                notification(NotificationSource.RIME_OPTION, "ascii_mode", "true", "accent"),
                notification(NotificationSource.RIME_OPTION, "ascii_mode", "false", "accent"),
                notification(NotificationSource.PREEDIT, null, null, "pre"),
            ),
        )
        var calls = 0
        val listener: () -> Unit = { calls++ }
        SkinRuntimeState.addStateChangedListener(listener)
        try {
            assertTrue(
                SkinRuntimeState.onEvent(
                    NotificationEvent(NotificationSource.RIME_OPTION, "ascii_mode", "true"),
                ),
            )
            assertEquals(1, calls)
            // 重复锁定：无变化，不通知
            assertFalse(
                SkinRuntimeState.onEvent(
                    NotificationEvent(NotificationSource.RIME_OPTION, "ascii_mode", "true"),
                ),
            )
            assertEquals(1, calls)
            // 失配事件：无变化
            assertFalse(
                SkinRuntimeState.onEvent(
                    NotificationEvent(NotificationSource.RIME_OPTION, "full_shape", "true"),
                ),
            )
            assertEquals(1, calls)
            // 同目标新命中：变化
            assertTrue(
                SkinRuntimeState.onEvent(
                    NotificationEvent(NotificationSource.RIME_OPTION, "ascii_mode", "false"),
                ),
            )
            assertEquals(2, calls)
            // 预编辑事件：双空命中
            assertTrue(
                SkinRuntimeState.onEvent(
                    NotificationEvent(NotificationSource.PREEDIT, null, null),
                ),
            )
            assertEquals(3, calls)
            assertEquals(2, SkinRuntimeState.latchedNotifications().size)
        } finally {
            SkinRuntimeState.removeStateChangedListener(listener)
            SkinRuntimeState.reset()
        }
    }

    @Test
    fun reset_clearsLatched() {
        SkinRuntimeState.reset(
            notifications = listOf(
                notification(NotificationSource.SCHEME, null, null, "s"),
            ),
        )
        assertTrue(
            SkinRuntimeState.onEvent(
                NotificationEvent(NotificationSource.SCHEME, "wanxiang", null),
            ),
        )
        SkinRuntimeState.reset()
        assertTrue(SkinRuntimeState.latchedNotifications().isEmpty())
        assertFalse(
            SkinRuntimeState.onEvent(
                NotificationEvent(NotificationSource.SCHEME, "wanxiang", null),
            ),
        )
    }

    @Test
    fun conditionSets_gatedAndActive() {
        SkinRuntimeState.reset(
            conditionStyles = listOf(
                ConditionStyle(
                    "rime\$ascii_mode", ConditionValue.Bool(true), listOf("accent"),
                ),
                ConditionStyle(
                    "symbolLock", ConditionValue.Bool(true), listOf("locked"),
                ),
            ),
        )
        try {
            assertEquals(setOf("accent", "locked"), SkinRuntimeState.gatedStyleNames())
            assertTrue(SkinRuntimeState.activeStyleNames().isEmpty())
            assertTrue(
                SkinRuntimeState.updateConditionCurrent(
                    mapOf(
                        "rime\$ascii_mode" to ConditionCurrent.Bool(true),
                        "symbolLock" to ConditionCurrent.Bool(false),
                    ),
                ),
            )
            assertEquals(setOf("accent"), SkinRuntimeState.activeStyleNames())
            // 同值重写：无变化
            assertFalse(
                SkinRuntimeState.updateConditionCurrent(
                    mapOf(
                        "rime\$ascii_mode" to ConditionCurrent.Bool(true),
                        "symbolLock" to ConditionCurrent.Bool(false),
                    ),
                ),
            )
        } finally {
            SkinRuntimeState.reset()
        }
    }

    @Test
    fun notificationAction_isPreserved() {
        val note = SkinNotification(
            source = NotificationSource.SCHEME, matchKey = null, matchValue = null,
            targetStyle = "t", backgroundStyle = "#FF0000", foregroundStyle = null,
            action = SkinAction.Command(WhitelistAction.CAPS),
            swipeUpAction = null, swipeDownAction = null,
        )
        SkinRuntimeState.reset(notifications = listOf(note))
        try {
            assertTrue(
                SkinRuntimeState.onEvent(
                    NotificationEvent(NotificationSource.SCHEME, "any", null),
                ),
            )
            assertEquals(note, SkinRuntimeState.latchedNotifications()["t"])
        } finally {
            SkinRuntimeState.reset()
        }
    }
}
