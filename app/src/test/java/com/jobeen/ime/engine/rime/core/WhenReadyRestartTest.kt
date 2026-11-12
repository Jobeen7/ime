// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * RimeLifecycle.whenReady 重启容忍（E-M5）：STOPPED 后紧跟一次重启时，
 * 该 STOPPED 不是终态——STOPPING 期 createSession 登记的 startupPending
 * 由 finalize 末尾消费、同步重走 startup，停机窗口内建的会话等待方
 * 不应注定失败一次。无重启的真终态仍抛 RimeStoppedException。
 *
 * 时序是确定性的：同一线程内连续 emitState，等待协程在主线程挂起前
 * 不可能插队；delay(100) 保证等待方先完成注册再发信号。
 */
class WhenReadyRestartTest {

    @Test
    fun waiterSurvivesStoppedThenRestart() = runBlocking {
        val registry = RimeLifecycleRegistry()
        registry.emitState(RimeLifecycle.State.STARTING)
        val deferred = async { registry.whenReady { "ok" } }
        delay(100)
        registry.emitState(RimeLifecycle.State.STOPPED)
        registry.emitState(RimeLifecycle.State.STARTING)
        registry.emitState(RimeLifecycle.State.READY)
        assertEquals("ok", withTimeout(5_000) { deferred.await() })
    }

    @Test
    fun waiterFailsWhenStoppedWithoutRestart() = runBlocking {
        // 必须包 supervisorScope：async 子协程抛错会同时传给父 Job，
        // 直接挂在 runBlocking 下时 joinBlocking 会先于 await() 把
        // RimeStoppedException 抛出来，try/catch 永远接不到；supervisor
        // 隔离后异常只留在 Deferred 里，由 await() 重新抛出、此处接住。
        supervisorScope {
            val registry = RimeLifecycleRegistry()
            registry.emitState(RimeLifecycle.State.STARTING)
            val deferred = async { registry.whenReady { "ok" } }
            delay(100)
            registry.emitState(RimeLifecycle.State.STOPPED)
            try {
                withTimeout(5_000) { deferred.await() }
                fail("expected RimeStoppedException")
            } catch (e: RimeStoppedException) {
                // expected: true terminal STOPPED
            }
        }
    }

    @Test
    fun stoppedImmediatelyFails() = runBlocking {
        val registry = RimeLifecycleRegistry()
        try {
            registry.whenReady { "ok" }
            fail("expected RimeStoppedException")
        } catch (e: RimeStoppedException) {
            // expected: already STOPPED at entry
        }
    }

    @Test
    fun readyImmediatelyRuns() = runBlocking {
        val registry = RimeLifecycleRegistry()
        registry.emitState(RimeLifecycle.State.READY)
        assertEquals("ok", registry.whenReady { "ok" })
    }
}
