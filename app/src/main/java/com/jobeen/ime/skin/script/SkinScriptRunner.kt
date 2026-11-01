package com.jobeen.ime.skin.script

/**
 * THE run-script entry point for the later wiring wave.
 *
 * Later wave (action wiring: runScript/openScript/#toggleScriptView and the
 * key-action/parser changes) must call exactly this — not the sandbox
 * directly, not the store's raw source:
 *
 * ```
 * val runner = SkinScriptRunner(store)
 * when (val result = runner.runScript(scriptId, event, args)) {
 *     is SkinScriptResult.Ok -> // result.value is data, NOT an action yet
 *     is SkinScriptResult.Skipped -> // nothing ran (see reason)
 *     is SkinScriptResult.Failed -> // script failed (see failure)
 * }
 * ```
 *
 * Rules enforced here (not left to callers):
 * - Unknown id: Skipped(NOT_FOUND) — never a crash, never a run.
 * - Disabled script: Skipped(DISABLED) — a disabled script NEVER executes,
 *   whatever event or args arrive. Toggle-off is a hard kill switch.
 * - No onEvent handler: Skipped(NO_HANDLER) — a script that only defines
 *   helpers is inert, not an error.
 * - Budget overrun / script error / oversize source: Failed — the failure
 *   carries a reason code (or a sanitized exception kind), never script
 *   source text and never a raw exception message.
 *
 * THREADING: [runScript] runs synchronously on the calling thread with a
 * 500ms wall-clock budget. Callers MUST call it off the main thread
 * (background dispatcher / worker thread); calling it on the main thread
 * can stall the UI for up to the budget. The sandbox itself is fresh per
 * run, so concurrent runs on different threads are independent — but a
 * single [SkinScriptRunner] instance carries no locking for the store,
 * matching the store's own single-thread coordination contract.
 *
 * NO action wiring in this wave: the returned value is opaque data. The
 * later wave decides what (if anything) a value means — e.g. interpreting
 * it as an action name — and adds the action bridge then.
 */
class SkinScriptRunner(
    private val store: SkinScriptRegistry,
    private val sandbox: SkinScriptSandbox = SkinScriptSandbox(),
) {

    /**
     * Run one App-level script for one event.
     * @param scriptId the App-level script id (as stored, not per-skin)
     * @param event the event name passed to onEvent as ctx.event
     * @param args string arguments passed to onEvent as ctx.args
     */
    fun runScript(
        scriptId: String,
        event: String,
        args: Map<String, String> = emptyMap(),
        onLog: (String) -> Unit = {},
    ): SkinScriptResult {
        val script = store.get(scriptId)
            ?: return SkinScriptResult.Skipped(SkinScriptSkipReason.NOT_FOUND)
        if (!script.meta.enabled) {
            return SkinScriptResult.Skipped(SkinScriptSkipReason.DISABLED)
        }
        return when (val outcome = sandbox.run(script.source, event, args, onLog)) {
            is SkinScriptRunOutcome.Ok ->
                SkinScriptResult.Ok(outcome.value, outcome.logLines)
            is SkinScriptRunOutcome.Skipped ->
                SkinScriptResult.Skipped(SkinScriptSkipReason.NO_HANDLER)
            is SkinScriptRunOutcome.Failed ->
                SkinScriptResult.Failed(outcome.failure)
        }
    }
}

/** Result of [SkinScriptRunner.runScript]. */
sealed interface SkinScriptResult {
    /** Script ran; [value] is opaque data (not an action — see class doc). */
    data class Ok(val value: String?, val logLines: List<String>) : SkinScriptResult

    /** Nothing ran; the script was missing, disabled, or handler-less. */
    data class Skipped(val reason: SkinScriptSkipReason) : SkinScriptResult

    /** The script failed to run; see [failure] for the reason code. */
    data class Failed(val failure: SkinScriptFailure) : SkinScriptResult
}

enum class SkinScriptSkipReason {
    NOT_FOUND,
    DISABLED,
    NO_HANDLER,
}
