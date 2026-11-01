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
}
