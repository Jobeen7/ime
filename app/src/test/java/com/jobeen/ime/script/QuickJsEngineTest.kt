// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuickJsEngine 桥单测（WO52 8.1）：native 库走主机 .so（Gradle
 * buildHostQuickJs 任务编，见 app/build.gradle.kts）。
 */
class QuickJsEngineTest {

    private val engines = mutableListOf<QuickJsEngine>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.destroy() } }
        engines.clear()
    }

    private fun newEngine(): QuickJsEngine {
        return QuickJsEngine.create().also { engines.add(it) }
    }

    @Test
    fun eval_asyncOutput_returnsStringList() {
        val engine = newEngine()
        assertNull(engine.eval("async function output() { return [\"a\", \"b\"]; }"))
        assertTrue(engine.isFunction("output"))
        assertNull(engine.callOutput())
        assertNull(engine.runJobs())
        assertEquals(OutputState.FULFILLED, engine.outputState())
        assertEquals(listOf("a", "b"), engine.outputValue())
    }

    @Test
    fun eval_plainFunctionOutput_isImmediatelyFulfilled() {
        val engine = newEngine()
        assertNull(engine.eval("function output() { return [\"x\"]; }"))
        assertNull(engine.callOutput())
        assertEquals(OutputState.FULFILLED, engine.outputState())
        assertEquals(listOf("x"), engine.outputValue())
    }

    @Test
    fun eval_nonStringElements_stringified() {
        val engine = newEngine()
        assertNull(engine.eval("async function output() { return [1, true]; }"))
        assertNull(engine.callOutput())
        assertNull(engine.runJobs())
        assertEquals(listOf("1", "true"), engine.outputValue())
    }

    @Test
    fun eval_syntaxError_returnsError() {
        val engine = newEngine()
        val failure = engine.eval("async function output( {")
        assertNotNull(failure)
        assertTrue(failure is ScriptFailure.Error)
    }

    @Test
    fun callOutput_withoutOutputFunction_returnsNoOutputFunction() {
        val engine = newEngine()
        assertNull(engine.eval("var a = 1;"))
        assertFalse(engine.isFunction("output"))
        assertEquals(ScriptFailure.NoOutputFunction, engine.callOutput())
    }

    @Test
    fun callOutput_rejectedPromise_reportsReason() {
        val engine = newEngine()
        assertNull(engine.eval("async function output() { throw new Error(\"boom\"); }"))
        assertNull(engine.callOutput())
        assertNull(engine.runJobs())
        assertEquals(OutputState.REJECTED, engine.outputState())
        assertTrue(engine.outputError().orEmpty().contains("boom"))
        assertNull(engine.outputValue())
    }

    @Test
    fun setGlobalString_injectsVariables() {
        val engine = newEngine()
        engine.setGlobalString("\$searchText", "中文")
        assertNull(engine.eval("async function output() { return [\"Hello, \" + \$searchText]; }"))
        assertNull(engine.callOutput())
        assertNull(engine.runJobs())
        assertEquals(listOf("Hello, 中文"), engine.outputValue())
    }

    @Test
    fun hostLog_recordsLines() {
        val engine = newEngine()
        val logs = mutableListOf<String>()
        engine.host = object : ScriptHost {
            override fun log(text: String) {
                logs.add(text)
            }

            override fun http(json: String): Long = 0L
            override fun setClipboard(text: String) = Unit
            override fun insertText(text: String) = Unit
            override fun openUrl(url: String): Boolean = false
            override fun userNotification(title: String, shortcutName: String, content: String): Boolean = false
            override fun onHttpCompletion(completion: HttpCompletion) = Unit
        }
        assertNull(engine.eval("async function output() { \$log(\"hi\"); \$log(\"a\", \"b\"); return [\"ok\"]; }"))
        assertNull(engine.callOutput())
        assertNull(engine.runJobs())
        assertEquals(listOf("hi", "a b"), logs)
        assertEquals(listOf("ok"), engine.outputValue())
    }

    @Test
    fun deadline_infiniteLoop_returnsTimeout() {
        val engine = newEngine()
        engine.setDeadline(500_000_000L)
        val failure = engine.eval("while (true) {}")
        assertEquals(ScriptFailure.Timeout, failure)
    }

    @Test
    fun memoryLimit_hugeArrayBuffer_returnsErrorNotCrash() {
        val engine = newEngine()
        val failure = engine.eval("new ArrayBuffer(1 << 30)")
        assertNotNull("1GiB allocation must fail under the 64MiB heap limit", failure)
        assertTrue(failure is ScriptFailure.Error)
        // 引擎在超限后仍可用（进程没崩、输入法活着）。
        assertNull(engine.eval("1 + 1"))
    }

    @Test
    fun cancel_infiniteLoopFromAnotherThread_returnsCancelled() {
        // 刻意跨线程：主线程创建、子线程 eval、主线程 cancel。曾因此测出
        // QuickJS 栈顶锚定问题（见 quickjs_jni.c enterJs 注释）：不重锚
        // 时换线程 eval 立即判栈溢出。
        val engine = newEngine()
        engine.setDeadline(0L)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        var failure: ScriptFailure? = null
        val thread = Thread {
            started.countDown()
            failure = engine.eval("while (true) {}")
            done.countDown()
        }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        engine.cancel()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(ScriptFailure.Cancelled, failure)
    }

    @Test
    fun settle_deliversHttpCompletionToScript() {
        val engine = newEngine()
        assertNull(
            engine.eval(
                """
                var seen = "";
                function __jimeHttpResult(id, rejected, payload) { seen = id + ":" + rejected + ":" + payload; }
                async function output() {
                  for (var i = 0; i < 100000 && seen === ""; i++) { await Promise.resolve(); }
                  return [seen];
                }
                """.trimIndent(),
            ),
        )
        assertNull(engine.callOutput())
        engine.settle(7L, true, "ok-body")
        assertNull(engine.runJobs())
        assertEquals(OutputState.FULFILLED, engine.outputState())
        assertEquals(listOf("7:false:ok-body"), engine.outputValue())
    }

    @Test
    fun liveCount_tracksEngines() {
        val before = QuickJsEngine.liveCount()
        val engine = newEngine()
        assertEquals(before + 1, QuickJsEngine.liveCount())
        engine.destroy()
        engines.remove(engine)
        assertEquals(before, QuickJsEngine.liveCount())
    }

    @Test
    fun destroy_isIdempotentAndInvalidates() {
        val engine = newEngine()
        engine.destroy()
        engine.destroy()
        assertFalse(engine.isValid)
    }
}
