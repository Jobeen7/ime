package com.jobeen.ime.skin.script

import java.util.concurrent.ConcurrentHashMap

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
 * - Circuit breaker (per script id): [CIRCUIT_BREAKER_MAX_FAILURES]
 *   CONSECUTIVE counted failures trip the script — later runs are
 *   refused immediately with Failed(CircuitOpen) and never execute,
 *   until the app restarts (state is in-memory only, a fresh Runner
 *   is clean), the script is reloaded with different source (the
 *   source change resets the count — see [runScript]), or
 *   [resetCircuit] is called. Only genuine script faults count:
 *   BudgetExceeded and Error. TooLarge (input validation, caller
 *   dependent), TooManyConcurrent (transient contention) and
 *   Cancelled (host-initiated) never count; any non-failure outcome
 *   (Ok, or Skipped/NO_HANDLER) resets the consecutive count. The
 *   breaker is skin-sandbox only: the voice-plugin side never goes
 *   through this class and is untouched.
 * - Cancel: [cancelRun] interrupts an in-flight run for a script id
 *   (see [SkinScriptSandbox.cancelRun]); the run reports
 *   Failed(Cancelled). Dispatch-layer single-flight Job cancel in
 *   KeyActionListener still owns the "don't dispatch the result"
 *   half; this is the "stop the eval" half.
 *
 * THREADING: [runScript] runs synchronously on the calling thread with a
 * single 500ms total wall-clock budget shared by script eval and the
 * onEvent call (not 500ms each — see SkinScriptSandbox). Callers MUST call it off the main thread
 * (background dispatcher / worker thread); calling it on the main thread
 * can stall the UI for up to the budget. The sandbox itself is fresh per
 * run, so concurrent runs on different threads are independent — but a
 * single [SkinScriptRunner] instance carries no locking for the store,
 * matching the store's own single-thread coordination contract.
 *
 * NO action wiring in this class: the returned value is opaque data.
 * The key-action wiring wave interprets it (see [SkinScriptKeyBridge]:
 * only an exact no-value whitelist action name becomes a dispatchable
 * action; anything else stays data) and adds the action bridge there.
 */
class SkinScriptRunner(
    private val store: SkinScriptRegistry,
    private val sandbox: SkinScriptSandbox = SkinScriptSandbox(),
) {

    /** Per-script breaker state (in-memory only: restart = clean slate). */
    private val circuits = ConcurrentHashMap<String, CircuitState>()

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
        // 熔断检查（NOT_FOUND／DISABLED 优先：禁行只拦“会执行”的
        // 脚本）。源码变了＝脚本已重载（删后重加新源码）：先清零
        // 再判；同源码重装不复位（同一脚本同一故障，不应复活）。
        val tripped = circuits.compute(scriptId) { _, state ->
            val current = state ?: CircuitState()
            if (current.lastSource != null && current.lastSource != script.source) {
                CircuitState(failures = 0, tripped = false, lastSource = script.source)
            } else {
                current.copy(lastSource = script.source)
            }
        }?.tripped == true
        if (tripped) {
            return SkinScriptResult.Failed(SkinScriptFailure.CircuitOpen)
        }
        return when (
            val outcome = sandbox.run(script.source, event, args, onLog, runId = scriptId)
        ) {
            is SkinScriptRunOutcome.Ok -> {
                recordSuccess(scriptId)
                SkinScriptResult.Ok(outcome.value, outcome.logLines)
            }
            is SkinScriptRunOutcome.Skipped -> {
                recordSuccess(scriptId)
                SkinScriptResult.Skipped(SkinScriptSkipReason.NO_HANDLER)
            }
            is SkinScriptRunOutcome.Failed -> {
                recordFailure(scriptId, outcome.failure)
                SkinScriptResult.Failed(outcome.failure)
            }
        }
    }

    /**
     * 取消该脚本的在途运行（有则中断，无则 false）。见
     * [SkinScriptSandbox.cancelRun]。
     */
    fun cancelRun(scriptId: String): Boolean = sandbox.cancelRun(scriptId)

    /**
     * 手动复位指定脚本的熔断（脚本重载／管理页重开时调；应用重启
     * 自动复位，无须调）。复位后下次运行正常执行。
     */
    fun resetCircuit(scriptId: String) {
        circuits.remove(scriptId)
    }

    /** 复位全部脚本的熔断。 */
    fun resetAllCircuits() {
        circuits.clear()
    }

    /** 计一次故障：仅预算超限与脚本错误计入；其余失败一律不计。 */
    private fun recordFailure(scriptId: String, failure: SkinScriptFailure) {
        if (failure != SkinScriptFailure.BudgetExceeded &&
            failure !is SkinScriptFailure.Error
        ) {
            return
        }
        circuits.compute(scriptId) { _, state ->
            val current = state ?: CircuitState()
            val failures = current.failures + 1
            current.copy(
                failures = failures,
                tripped = failures >= CIRCUIT_BREAKER_MAX_FAILURES,
            )
        }
    }

    /** 非故障结果清零连续故障计数（成功即证明脚本当前可用）。 */
    private fun recordSuccess(scriptId: String) {
        circuits.computeIfPresent(scriptId) { _, state ->
            state.copy(failures = 0, tripped = false)
        }
    }

    companion object {
        /** 熔断阈值：同一脚本连续故障达 2 次即禁行（第 3 次起直接拒绝）。 */
        const val CIRCUIT_BREAKER_MAX_FAILURES = 2
    }
}

/** One script's breaker state: consecutive counted failures + trip flag. */
private data class CircuitState(
    val failures: Int = 0,
    val tripped: Boolean = false,
    val lastSource: String? = null,
)

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
