// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入丢字日志（施工单 10 H2-16，CyIME J4 对口）：限流＋只记原因与门状态。 */
class InputDropLogTest {

    private class Fixture {
        var now = 0L
        val lines = mutableListOf<String>()
        var stateCalls = 0
        val log = InputDropLog({ now }, lines::add)

        fun state(): String {
            stateCalls++
            return "deploying=true initialized=false"
        }
    }

    @Test
    fun firstDrop_writesOneLineWithReasonAndState() {
        val f = Fixture()
        f.log.drop("key_deploy", f::state)
        assertEquals(
            listOf("input_dropped reason=key_deploy deploying=true initialized=false"),
            f.lines,
        )
        assertEquals(1, f.stateCalls)
    }

    @Test
    fun dropsWithinWindow_suppressedAndStateNotEvaluated() {
        val f = Fixture()
        f.log.drop("key_deploy", f::state)
        f.now = 500L
        f.log.drop("key_deploy", f::state)
        f.now = 1_999L
        f.log.drop("enter_deploy", f::state)
        assertEquals(1, f.lines.size)
        assertEquals(1, f.stateCalls)
    }

    @Test
    fun dropAfterWindow_carriesSuppressedCount() {
        val f = Fixture()
        f.log.drop("key_deploy", f::state)
        f.now = 100L
        f.log.drop("key_deploy", f::state)
        f.now = 200L
        f.log.drop("key_deploy", f::state)
        f.now = 2_000L
        f.log.drop("key_deploy", f::state)
        assertEquals(2, f.lines.size)
        assertEquals(
            "input_dropped reason=key_deploy more=2 deploying=true initialized=false",
            f.lines[1],
        )
    }

    @Test
    fun windowBoundary_exactlyWindowMs_logsAgain() {
        val f = Fixture()
        f.log.drop("key_deploy", f::state)
        f.now = 2_000L
        f.log.drop("key_deploy", f::state)
        assertEquals(2, f.lines.size)
        assertTrue(f.lines[1].startsWith("input_dropped reason=key_deploy "))
    }

    @Test
    fun differentReasonWithinWindow_stillSuppressedGlobally() {
        val f = Fixture()
        f.log.drop("key_deploy", f::state)
        f.now = 100L
        f.log.drop("key_not_initialized", f::state)
        assertEquals(1, f.lines.size)
        f.now = 2_000L
        f.log.drop("key_not_initialized", f::state)
        assertEquals(2, f.lines.size)
        assertTrue(f.lines[1].contains("reason=key_not_initialized more=1"))
    }

    @Test
    fun noSuppressedDrops_noMoreSuffix() {
        val f = Fixture()
        f.log.drop("key_deploy", f::state)
        f.now = 5_000L
        f.log.drop("key_deploy", f::state)
        assertTrue(f.lines[1].startsWith("input_dropped reason=key_deploy "))
    }
}
