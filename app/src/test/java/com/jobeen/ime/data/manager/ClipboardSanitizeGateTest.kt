// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 一次性超限清理的进程内门（D-L6，与 P-中2 同一函数）：
 * 并发调用方等待正在进行的清理完成、而不是与它竞速；清理只跑
 * 一次；失败不置标志、下次重试。门是纯协调逻辑，DB 本体与
 * intake 接线（先清理再读、截断投影读、读失败按未知重试）需
 * 仪器测试／真机验收（SQL 本身不可在 JVM 测，见 ClipboardDedupeTest）。
 */
class ClipboardSanitizeGateTest {

    @Test
    fun sequentialCalls_runBlockOnce() = runBlocking {
        val gate = SanitizeOnceGate()
        var runs = 0
        repeat(3) { gate.runOnce { runs++; true } }
        assertEquals(1, runs)
    }

    @Test
    fun concurrentCalls_secondWaitsForFirst() = runBlocking {
        val gate = SanitizeOnceGate()
        var runs = 0
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.runOnce {
                runs++
                release.await()
                true
            }
        }
        // 先让第一个调用方进门并挂起在清理本体上。
        delay(100)
        assertEquals(1, runs)
        val second = async { gate.runOnce { runs++; true } }
        // 第二个调用方必须等清理完成，不能立即返回、更不能重跑清理。
        delay(100)
        assertFalse(second.isCompleted)
        assertEquals(1, runs)
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, runs)
    }

    @Test
    fun failedCleanup_retriesNextCall() = runBlocking {
        val gate = SanitizeOnceGate()
        var runs = 0
        // 前两次失败、第三次成功：各跑一次。
        gate.runOnce { runs++; false }
        gate.runOnce { runs++; false }
        gate.runOnce { runs++; true }
        assertEquals(3, runs)
        // 成功后置标志，后续调用不再跑。
        gate.runOnce { runs++; true }
        assertEquals(3, runs)
    }

    @Test
    fun failedCleanup_nextCallerRetries() = runBlocking {
        val gate = SanitizeOnceGate()
        var runs = 0
        gate.runOnce { runs++; false }
        assertEquals(1, runs)
        // 失败未置标志：下一个调用方重新执行清理，而不是直接返回。
        gate.runOnce { runs++; true }
        assertEquals(2, runs)
        // 成功后置标志，后续调用不再跑。
        gate.runOnce { runs++; true }
        assertEquals(2, runs)
    }
}
