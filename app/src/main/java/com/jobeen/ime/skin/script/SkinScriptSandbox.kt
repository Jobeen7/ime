package com.jobeen.ime.skin.script

import com.jobeen.ime.base.speech.cloud.BridgeFunction
import com.jobeen.ime.base.speech.cloud.PluginContract
import com.jobeen.ime.base.speech.cloud.RhinoSession
import com.jobeen.ime.base.speech.cloud.ScriptBudgetExceeded
import com.jobeen.ime.base.speech.cloud.sanitizeScriptError
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
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
 * - No unbounded concurrency: ALL skin-script runs (key-triggered and
 *   management-page test runs) share one global cap of
 *   [MAX_CONCURRENT_SCRIPT_RUNS] concurrent runs, enforced here by an
 *   atomic counter — a run past the cap fails immediately with
 *   [SkinScriptFailure.TooManyConcurrent], never queues inside the
 *   sandbox. The key-dispatch path additionally queues on
 *   [acquireRunPermit] (bounded wait, cancellable) so a burst of key
 *   presses waits for a slot instead of failing outright; the same
 *   scriptId's earlier run is still cancelled first (single-flight,
 *   see KeyActionListener). Input args are capped in count and total
 *   chars ([MAX_SCRIPT_ARGS]/[MAX_SCRIPT_ARGS_CHARS]).
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
 *   budget, the log/return/args caps, and the global concurrency cap
 *   above — not a byte budget. The concurrency cap bounds the
 *   *aggregate* allocation rate (at most 4 runs allocating at once),
 *   but a single run can still spike the heap inside its 500ms
 *   budget; that is the accepted trust boundary for user-installed
 *   scripts on the owner's own device (scripts are installed by the
 *   user, not fetched from a remote party — see below). A future
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
        if (args.size > MAX_SCRIPT_ARGS || argsTotalChars(args) > MAX_SCRIPT_ARGS_CHARS) {
            return SkinScriptRunOutcome.Failed(SkinScriptFailure.TooLarge)
        }
        // 全局并发硬上限（原子计数，全部实例共用）：超限的运行立即
        // 失败，不占槽、不排队（排队只在分发层做，见 acquireRunPermit）。
        if (activeRuns.incrementAndGet() > MAX_CONCURRENT_SCRIPT_RUNS) {
            activeRuns.decrementAndGet()
            return SkinScriptRunOutcome.Failed(SkinScriptFailure.TooManyConcurrent)
        }
        try {
            return runInner(source, event, args, onLog)
        } finally {
            activeRuns.decrementAndGet()
        }
    }

    /** 输入参数总字符数（键＋值；超限即拒，不进沙箱）。 */
    private fun argsTotalChars(args: Map<String, String>): Int {
        var total = 0
        for ((k, v) in args) {
            total += k.length + v.length
            if (total > MAX_SCRIPT_ARGS_CHARS) return total
        }
        return total
    }

    /** 当前在途运行数（单测与压力测试观测用）。 */
    internal fun activeRunCount(): Int = activeRuns.get()

    private fun runInner(
        source: String,
        event: String,
        args: Map<String, String>,
        onLog: (String) -> Unit,
    ): SkinScriptRunOutcome {
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

        /**
         * 全局并发上限：全部皮肤脚本（按键触发＋管理页试跑）同时
         * 最多 4 个在途运行。4 槽 × 500ms 预算封顶了脚本的总分配
         * 速率与 CPU 占用，又不至于让连续按键的脚本互相饿死。
         */
        const val MAX_CONCURRENT_SCRIPT_RUNS = 4

        /** 输入参数上限：个数与总字符数（键＋值），超限即拒。 */
        const val MAX_SCRIPT_ARGS = 32
        const val MAX_SCRIPT_ARGS_CHARS = 65_536

        /**
         * 分发层排队等许可的上限（毫秒）：4 槽全满时，第 5 个按键
         * 脚本最多等 2 秒（约 4 轮 500ms 预算），超时即放弃本次
         * 执行、不重试，只记元数据。等许可本身可取消：同 scriptId
         * 再按取消旧任务时，排队中的旧任务随取消退出队列。
         */
        const val RUN_QUEUE_TIMEOUT_MS = 2_000L

        /** 全局在途运行计数（全部沙箱实例共用；硬上限的执行点）。 */
        private val activeRuns = AtomicInteger(0)

        /**
         * 分发层排队许可（与 [activeRuns] 同值 4 槽）：按键脚本
         * 在后台任务里先取许可再进沙箱，取不到（超时）即放弃；
         * 取到后必须在 finally 里调 [releaseRunPermit] 归还。
         * 管理页试跑不走此许可，只受沙箱硬上限约束。
         */
        private val runPermits = Semaphore(MAX_CONCURRENT_SCRIPT_RUNS)

        /** 排队取一次运行许可（可取消；超时返回 false）。 */
        suspend fun acquireRunPermit(timeoutMs: Long = RUN_QUEUE_TIMEOUT_MS): Boolean =
            withTimeoutOrNull(timeoutMs) {
                runPermits.acquire()
                true
            } ?: false

        /** 归还一次运行许可（与 [acquireRunPermit] 配对）。 */
        fun releaseRunPermit() {
            runPermits.release()
        }
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

    /** 全局并发上限已满：本次运行未执行（分发层排队超时时不产生本失败，只记元数据）。 */
    data object TooManyConcurrent : SkinScriptFailure

    /** Script error, carrying only the sanitized exception kind, never source text. */
    data class Error(val kind: String) : SkinScriptFailure

    companion object {
        fun error(kind: String): SkinScriptFailure = Error(kind)
    }
}
