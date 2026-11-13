package com.jobeen.ime.skin.script

import com.jobeen.ime.base.speech.cloud.RhinoSession
import com.jobeen.ime.base.speech.cloud.ScriptCancelled
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WO38 E1-3 熔断＋E1-10 JS 会话（预算／取消／备份）：
 *
 * 熔断（按脚本 id 计，连续故障 2 次即禁行）：
 * - 0 次放行、1 次仍放行、2 次后第 3 次直接拒（CircuitOpen，不执行）；
 * - 成功清零连续计数；预算超限计入；超限输入／取消不计入；
 * - 重启（新 Runner）／源码重载／手动复位均恢复；分脚本隔离；
 *   NOT_FOUND／DISABLED 优先于熔断。
 *
 * JS 会话：
 * - 预算：超限报 BudgetExceeded（已有机，见 E1-10 核对）；
 * - 取消：会话级 cancel 中断在途求值、Runner 级 cancelRun 返回
 *   Cancelled 且远快于 500ms 预算；
 * - 备份：无可行语义（见文件末尾注释——会话每次新建、无跨运行
 *   状态可保存；源码本身已由 Store 原子落盘），故不测、不造。
 */
class Wo38ScriptCircuitBreakerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runner(): Pair<SkinScriptStore, SkinScriptRunner> {
        val store = SkinScriptStore(tmp.root)
        return store to SkinScriptRunner(store)
    }

    private fun addFailing(store: SkinScriptStore, id: String) {
        store.add(id, "F", "function onEvent(ctx) { throw new Error('x'); }")
    }

    private fun assertFailedError(result: SkinScriptResult) {
        assertTrue("expected Failed(Error), got $result", result is SkinScriptResult.Failed)
        assertTrue(
            "expected Error kind, got ${(result as SkinScriptResult.Failed).failure}",
            result.failure is SkinScriptFailure.Error,
        )
    }

    private fun assertCircuitOpen(result: SkinScriptResult) {
        assertEquals(
            SkinScriptResult.Failed(SkinScriptFailure.CircuitOpen),
            result,
        )
    }

    // ---- 熔断：0 / 1 / 2 次 ----

    @Test
    fun breaker_noFailure_runsNormally() {
        val (store, runner) = runner()
        store.add("ok.one", "O", "function onEvent(ctx) { return 'yes'; }")
        assertEquals(
            SkinScriptResult.Ok("yes", emptyList()),
            runner.runScript("ok.one", "press"),
        )
    }

    @Test
    fun breaker_oneFailure_secondRunStillExecutes() {
        val (store, runner) = runner()
        addFailing(store, "f.one")
        assertFailedError(runner.runScript("f.one", "press"))
        // 第 2 次仍执行（仍是 Error，不是 CircuitOpen）。
        assertFailedError(runner.runScript("f.one", "press"))
    }

    @Test
    fun breaker_twoFailures_thirdRunRefusedWithoutExecuting() {
        val (store, runner) = runner()
        addFailing(store, "f.two")
        assertFailedError(runner.runScript("f.two", "press"))
        assertFailedError(runner.runScript("f.two", "press"))
        assertCircuitOpen(runner.runScript("f.two", "press"))
    }

    @Test
    fun breaker_successResetsConsecutiveCount() {
        val (store, runner) = runner()
        store.add(
            "c.one",
            "C",
            "function onEvent(ctx) { " +
                "if (ctx.args.fail == '1') { throw new Error('x'); } return 'ok'; }",
        )
        assertFailedError(runner.runScript("c.one", "press", mapOf("fail" to "1")))
        assertEquals(
            SkinScriptResult.Ok("ok", emptyList()),
            runner.runScript("c.one", "press", mapOf("fail" to "0")),
        )
        // 成功已清零：再连 fail 两次才禁行（第 4 次仍是 Error）。
        assertFailedError(runner.runScript("c.one", "press", mapOf("fail" to "1")))
        assertFailedError(runner.runScript("c.one", "press", mapOf("fail" to "1")))
        assertCircuitOpen(runner.runScript("c.one", "press", mapOf("fail" to "1")))
    }

    // ---- 熔断：计入／不计入 ----

    @Test
    fun breaker_budgetFailureCounts() {
        val (store, runner) = runner()
        store.add("l.one", "L", "function onEvent(ctx) { while (true) { } }")
        assertEquals(
            SkinScriptResult.Failed(SkinScriptFailure.BudgetExceeded),
            runner.runScript("l.one", "press"),
        )
        assertEquals(
            SkinScriptResult.Failed(SkinScriptFailure.BudgetExceeded),
            runner.runScript("l.one", "press"),
        )
        assertCircuitOpen(runner.runScript("l.one", "press"))
    }

    @Test
    fun breaker_tooLargeDoesNotCount() {
        val (store, runner) = runner()
        store.add("t.one", "T", "function onEvent(ctx) { return 'ok'; }")
        val bigArgs = mapOf("k" to "x".repeat(SkinScriptSandbox.MAX_SCRIPT_ARGS_CHARS))
        repeat(3) {
            assertEquals(
                SkinScriptResult.Failed(SkinScriptFailure.TooLarge),
                runner.runScript("t.one", "press", bigArgs),
            )
        }
    }

    @Test
    fun breaker_cancelledDoesNotCount() {
        val (store, runner) = runner()
        store.add("x.one", "X", "function onEvent(ctx) { while (true) { } }")
        repeat(3) {
            assertEquals(
                SkinScriptResult.Failed(SkinScriptFailure.Cancelled),
                runAndCancel(runner, "x.one"),
            )
        }
    }

    /** 在工作线程起一场死循环运行，主线程轮询 cancelRun 直至命中，返回其结果。 */
    private fun runAndCancel(runner: SkinScriptRunner, scriptId: String): SkinScriptResult {
        val resultRef = AtomicReference<SkinScriptResult?>()
        val t = Thread { resultRef.set(runner.runScript(scriptId, "press")) }
        t.start()
        var cancelled = false
        var attempts = 0
        while (!cancelled && attempts < 100) {
            cancelled = runner.cancelRun(scriptId)
            if (!cancelled) Thread.sleep(20)
            attempts++
        }
        assertTrue("cancelRun never hit the in-flight run", cancelled)
        t.join(5000)
        return resultRef.get() ?: error("run produced no result")
    }

    // ---- 熔断：恢复 ----

    @Test
    fun breaker_restartRecovers() {
        val (store, runner) = runner()
        addFailing(store, "r.one")
        assertFailedError(runner.runScript("r.one", "press"))
        assertFailedError(runner.runScript("r.one", "press"))
        assertCircuitOpen(runner.runScript("r.one", "press"))
        // 新 Runner＝应用重启：熔断状态随旧实例丢弃。
        val freshRunner = SkinScriptRunner(store)
        assertFailedError(freshRunner.runScript("r.one", "press"))
    }

    @Test
    fun breaker_sourceReloadRecovers() {
        val (store, runner) = runner()
        addFailing(store, "s.one")
        assertFailedError(runner.runScript("s.one", "press"))
        assertFailedError(runner.runScript("s.one", "press"))
        assertCircuitOpen(runner.runScript("s.one", "press"))
        // 删后重加新源码＝脚本重载：熔断复位，正常执行。
        assertTrue(store.remove("s.one"))
        store.add("s.one", "S", "function onEvent(ctx) { return 'fixed'; }")
        assertEquals(
            SkinScriptResult.Ok("fixed", emptyList()),
            runner.runScript("s.one", "press"),
        )
    }

    @Test
    fun breaker_sameSourceReinstallDoesNotRecover() {
        val (store, runner) = runner()
        addFailing(store, "s.two")
        assertFailedError(runner.runScript("s.two", "press"))
        assertFailedError(runner.runScript("s.two", "press"))
        assertCircuitOpen(runner.runScript("s.two", "press"))
        // 同源码重装不是重载：同一脚本同一故障，不应复活。
        assertTrue(store.remove("s.two"))
        addFailing(store, "s.two")
        assertCircuitOpen(runner.runScript("s.two", "press"))
    }

    @Test
    fun breaker_resetCircuitRecovers() {
        val (store, runner) = runner()
        addFailing(store, "m.one")
        assertFailedError(runner.runScript("m.one", "press"))
        assertFailedError(runner.runScript("m.one", "press"))
        assertCircuitOpen(runner.runScript("m.one", "press"))
        runner.resetCircuit("m.one")
        assertFailedError(runner.runScript("m.one", "press"))
    }

    // ---- 熔断：隔离与优先级 ----

    @Test
    fun breaker_isPerScript() {
        val (store, runner) = runner()
        addFailing(store, "a.one")
        store.add("b.one", "B", "function onEvent(ctx) { return 'b'; }")
        assertFailedError(runner.runScript("a.one", "press"))
        assertFailedError(runner.runScript("a.one", "press"))
        assertCircuitOpen(runner.runScript("a.one", "press"))
        assertEquals(
            SkinScriptResult.Ok("b", emptyList()),
            runner.runScript("b.one", "press"),
        )
    }

    @Test
    fun breaker_disabledAndMissingTakePrecedence() {
        val (store, runner) = runner()
        addFailing(store, "p.one")
        assertFailedError(runner.runScript("p.one", "press"))
        assertFailedError(runner.runScript("p.one", "press"))
        assertCircuitOpen(runner.runScript("p.one", "press"))
        assertTrue(store.setEnabled("p.one", false))
        assertEquals(
            SkinScriptResult.Skipped(SkinScriptSkipReason.DISABLED),
            runner.runScript("p.one", "press"),
        )
        // 禁用开关不是重载：重启用后仍禁行。
        assertTrue(store.setEnabled("p.one", true))
        assertCircuitOpen(runner.runScript("p.one", "press"))
        assertTrue(store.remove("p.one"))
        assertEquals(
            SkinScriptResult.Skipped(SkinScriptSkipReason.NOT_FOUND),
            runner.runScript("p.one", "press"),
        )
    }

    // ---- JS 会话：预算 ----

    @Test
    fun budget_runnerReportsBudgetExceeded() {
        val (store, runner) = runner()
        store.add("b.one", "B", "function onEvent(ctx) { while (true) { } }")
        assertEquals(
            SkinScriptResult.Failed(SkinScriptFailure.BudgetExceeded),
            runner.runScript("b.one", "press"),
        )
    }

    // ---- JS 会话：取消 ----

    @Test
    fun cancel_sessionLevel_interruptsInfiniteLoop() {
        val sessionRef = AtomicReference<RhinoSession?>()
        val caughtRef = AtomicReference<Throwable?>()
        val startNanos = System.nanoTime()
        val t = Thread {
            RhinoSession.create().use { session ->
                sessionRef.set(session)
                try {
                    session.eval("while (true) { }", "test", 10_000L)
                } catch (e: Throwable) {
                    caughtRef.set(e)
                }
            }
        }
        t.start()
        var waited = 0
        while (sessionRef.get() == null && waited < 100) {
            Thread.sleep(20)
            waited++
        }
        val session = sessionRef.get() ?: error("session not created")
        session.cancel()
        t.join(5000)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
        assertTrue(
            "expected ScriptCancelled, got ${caughtRef.get()}",
            caughtRef.get() is ScriptCancelled,
        )
        // 10 秒预算未走完即中断：取消真实生效，不只等预算。
        assertTrue("cancel took too long: ${elapsedMs}ms", elapsedMs < 2000)
    }

    @Test
    fun cancel_runnerLevel_returnsCancelledWellBeforeBudget() {
        val (store, runner) = runner()
        store.add("k.one", "K", "function onEvent(ctx) { while (true) { } }")
        val startNanos = System.nanoTime()
        val result = runAndCancel(runner, "k.one")
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
        assertEquals(SkinScriptResult.Failed(SkinScriptFailure.Cancelled), result)
        // 预算 500ms：取消若不生效，本轮至少跑满 500ms。
        assertTrue("cancel took too long: ${elapsedMs}ms", elapsedMs < 450)
    }

    @Test
    fun cancel_cancelRunWithoutInflightRun_returnsFalse() {
        val (store, runner) = runner()
        store.add("n.one", "N", "function onEvent(ctx) { return 'ok'; }")
        assertFalse(runner.cancelRun("no-such-id"))
        assertEquals(
            SkinScriptResult.Ok("ok", emptyList()),
            runner.runScript("n.one", "press"),
        )
        assertFalse(runner.cancelRun("n.one"))
    }
}

/**
 * E1-10 “备份”核实结论（无可行语义，不实现、不硬造）：
 * - 会话无状态可备：SkinScriptSandbox 每次运行新建 RhinoSession，
 *   用完即 close；脚本的全局量、原型补丁随运行消亡（沙箱文档
 *   “No cross-script state”），不存在可快照／恢复的会话状态。
 * - 源码已有持久备份：SkinScriptStore 把 script.js＋meta.json 以
 *   AtomicFiles 原子写入磁盘；add 拒绝覆盖（AlreadyExists），
 *   全库无任何破坏性写操作需要备份兜底。
 * - 语音侧同理：PluginStore 只对会话做一次性元数据读取，会话
 *   本身是临时对象。
 */
