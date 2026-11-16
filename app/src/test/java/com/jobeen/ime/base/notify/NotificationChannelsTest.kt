// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.notify

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 13.1：渠道建 channel（id／重要性）、权限拒绝降级、完成通知只发一次。 */
class NotificationChannelsTest {

    @Test
    fun `channelSpecs has exactly the one app-owned channel`() {
        val specs = NotificationChannels.channelSpecs()
        assertEquals(1, specs.size)
        assertEquals(NotificationChannels.REMOTE_TRANSFER, specs[0].id)
    }

    @Test
    fun `transfer channel is IMPORTANCE_LOW`() {
        val specs = NotificationChannels.channelSpecs().associateBy { it.id }
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            specs.getValue(NotificationChannels.REMOTE_TRANSFER).importance,
        )
    }

    @Test
    fun `remote transfer foreground notification id is 1002`() {
        assertEquals(1002, NotificationChannels.REMOTE_TRANSFER_NOTIFICATION_ID)
    }

    @Test
    fun `permission required only on Android 13 and above`() {
        assertFalse(NotificationChannels.isPermissionRequired(32))
        assertTrue(NotificationChannels.isPermissionRequired(33))
        assertTrue(NotificationChannels.isPermissionRequired(34))
    }

    @Test
    fun `canPostNotifications is true below 13 regardless of grant`() {
        assertTrue(NotificationChannels.canPostNotifications(32, false))
        assertTrue(NotificationChannels.canPostNotifications(32, true))
    }

    @Test
    fun `canPostNotifications on 13+ follows the grant (denied means silent)`() {
        assertFalse(NotificationChannels.canPostNotifications(33, false))
        assertTrue(NotificationChannels.canPostNotifications(33, true))
    }

    @Test
    fun `same runKey posts only once`() {
        var posted = 0
        val notifier = CompletionNotifier()
        assertTrue(notifier.notifyOnce("sync#1:success") { posted++ })
        assertFalse(notifier.notifyOnce("sync#1:success") { posted++ })
        assertEquals(1, posted)
    }

    @Test
    fun `different runKeys each post once`() {
        var posted = 0
        val notifier = CompletionNotifier()
        assertTrue(notifier.notifyOnce("deploy#1:success") { posted++ })
        assertTrue(notifier.notifyOnce("deploy#2:success") { posted++ })
        assertTrue(notifier.notifyOnce("sync#1:failed") { posted++ })
        assertEquals(3, posted)
    }

    @Test
    fun `same operation different outcome is a different event`() {
        var posted = 0
        val notifier = CompletionNotifier()
        assertTrue(notifier.notifyOnce("sync#1:success") { posted++ })
        assertTrue(notifier.notifyOnce("sync#1:failed") { posted++ })
        assertEquals(2, posted)
    }
}
