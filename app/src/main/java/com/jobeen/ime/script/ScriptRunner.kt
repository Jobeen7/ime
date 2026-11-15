// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 脚本输入（注入变量；与对端 z42 的 Map 逐项 setGlobalString 一致）。 */
data class ScriptInputs(
    val searchText: String = "",
    val selectedText: String = "",
    /** 剪贴板内容：仅当脚本 allowPasteboard 时传入，否则传 ""（调用方门控）。 */
    val pasteboardContent: String = "",
)

/** 一次脚本运行的结果（与对端 te4(output 列表，日志列表，耗时秒) 对应）。 */
data class ScriptRunResult(
    val output: List<String>,
    val logs: List<String>,
    /** 本次运行里 $insertText 接到的文本（管理页试跑的可见结果）。 */
    val insertedTexts: List<String>,
    val elapsedSeconds: Double,
)

/** 脚本运行失败（携带 [ScriptFailure]，不含源码）。 */
class ScriptRunException(val failure: ScriptFailure) : Exception(
    when (failure) {
        is ScriptFailure.Error -> failure.message.ifEmpty { "脚本出错" }
        ScriptFailure.Timeout -> "脚本执行超时（30 秒）"
        ScriptFailure.Cancelled -> "脚本已取消"
        ScriptFailure.NoOutputFunction -> "脚本未定义 output 函数"
    },
)

/**
 * 脚本运行器（WO52 8.1）：执行约定照 z42：
 * eval(code, "jime-script://editor.js") → 必须定义 output 函数 →
 * 等待其 Promise 结算 → 返回（output 列表，日志列表，耗时秒）；
 * 30 秒超时抛错；取消抛错。
 *
 * 线程：整场运行固定在一条专用的单线程 dispatcher 上（引擎是单线程
 * 的）；取消时调 engine.cancel()（线程安全）再把 CancellationException
 * 原样抛给调用方（不吞协程取消）。
 *
 * 宿主接线：调用方以 [hostFactory] 供一个 host，运行器把本次运行的
 * 日志 sink 与 $http 完成回调接进去——host 只负责把 $log 行写进
 * sink、把 $http 完成交给回调，不碰 JS。$http 完成由运行器在引擎
 * 线程上经 settle()＋runJobs() 投递给脚本的 `__jimeHttpResult`
 *（见 QuickJsEngine／quickjs_jni.c 的投递约定）。
 */
class ScriptRunner {

    /**
     * 运行一段脚本。
     *
     * @param source 脚本源码。
     * @param inputs 注入变量（$searchText／$selectedText／$pasteboardContent；
     *   pasteboardContent 由调用方按脚本的 allowPasteboard 门控，禁读时传 ""）。
     * @param hostFactory 供 host：参数为（日志 sink，$http 完成回调）。
     * @param timeoutNanos 执行截止（默认 30 秒，与对端口径一致）。
     * @return output 列表＋日志列表＋$insertText 文本＋耗时秒。
     * @throws ScriptRunException 脚本失败（eval 错／无 output／结算被拒／超时）。
     * @throws CancellationException 调用方取消（引擎已 cancel）。
     */
    suspend fun run(
        source: String,
        inputs: ScriptInputs = ScriptInputs(),
        hostFactory: (logSink: (String) -> Unit, onHttpCompletion: (HttpCompletion) -> Unit) -> ScriptHost = { _, _ -> ScriptHost.NOOP },
        timeoutNanos: Long = QuickJsEngine.TIMEOUT_NANOS,
    ): ScriptRunResult {
        val logs = mutableListOf<String>()
        val insertedTexts = mutableListOf<String>()
        val completions = ConcurrentLinkedQueue<HttpCompletion>()
        val startNanos = System.nanoTime()
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "script-run").also { it.isDaemon = true }
        }.asCoroutineDispatcher()
        try {
            return withContext(dispatcher) {
                val engine = QuickJsEngine.create()
                try {
                    val delegate = hostFactory(
                        { text -> appendLog(logs, text) },
                        { completion -> completions.add(completion) },
                    )
                    engine.host = RunnerHost(delegate, completions::add, insertedTexts)
                    engine.setDeadline(timeoutNanos)
                    engine.setGlobalString("\$searchText", inputs.searchText)
                    engine.setGlobalString("\$selectedText", inputs.selectedText)
                    engine.setGlobalString("\$pasteboardContent", inputs.pasteboardContent)
                    engine.eval(source)?.let { throw ScriptRunException(it) }
                    if (!engine.isFunction("output")) {
                        throw ScriptRunException(ScriptFailure.NoOutputFunction)
                    }
                    engine.callOutput()?.let { throw ScriptRunException(it) }
                    // 等待 Promise 结算：泵微任务＋投递 $http 完成，直到
                    // output 结算或墙钟超时。runJobs 本身受引擎截止
                    // （deadline）约束；这里再补一道墙钟上限，防止
                    // output 的 Promise 永远不结算时空转。
                    var result: ScriptRunResult? = null
                    while (result == null) {
                        engine.runJobs()?.let { throw ScriptRunException(it) }
                        drainCompletions(engine, completions)
                        when (engine.outputState()) {
                            OutputState.FULFILLED -> {
                                val output = engine.outputValue().orEmpty()
                                result = ScriptRunResult(
                                    output = output,
                                    logs = logs.toList(),
                                    insertedTexts = insertedTexts.toList(),
                                    elapsedSeconds = (System.nanoTime() - startNanos) / 1.0e9,
                                )
                            }
                            OutputState.REJECTED -> {
                                throw ScriptRunException(
                                    ScriptFailure.Error(
                                        engine.outputError() ?: "output rejected",
                                    ),
                                )
                            }
                            OutputState.PENDING -> {
                                val elapsed = System.nanoTime() - startNanos
                                if (elapsed >= timeoutNanos) {
                                    throw ScriptRunException(ScriptFailure.Timeout)
                                }
                                delay(POLL_DELAY_MS)
                            }
                        }
                    }
                    result
                } finally {
                    engine.destroy()
                }
            }
        } catch (e: CancellationException) {
            // 协程取消：引擎已在 withContext 块外——destroy 在 finally
            // 里已跑；这里只把取消原样抛给调用方（不转 ScriptRunException）。
            throw e
        } finally {
            dispatcher.close()
        }
    }

    private fun drainCompletions(engine: QuickJsEngine, completions: ConcurrentLinkedQueue<HttpCompletion>) {
        while (true) {
            val completion = completions.poll() ?: return
            engine.settle(completion.id, !completion.rejected, completion.payload)
            // 投递可能产生新微任务，马上泵一次。
            engine.runJobs()?.let { throw ScriptRunException(it) }
        }
    }

    private fun appendLog(logs: MutableList<String>, text: String) {
        if (text.length > AndroidScriptHost.MAX_LOG_LINE_CHARS) return
        if (logs.size >= AndroidScriptHost.MAX_LOG_LINES) return
        logs.add(text)
    }

    /**
     * 运行期 host 包装：6 个宿主 API 直通 delegate；$http 完成只进
     * 运行器的队列（不转给 delegate，避免双投递）；$insertText 另记
     * 一份进结果（管理页试跑的可见结果）。
     * 显式逐方法转发（不用 `by delegate`：接口新增方法时委托转发
     * 在增量编译下会丢方法，见 AGENTS.md 的委托陷阱）。
     */
    private class RunnerHost(
        private val delegate: ScriptHost,
        private val onCompletion: (HttpCompletion) -> Unit,
        private val insertedTexts: MutableList<String>,
    ) : ScriptHost {
        override fun log(text: String) = delegate.log(text)
        override fun http(json: String): Long = delegate.http(json)
        override fun setClipboard(text: String) = delegate.setClipboard(text)
        override fun insertText(text: String) {
            if (text.isEmpty()) return
            insertedTexts.add(text)
            delegate.insertText(text)
        }
        override fun openUrl(url: String): Boolean = delegate.openUrl(url)
        override fun userNotification(title: String, shortcutName: String, content: String): Boolean =
            delegate.userNotification(title, shortcutName, content)
        override fun onHttpCompletion(completion: HttpCompletion) = onCompletion(completion)
    }

    companion object {
        /** 等待结算的轮询间隔（毫秒）。 */
        const val POLL_DELAY_MS = 10L
    }
}
