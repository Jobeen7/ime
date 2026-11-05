package com.jobeen.ime.skin.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Skin sandbox: own API surface, isolation, budget, output caps (real Rhino). */
class SkinScriptSandboxTest {

    private val sandbox = SkinScriptSandbox()

    @Test
    fun onEvent_receivesEventAndArgs_returnsValueAndLogs() {
        val logs = ArrayList<String>()
        val outcome = sandbox.run(
            "function onEvent(ctx) { skin.log('saw ' + ctx.event + ':' + ctx.args.key); return 'v:' + ctx.args.key; }",
            "press",
            mapOf("key" to "a"),
            onLog = { logs.add(it) },
        )
        val ok = outcome as SkinScriptRunOutcome.Ok
        assertEquals("v:a", ok.value)
        assertEquals(listOf("saw press:a"), ok.logLines)
        assertEquals(listOf("saw press:a"), logs)
    }

    @Test
    fun missingOnEvent_skippedNotFailed() {
        val outcome = sandbox.run("var x = 1;", "press", emptyMap())
        assertEquals(
            SkinScriptRunOutcome.Skipped(SkinScriptSkip.NO_HANDLER),
            outcome,
        )
    }

    @Test
    fun voicePluginBridges_absent() {
        val outcome = sandbox.run(
            "function onEvent(ctx) { return '' + (typeof net) + ',' + (typeof files) + ',' + (typeof cb) + ',' + (typeof crypto); }",
            "press",
            emptyMap(),
        )
        val ok = outcome as SkinScriptRunOutcome.Ok
        assertEquals("undefined,undefined,undefined,undefined", ok.value)
    }

    @Test
    fun javaAccess_blocked() {
        val outcome = sandbox.run(
            "function onEvent(ctx) { return typeof Packages; }",
            "press",
            emptyMap(),
        )
        val ok = outcome as SkinScriptRunOutcome.Ok
        assertEquals("undefined", ok.value)
        val escaping = sandbox.run(
            "function onEvent(ctx) { try { Packages.java.lang.System.exit(0); return 'escaped'; } catch (e) { return 'blocked'; } }",
            "press",
            emptyMap(),
        )
        assertEquals("blocked", (escaping as SkinScriptRunOutcome.Ok).value)
    }

    @Test
    fun runs_isolated_noSharedGlobals() {
        sandbox.run("var marker = 99; function onEvent(ctx) { return 'set'; }", "press", emptyMap())
        val outcome = sandbox.run(
            "function onEvent(ctx) { return typeof marker; }",
            "press",
            emptyMap(),
        )
        assertEquals("undefined", (outcome as SkinScriptRunOutcome.Ok).value)
    }

    @Test
    fun infiniteLoop_budgetExceeded() {
        val outcome = sandbox.run(
            "function onEvent(ctx) { while (true) { } }",
            "press",
            emptyMap(),
        )
        assertEquals(
            SkinScriptRunOutcome.Failed(SkinScriptFailure.BudgetExceeded),
            outcome,
        )
    }

    @Test
    fun scriptError_reportsKindOnly_notSource() {
        val outcome = sandbox.run(
            "function onEvent(ctx) { throw new Error('SECRET_MARKER_TEXT'); }",
            "press",
            emptyMap(),
        )
        val failed = outcome as SkinScriptRunOutcome.Failed
        val failure = failed.failure as SkinScriptFailure.Error
        assertTrue(failure.kind.isNotEmpty())
        assertTrue(!failure.toString().contains("SECRET_MARKER_TEXT"))
    }

    @Test
    fun logAndReturn_capsEnforced() {
        val outcome = sandbox.run(
            "function onEvent(ctx) { skin.log('x'.repeat(5000)); return 'y'.repeat(9000); }",
            "press",
            emptyMap(),
        )
        val ok = outcome as SkinScriptRunOutcome.Ok
        // Over-long log line is dropped, not truncated into output.
        assertTrue(ok.logLines.isEmpty())
        assertEquals(SkinScriptSandbox.MAX_RETURN_CHARS, ok.value?.length)
    }

    @Test
    fun objectReturn_yieldsNull() {
        val outcome = sandbox.run(
            "function onEvent(ctx) { return {a: 1}; }",
            "press",
            emptyMap(),
        )
        assertNull((outcome as SkinScriptRunOutcome.Ok).value)
    }

    // ---- 审计 #1-10：输入上限与全局并发硬上限 ----

    @Test
    fun tooManyArgs_rejected() {
        val args = (1..33).associate { "k$it" to "v" }
        val outcome = sandbox.run(
            "function onEvent(ctx) { return 'ran'; }",
            "press",
            args,
        )
        assertEquals(
            SkinScriptRunOutcome.Failed(SkinScriptFailure.TooLarge),
            outcome,
        )
    }

    @Test
    fun tooManyArgChars_rejected() {
        val outcome = sandbox.run(
            "function onEvent(ctx) { return 'ran'; }",
            "press",
            mapOf("k" to "x".repeat(70_000)),
        )
        assertEquals(
            SkinScriptRunOutcome.Failed(SkinScriptFailure.TooLarge),
            outcome,
        )
    }

    @Test
    fun concurrentRuns_cappedAtFour() {
        // 4 个慢脚本先占满槽（轮询确认 4 槽都被占住），第 5 个
        // 在调用线程立即失败 TooManyConcurrent；4 个跑完后计数归零。
        val slowScript = "function onEvent(ctx) { " +
            "var t = Date.now(); while (Date.now() - t < 400) {} return 'done'; }"
        val results = arrayOfNulls<SkinScriptRunOutcome>(4)
        val threads = (0..3).map { i ->
            Thread {
                results[i] = sandbox.run(slowScript, "press", emptyMap())
            }.also { it.isDaemon = true; it.start() }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (sandbox.activeRunCount() < SkinScriptSandbox.MAX_CONCURRENT_SCRIPT_RUNS &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(SkinScriptSandbox.MAX_CONCURRENT_SCRIPT_RUNS, sandbox.activeRunCount())
        val fifth = sandbox.run(slowScript, "press", emptyMap())
        assertEquals(
            SkinScriptRunOutcome.Failed(SkinScriptFailure.TooManyConcurrent),
            fifth,
        )
        threads.forEach { it.join(10_000) }
        assertTrue(results.all { it is SkinScriptRunOutcome.Ok })
        assertEquals(0, sandbox.activeRunCount())
    }

    @Test
    fun largeAllocation_completesAndMemoryReclaimable() {
        // 压力：脚本一次建 10M 字串（约 20MB 堆）并持有 300ms，
        // 采样观测峰值；跑完＋GC 后堆回到基线附近（分配可回收、
        // 无泄漏）。堆上限本身 Rhino 给不出（见沙箱注释），本
        // 测试只证「单次大分配不崩、不漏」，不谎称已限堆。
        val runtime = Runtime.getRuntime()
        runtime.gc()
        Thread.sleep(100)
        val baseline = runtime.totalMemory() - runtime.freeMemory()
        val peak = java.util.concurrent.atomic.AtomicLong(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val sampler = Thread {
            while (!stop.get()) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peak.accumulateAndGet(used) { a, b -> maxOf(a, b) }
                Thread.sleep(10)
            }
        }.also { it.isDaemon = true; it.start() }
        val outcome = try {
            sandbox.run(
                "function onEvent(ctx) { " +
                    "var s = 'x'.repeat(10000000); " +
                    "var t = Date.now(); while (Date.now() - t < 300) {} " +
                    "return 'len:' + s.length; }",
                "press",
                emptyMap(),
            )
        } finally {
            stop.set(true)
            sampler.join(5_000)
        }
        val ok = outcome as? SkinScriptRunOutcome.Ok
        assertEquals("len:10000000", ok?.value)
        assertTrue(
            "peak should exceed baseline by the 20MB string, peak=${peak.get()} baseline=$baseline",
            peak.get() > baseline + 5 * 1024 * 1024,
        )
        runtime.gc()
        Thread.sleep(100)
        runtime.gc()
        val after = runtime.totalMemory() - runtime.freeMemory()
        assertTrue(
            "heap should return near baseline after GC, after=$after baseline=$baseline",
            after < baseline + 20 * 1024 * 1024,
        )
        assertEquals(0, sandbox.activeRunCount())
    }
}
