package com.jobeen.ime.skin.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runner entry point: gating (missing/disabled/handler-less) before execution. */
class SkinScriptRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runner(): Pair<SkinScriptStore, SkinScriptRunner> {
        val store = SkinScriptStore(tmp.root)
        return store to SkinScriptRunner(store)
    }

    @Test
    fun runScript_runsEnabledScript() {
        val (store, runner) = runner()
        store.add("r.one", "R", "function onEvent(ctx) { return 'ok:' + ctx.event; }")
        val result = runner.runScript("r.one", "press", mapOf("k" to "v"))
        assertEquals(SkinScriptResult.Ok("ok:press", emptyList()), result)
    }

    @Test
    fun runScript_missingScript_skipped() {
        val (_, runner) = runner()
        assertEquals(
            SkinScriptResult.Skipped(SkinScriptSkipReason.NOT_FOUND),
            runner.runScript("nope", "press"),
        )
    }

    @Test
    fun runScript_disabledScript_neverExecutes() {
        val (store, runner) = runner()
        store.add("d.one", "D", "function onEvent(ctx) { skin.log('RAN'); return 'ran'; }")
        assertTrue(store.setEnabled("d.one", false))
        val result = runner.runScript("d.one", "press")
        assertEquals(SkinScriptResult.Skipped(SkinScriptSkipReason.DISABLED), result)
    }

    @Test
    fun runScript_noHandler_skipped() {
        val (store, runner) = runner()
        store.add("h.one", "H", "var x = 1;")
        assertEquals(
            SkinScriptResult.Skipped(SkinScriptSkipReason.NO_HANDLER),
            runner.runScript("h.one", "press"),
        )
    }

    @Test
    fun runScript_budgetExceeded_failed() {
        val (store, runner) = runner()
        store.add("l.one", "L", "function onEvent(ctx) { while (true) { } }")
        assertEquals(
            SkinScriptResult.Failed(SkinScriptFailure.BudgetExceeded),
            runner.runScript("l.one", "press"),
        )
    }

    @Test
    fun runScript_reEnabledScript_runsAgain() {
        val (store, runner) = runner()
        store.add("e.one", "E", "function onEvent(ctx) { return 'yes'; }")
        store.setEnabled("e.one", false)
        assertTrue(runner.runScript("e.one", "press") is SkinScriptResult.Skipped)
        store.setEnabled("e.one", true)
        assertEquals(
            SkinScriptResult.Ok("yes", emptyList()),
            runner.runScript("e.one", "press"),
        )
    }
}
