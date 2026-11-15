// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.webserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-W-6：Wi-Fi 服务空闲／绝对自停决策（时间注入纯函数）。 */
class WebServerServiceTest {

    @Test
    fun idleThirtyMinutesStops() {
        val start = 1_000_000L
        assertFalse(WebServerService.shouldAutoStop(start + 29 * 60 * 1000L, start, start))
        assertTrue(WebServerService.shouldAutoStop(start + 30 * 60 * 1000L, start, start))
    }

    @Test
    fun recentRequestResetsIdleClock() {
        val start = 1_000_000L
        val now = start + 40 * 60 * 1000L
        assertFalse(WebServerService.shouldAutoStop(now, start, now - 60_000L))
    }

    @Test
    fun absoluteFiveHoursStopsEvenWithTraffic() {
        val start = 1_000_000L
        val now = start + 5 * 60 * 60 * 1000L
        assertFalse(WebServerService.shouldAutoStop(now - 1L, start, now - 1L))
        assertTrue(WebServerService.shouldAutoStop(now, start, now - 1L))
    }
}
