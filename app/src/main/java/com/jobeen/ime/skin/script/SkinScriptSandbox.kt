package com.jobeen.ime.skin.script

import com.jobeen.ime.base.speech.cloud.BridgeFunction
import com.jobeen.ime.base.speech.cloud.PluginContract
import com.jobeen.ime.base.speech.cloud.RhinoSession
import com.jobeen.ime.base.speech.cloud.ScriptBudgetExceeded
import com.jobeen.ime.base.speech.cloud.sanitizeScriptError
import org.mozilla.javascript.NativeObject

/**
 * Skin-script sandbox.
 *
 * REUSES the Rhino JS engine already embedded for cloud voice plugins
 * (see base/speech/cloud/RhinoSandbox.kt) — no new JS engine is added.
 * It is a SEPARATE sandbox with its own API surface: it installs only the
 * `skin` bridge below and never installs any voice-plugin bridge (no net,
 * no files, no crypto, no cb). A skin script therefore cannot reach the
 * voice-plugin capabilities even though both share the engine class.
 *
 * SECURITY POLICY (default deny unless explicitly needed):
 *
 * ALLOWED:
 * - Pure JS computation on the arguments the host passes in (event name
 *   plus string args) and on the script's own literals.
 * - `skin.log(text)`: appends one host-visible log line, capped in length
 *   and count; anything over the cap is dropped, never truncated into a
 *   second line. Log lines are the script's own output, returned to the
 *   caller in the run result — the sandbox itself writes nothing to disk.
 * - Returning one value from `onEvent(ctx)`: strings pass through capped;
 *   numbers/booleans stringify; objects/undefined yield null. The return
 *   value is data for the caller, not an instruction: this wave does NOT
 *   interpret it as an action (no action wiring yet).
 *
 * BLOCKED (each verified by SkinScriptSandboxTest):
 * - No network: no `net` bridge, no fetch/XHR-style host object.
 * - No filesystem: no `files` bridge, no per-script directory, no path
 *   of any kind reaches the script.
 * - No URI/intent launching: no host object can open anything.
 * - No voice-plugin APIs: no `cb`, `crypto`, `net`, `files` globals.
 * - No Java access: the shared RhinoSession denies every class via
 *   ClassShutter and deletes Packages/java/org/com/javax/edu/net,
 *   JavaAdapter, JavaImporter, importClass, importPackage from scope.
 * - No cross-script state: every run gets a FRESH RhinoSession, so one
 *   script's globals, prototypes and monkey-patches die with its run.
 * - No unbounded run: eval and the onEvent call SHARE ONE wall-clock
 *   deadline (see RUN_BUDGET_MS): the remaining budget is recomputed
 *   before each stage, so a script cannot take 500ms in eval plus
 *   another 500ms in onEvent. Overrun kills the run, never retries.
 *   (The host register setup eval inside RhinoSession.create is host
 *   code with its own constant budget, not script time, and is not
 *   charged to the script deadline.)
 * - No unbounded input/output: source size is re-checked here even
 *   though the store already caps it; log lines and return values are
 *   capped; script source and error messages are never logged (only the
 *   sanitized exception kind is reported).
 *
 * RESIDUAL RISK (honest list, not waved away):
 * - The budget is sampled every 4096 interpreted instructions: a script
 *   cannot loop forever, but a single long stretch of straight-line code
 *   can run up to the sampling granularity past the deadline, and the
 *   500ms budget itself runs on the caller's thread — callers MUST run
 *   scripts off the main thread (see [SkinScriptRunner.runScript]).
 * - No heap cap (unchanged by the batch-4 budget work, deliberately
 *   not faked): Rhino exposes no reliable per-session heap limit, so
 *   a script can still allocate large arrays within its budget. What
 *   bounds it in practice is the source-size cap, the wall-clock
 *   budget, and the log/return caps — not a byte budget. A future
 *   wave that runs untrusted third-party scripts should add a
 *   heap/observation guard before widening distribution; this wave
 *   does not claim one exists.
 * - JS `eval`/`Function` inside the script still work, but stay inside
 *   the same denied-class scope: they can compute, they cannot escape.
 * - `skin.log` lines reach the caller's log surface; a hostile script
 *   can emit misleading text there. Treat log lines as untrusted data.
 * - Timing side channels (how long a run takes) are not mitigated.
 */
class SkinScriptSandbox {

    /** One sandboxed run: eval the source, call onEvent(ctx) if defined. */
    fun run(
        source: String,
        event: String,
        args: Map<String, String>,
        onLog: (String) -> Unit = {},
    ): SkinScriptRunOutcome {
        if (source.toByteArray(Charsets.UTF_8).size > PluginContract.MAX_SCRIPT_BYTES) {
            return SkinScriptRunOutcome.Failed(SkinScriptFailure.TooLarge)
        }
        val logs = ArrayList<String>()
        return try {
            RhinoSession.create().use { session ->
                session.putBridge("skin", buildSkinBridge(logs, onLog))
                // 单一墙钟预算：eval 与 onEvent 调用共享同一截止
                // 时间，每段开始前按剩余时间重算预算；任一段超限
                // 即整轮失败。剩余 ≤0 时直接按超预算失败，不再进
                // 入下一段（避免零预算调用仍跑一段采样粒度）。
                val deadlineNanos = System.nanoTime() + RUN_BUDGET_MS * 1_000_000L
                val evalRemainingMs = remainingMs(deadlineNanos)
                if (evalRemainingMs <= 0L) {
                    return SkinScriptRunOutcome.Failed(SkinScriptFailure.BudgetExceeded)
                }
                session.eval(source, "skin-script", evalRemainingMs)
                val fn = session.getFunction("onEvent")
                    ?: return SkinScriptRunOutcome.Skipped(SkinScriptSkip.NO_HANDLER)
                val callRemainingMs = remainingMs(deadlineNanos)
                if (callRemainingMs <= 0L) {
                    return SkinScriptRunOutcome.Failed(SkinScriptFailure.BudgetExceeded)
                }
                val ctx = buildCtx(event, args)
                val raw = session.callFunction(fn, arrayOf(ctx), callRemainingMs)
                SkinScriptRunOutcome.Ok(
                    value = capReturn(raw),
                    logLines = logs.toList(),
                )
            }
        } catch (e: ScriptBudgetExceeded) {
            SkinScriptRunOutcome.Failed(SkinScriptFailure.BudgetExceeded)
        } catch (e: Exception) {
            // Sanitized kind only: never the message (it may quote source).
            SkinScriptRunOutcome.Failed(
                SkinScriptFailure.error(sanitizeScriptError(e).kind),
            )
        }
    }

    /** 截止时间剩余毫秒（可为负，调用方据此判超限）。 */
    private fun remainingMs(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()) / 1_000_000L

    private fun buildSkinBridge(
        logs: MutableList<String>,
        onLog: (String) -> Unit,
    ): NativeObject {
        val skin = NativeObject()
        skin.put("log", skin, BridgeFunction { bridgeArgs ->
            val text = (bridgeArgs.getOrNull(0) as? CharSequence)?.toString()
                ?: return@BridgeFunction null
            if (text.length > MAX_LOG_LINE_CHARS) return@BridgeFunction null
            if (logs.size >= MAX_LOG_LINES) return@BridgeFunction null
            logs.add(text)
            runCatching { onLog(text) }
            null
        })
        return skin
    }

    private fun buildCtx(event: String, args: Map<String, String>): NativeObject {
        val ctx = NativeObject()
        ctx.put("event", ctx, event)
        val argsObj = NativeObject()
        for ((k, v) in args) {
            argsObj.put(k, argsObj, v)
        }
        ctx.put("args", ctx, argsObj)
        return ctx
    }

    private fun capReturn(raw: Any?): String? {
        val text = when (raw) {
            null -> return null
            is CharSequence -> raw.toString()
            is Number, is Boolean -> raw.toString()
            else -> return null
        }
        return if (text.length > MAX_RETURN_CHARS) text.substring(0, MAX_RETURN_CHARS) else text
    }

    companion object {
        /**
         * 全流程墙钟预算（eval＋onEvent 调用共享一个截止时间，
         * 不是每段各 500ms；与语音宿主后台预算同值）。
         */
        const val RUN_BUDGET_MS = 500L
        const val MAX_LOG_LINE_CHARS = 1000
        const val MAX_LOG_LINES = 50
        const val MAX_RETURN_CHARS = 4096
    }
}

/** Outcome of one sandboxed run. */
sealed interface SkinScriptRunOutcome {
    data class Ok(val value: String?, val logLines: List<String>) : SkinScriptRunOutcome
    data class Skipped(val reason: SkinScriptSkip) : SkinScriptRunOutcome
    data class Failed(val failure: SkinScriptFailure) : SkinScriptRunOutcome
}

enum class SkinScriptSkip {
    NO_HANDLER,
}

sealed interface SkinScriptFailure {
    data object TooLarge : SkinScriptFailure
    data object BudgetExceeded : SkinScriptFailure

    /** Script error, carrying only the sanitized exception kind, never source text. */
    data class Error(val kind: String) : SkinScriptFailure

    companion object {
        fun error(kind: String): SkinScriptFailure = Error(kind)
    }
}
