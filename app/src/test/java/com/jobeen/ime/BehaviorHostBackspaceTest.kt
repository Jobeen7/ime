// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.rime.behavior.Backspace
import com.jobeen.ime.engine.rime.behavior.InputString
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.core.RimeApi
import com.jobeen.ime.engine.rime.host.BehaviorHost
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BehaviorHost.recontrolBackspace（E-M6）：native 的 BackSpace 只删
 * 一个字，队列侧必须同样只删末条末尾一个码点，而不是整条丢弃。
 * 旧行为：多字序列键（如皮肤自定义序列）一次退格丢整条，下次重建
 * 输入会把 native 组字里多余的字静默截掉。
 *
 * FakeJob 只登记队列、不投递引擎（与 BehaviorHostSelectPinYinTest
 * 同一 harness）。
 */
class BehaviorHostBackspaceTest {

    private class FakeJob : IRimeJob {
        override fun sendJob(block: suspend RimeApi.() -> Unit) { /* 只登记队列，不投递引擎 */ }
        override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T =
            defaultValue
    }

    @Test
    fun multiCharEntryTrimsOneCodePoint() {
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("abc"))
        host.flowed(Backspace())
        assertEquals("ab", host.build())
        host.flowed(Backspace())
        assertEquals("a", host.build())
    }

    @Test
    fun singleCharEntryIsRemovedEntirely() {
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("a"))
        host.flowed(Backspace())
        assertEquals("", host.build())
    }

    @Test
    fun multiEntryTrimsOnlyLastEntry() {
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("ab"))
        host.flowed(InputString("cd"))
        host.flowed(Backspace())
        assertEquals("abc", host.build())
        host.flowed(Backspace())
        assertEquals("ab", host.build())
        host.flowed(Backspace())
        assertEquals("a", host.build())
        host.flowed(Backspace())
        assertEquals("", host.build())
    }

    @Test
    fun surrogatePairTrimsWholeCodePoint() {
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("\uD83D\uDE00")) // U+1F600 😀，两个 UTF-16 单元
        host.flowed(Backspace())
        assertEquals("", host.build())
    }

    @Test
    fun surrogatePairAfterAsciiTrimsOnlyEmoji() {
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("a\uD83D\uDE00"))
        host.flowed(Backspace())
        assertEquals("a", host.build())
    }

    @Test
    fun backspaceOnEmptyQueueIsNoop() {
        val host = BehaviorHost(FakeJob())
        host.flowed(Backspace())
        assertEquals("", host.build())
    }
}
