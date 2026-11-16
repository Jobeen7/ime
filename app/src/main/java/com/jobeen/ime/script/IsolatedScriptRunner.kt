// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.jobeen.ime.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 隔离脚本运行器（主进程侧）：与 [ScriptRunner] 同签名，可直接替换调用方
 * 的 `ScriptRunner()`（管理页试跑即如此），QuickJS 执行发生在隔离进程。
 *
 * 通信（Binder，双向）：
 * - 主 → 隔离：bindService 拿到 [IScriptIsolationService]，一次同步
 *   `runScript()` 调用把源码＋入参（含已备好的 `$key` 变量）传进去，
 *   在 [Dispatchers.IO] 上阻塞等结果 Bundle 回来；
 * - 隔离 → 主：6 个宿主 API 里需主进程能力的 5 个经 [IScriptHost]
 *   回调回来（$log 不走 Binder，见 [IsolatedScriptHost]）；$http 的
 *   完成经 [IScriptHostCallback] 单向投递回隔离进程的完成队列。
 *
 * 联网代理（主进程内，隔离进程不直接联网）：
 * - `$http` 先过 [ScriptHttpProxyPolicy]：URL 的 host 须在脚本头部的
 *   `@connect` 白名单内，否则不发请求，直接给脚本一次 rejected 完成
 *   （错误文本为 `script_http_not_allowed`，含 host，不含密钥）；
 * - 放行的请求交调用方给的 delegate host（原 AndroidScriptHost）实发；
 *   代理只转发脚本自己给的请求头，不附加任何应用凭据；
 * - 参数非法（JSON 坏／URL 不合法）时同样交 delegate 处理，保持原有
 *   “先拿 id 再投递 rejected 完成”的行为。
 *
 * 失败与降级（不静默回落到进程内执行——静默回落等于悄悄拆掉隔离边界）：
 * - 绑定超时（[BIND_TIMEOUT_MS]）／Binder 异常／隔离进程死亡／结果
 *   Bundle 非法 → 抛 [ScriptRunException]（Error，文案
 *   `script_isolated_unavailable`），调用方按普通脚本错误展示；
 * - 脚本自身超时仍由隔离进程内的引擎截止判定（Timeout），与原行为一致；
 * - 隔离进程无响应（Binder 调用超过脚本超时＋[CALL_GRACE_MS] 宽限）→
 *   按 Timeout 抛错，并尽力调 `cancelRun()`＋解绑（在途 Binder 调用
 *   本身无法取消，等隔离侧引擎截止后自行返回，线程随即释放）；
 * - 协程取消 → 尽力调 `cancelRun()`（隔离侧 engine.cancel() 中断引擎）
 *   后原样重抛 [CancellationException]；
 * - 每次运行结束都解绑，不缓存连接（隔离进程随即由系统回收）。
 *
 * 限制：源码＋变量总量受 Binder 事务（约 1 MiB）约束，源码超
 * [MAX_SOURCE_CHARS] 字符时在绑定前直接抛错，不发 Binder。
 */
class IsolatedScriptRunner(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    /**
     * 与 [ScriptRunner.run] 同签名（hostFactory 的 logSink 在此路径下
     * 不会被调——日志在隔离进程内收集、随结果返回；onCompletion 同理，
     * $http 完成由代理经 Binder 回调路由，不走这里）。
     */
    suspend fun run(
        source: String,
        inputs: ScriptInputs = ScriptInputs(),
        hostFactory: (logSink: (String) -> Unit, onHttpCompletion: (HttpCompletion) -> Unit) -> ScriptHost = { _, _ -> ScriptHost.NOOP },
        timeoutNanos: Long = QuickJsEngine.TIMEOUT_NANOS,
    ): ScriptRunResult {
        if (source.length > MAX_SOURCE_CHARS) {
            throw ScriptRunException(
                ScriptFailure.Error(appContext.getString(R.string.script_too_large)),
            )
        }
        val allowlist = ScriptConnectAllowlist.parse(source)
        val service = bind() ?: throw ScriptRunException(
            ScriptFailure.Error(appContext.getString(R.string.script_isolated_unavailable)),
        )
        try {
            return withContext(Dispatchers.IO) {
                val proxy = ProxyScriptHost(hostFactory, allowlist)
                val bundle = try {
                    withTimeoutOrNull(timeoutNanos / 1_000_000 + CALL_GRACE_MS) {
                        service.runScript(
                            source,
                            inputs.searchText,
                            inputs.selectedText,
                            inputs.pasteboardContent,
                            timeoutNanos,
                            proxy,
                            inputs.variables.keys.toTypedArray(),
                            inputs.variables.values.toTypedArray(),
                            inputs.skippedVariableLogLines.toTypedArray(),
                        )
                    }
                } catch (e: RemoteException) {
                    throw ScriptRunException(
                        ScriptFailure.Error(appContext.getString(R.string.script_isolated_unavailable)),
                    )
                }
                if (bundle == null) {
                    runCatching { service.cancelRun() }
                    throw ScriptRunException(ScriptFailure.Timeout)
                }
                if (!bundle.getBoolean(ScriptIsolationService.KEY_SUCCESS, false)) {
                    throw bundle.toFailure()
                }
                bundle.toRunResult()
            }
        } catch (e: CancellationException) {
            runCatching { service.cancelRun() }
            throw e
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    // --- 绑定 ---

    private lateinit var connection: ServiceConnection

    /** 绑定隔离 Service，超时返回 null（调用方按不可用抛错）。 */
    private suspend fun bind(): IScriptIsolationService? {
        return withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (cont.isActive) {
                            cont.resume(IScriptIsolationService.Stub.asInterface(binder))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
                val intent = Intent(appContext, ScriptIsolationService::class.java)
                if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    runCatching { appContext.unbindService(connection) }
                }
            }
        }
    }

    // --- 主进程侧 $http 代理＋宿主转发 ---

    /**
     * 主进程侧代理 host（[IScriptHost.Stub]）：白名单门＋delegate 转发。
     * delegate 由调用方的 hostFactory 提供（原 AndroidScriptHost），其
     * logSink／onCompletion 在此路径下不用（见 [run] 的签名注释）——
     * $http 完成按 id 路由回发起那次调用的 Binder 回调。
     * 拒绝侧 id 自 [DENIED_HTTP_ID_BASE] 起，与 delegate 自 1 起的 id
     * 空间不重叠（一次运行发不出 10 亿次 $http）。
     *
     * 时序注意：delegate.http() 在参数非法时会在返回 id 之前同步投递一次
     * rejected 完成——此时路由表里还没有该 id 的回调，[routeCompletion]
     * 把这类完成暂存到 orphans，[registerCallback] 登记后再补投递。
     */
    private inner class ProxyScriptHost(
        hostFactory: (logSink: (String) -> Unit, onHttpCompletion: (HttpCompletion) -> Unit) -> ScriptHost,
        private val allowlist: Set<String>,
    ) : IScriptHost.Stub() {
        private val delegate: ScriptHost = hostFactory({}, ::routeCompletion)
        private val pendingCallbacks = ConcurrentHashMap<Long, IScriptHostCallback>()
        private val orphanCompletions = ConcurrentLinkedQueue<HttpCompletion>()
        private val nextDeniedId = AtomicLong(DENIED_HTTP_ID_BASE)
        private val deniedMessageFormat = appContext.getString(R.string.script_http_not_allowed)

        private fun routeCompletion(completion: HttpCompletion) {
            val callback = pendingCallbacks.remove(completion.id)
            if (callback != null) {
                runCatching {
                    callback.onHttpCompletion(completion.id, completion.rejected, completion.payload)
                }
            } else {
                orphanCompletions.add(completion)
            }
        }

        private fun registerCallback(id: Long, callback: IScriptHostCallback) {
            pendingCallbacks[id] = callback
            // 补投递同步到达的孤完成（只扫登记前已有的条数，不循环等新到的）。
            repeat(orphanCompletions.size) {
                val orphan = orphanCompletions.poll() ?: return
                if (orphan.id == id) {
                    runCatching {
                        callback.onHttpCompletion(orphan.id, orphan.rejected, orphan.payload)
                    }
                } else {
                    orphanCompletions.add(orphan)
                }
            }
        }

        override fun http(json: String, callback: IScriptHostCallback): Long {
            val prepared = try {
                HttpRequestParser.parse(json)
            } catch (e: HttpParamException) {
                // 参数非法：交 delegate 走原有“先拿 id 再 rejected 完成”路径。
                val id = delegate.http(json)
                registerCallback(id, callback)
                return id
            }
            return when (ScriptHttpProxyPolicy.check(prepared.url, allowlist)) {
                ProxyDecision.Allowed -> {
                    val id = delegate.http(json)
                    registerCallback(id, callback)
                    id
                }
                is ProxyDecision.Denied -> {
                    val id = nextDeniedId.getAndIncrement()
                    val host = ScriptConnectAllowlist.hostOf(prepared.url).orEmpty()
                    runCatching {
                        callback.onHttpCompletion(id, true, deniedMessageFormat.format(host))
                    }
                    id
                }
            }
        }

        override fun setClipboard(text: String) = delegate.setClipboard(text)

        override fun insertText(text: String) = delegate.insertText(text)

        override fun openUrl(url: String): Boolean = delegate.openUrl(url)

        override fun userNotification(title: String, shortcutName: String, content: String): Boolean =
            delegate.userNotification(title, shortcutName, content)
    }

    private fun Bundle.toFailure(): ScriptRunException {
        val failure = when (getString(ScriptIsolationService.KEY_ERROR_KIND)) {
            "timeout" -> ScriptFailure.Timeout
            "cancelled" -> ScriptFailure.Cancelled
            "no-output" -> ScriptFailure.NoOutputFunction
            else -> ScriptFailure.Error(
                getString(ScriptIsolationService.KEY_ERROR_MESSAGE).orEmpty(),
            )
        }
        return ScriptRunException(failure)
    }

    private fun Bundle.toRunResult(): ScriptRunResult {
        val output = getStringArrayList(ScriptIsolationService.KEY_OUTPUT).orEmpty()
        val logs = getStringArrayList(ScriptIsolationService.KEY_LOGS).orEmpty()
        val inserted = getStringArrayList(ScriptIsolationService.KEY_INSERTED).orEmpty()
        val elapsed = getDouble(ScriptIsolationService.KEY_ELAPSED, 0.0)
        return ScriptRunResult(
            output = output,
            logs = logs,
            insertedTexts = inserted,
            elapsedSeconds = elapsed,
        )
    }

    companion object {
        /** 绑定隔离 Service 的等待上限（毫秒）。 */
        const val BIND_TIMEOUT_MS = 10_000L

        /** Binder 调用在脚本超时之外的宽限（毫秒，等隔离侧收尾回包）。 */
        const val CALL_GRACE_MS = 10_000L

        /** 源码上限（字符，Binder 事务约 1 MiB，留足变量与结果的余量）。 */
        const val MAX_SOURCE_CHARS = 512 * 1024

        /** 拒绝侧 $http id 起点（delegate 自 1 起，不重叠）。 */
        const val DENIED_HTTP_ID_BASE = 1_000_000_000L
    }
}
