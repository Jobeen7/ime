// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * 脚本隔离 Service（跑在 `android:isolatedProcess` 的进程里，零权限）：
 * QuickJS 引擎只在这里建，脚本源码只在这里 eval；本进程不直接联网、
 * 不直接读文件（没有 INTERNET 权限，也读不到应用私有文件），6 个宿主
 * API 里需主进程能力的 5 个经 Binder 回主进程（[IsolatedScriptHost]），
 * $log 只进本次运行的日志列表，随结果一起返回。
 *
 * 单航班：同一时刻只跑一段脚本，第二段直接返回 busy 错误 Bundle。
 * 取消：[IScriptIsolationService.cancelRun] 取消在途协程，ScriptRunner
 * 的取消接线调 engine.cancel() 中断引擎。
 *
 * 注意：隔离进程不跑 Application 的完整初始化路径（不同进程），本文件
 * 不依赖应用级单例／Timber，只用 android.util.Log（且只在异常路径）。
 */
class ScriptIsolationService : Service() {

    private val runScope = CoroutineScope(
        SupervisorJob() +
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "script-isolated-run").also { it.isDaemon = true }
            }.asCoroutineDispatcher(),
    )
    private val currentRun = AtomicReference<kotlinx.coroutines.Deferred<ScriptRunResult>?>(null)
    /** 单航班守门：检查＋起跑必须原子，否则并发的两段会同时进引擎。 */
    private val runLock = Any()

    private val binder = object : IScriptIsolationService.Stub() {
        override fun runScript(
            source: String,
            searchText: String,
            selectedText: String,
            pasteboardContent: String,
            timeoutNanos: Long,
            host: IScriptHost,
            variableKeys: Array<String>,
            variableValues: Array<String>,
            skippedVariableLogLines: Array<String>,
        ): android.os.Bundle {
            val variables = variableKeys.zip(variableValues).toMap()
            val inputs = ScriptInputs(
                searchText = searchText,
                selectedText = selectedText,
                pasteboardContent = pasteboardContent,
                variables = variables,
                skippedVariableLogLines = skippedVariableLogLines.toList(),
            )
            val deferred: kotlinx.coroutines.Deferred<ScriptRunResult>
            synchronized(runLock) {
                if (currentRun.get() != null) return errorBundle("busy", "")
                deferred = runScope.async {
                    ScriptRunner().run(
                        source = source,
                        inputs = inputs,
                        hostFactory = { logSink, onCompletion ->
                            IsolatedScriptHost(host, logSink, onCompletion)
                        },
                        timeoutNanos = timeoutNanos,
                    )
                }
                currentRun.set(deferred)
            }
            return try {
                successBundle(runBlocking { deferred.await() })
            } catch (e: CancellationException) {
                errorBundle("cancelled", "")
            } catch (e: ScriptRunException) {
                errorBundle(e.failure)
            } catch (e: Exception) {
                android.util.Log.w("ScriptIsolation", "isolated run failed", e)
                errorBundle(ScriptFailure.Error(e.message ?: "脚本出错"))
            } finally {
                currentRun.compareAndSet(deferred, null)
            }
        }

        override fun cancelRun() {
            currentRun.get()?.cancel()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        runScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val KEY_SUCCESS = "success"
        const val KEY_OUTPUT = "output"
        const val KEY_LOGS = "logs"
        const val KEY_INSERTED = "insertedTexts"
        const val KEY_ELAPSED = "elapsedSeconds"
        const val KEY_ERROR_KIND = "errorKind"
        const val KEY_ERROR_MESSAGE = "errorMessage"

        /** [KEY_ERROR_KIND] 取值：error／timeout／cancelled／no-output／busy。 */
        fun successBundle(result: ScriptRunResult): android.os.Bundle =
            android.os.Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putStringArrayList(KEY_OUTPUT, ArrayList(result.output))
                putStringArrayList(KEY_LOGS, ArrayList(result.logs))
                putStringArrayList(KEY_INSERTED, ArrayList(result.insertedTexts))
                putDouble(KEY_ELAPSED, result.elapsedSeconds)
            }

        fun errorBundle(failure: ScriptFailure): android.os.Bundle =
            when (failure) {
                is ScriptFailure.Error -> errorBundle("error", failure.message)
                ScriptFailure.Timeout -> errorBundle("timeout", "")
                ScriptFailure.Cancelled -> errorBundle("cancelled", "")
                ScriptFailure.NoOutputFunction -> errorBundle("no-output", "")
            }

        fun errorBundle(kind: String, message: String): android.os.Bundle =
            android.os.Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_ERROR_KIND, kind)
                putString(KEY_ERROR_MESSAGE, message)
            }
    }
}

/**
 * 隔离进程内的宿主实现：把 6 个宿主 API 接到主进程的 Binder host 上。
 *
 * - $log：不走 Binder，直接调本地 logSink（ScriptRunner 的日志列表），
 *   随运行结果返回；
 * - $http：同步调主进程 host.http() 拿 id；完成经主进程的
 *   [IScriptHostCallback] 单向投递回来，再经 [onHttpCompletion] 进
 *   运行器的完成队列（与原进程内行为一致：先拿 id 再回调）；
 * - Binder 断了（主进程死亡）：$http 返回一个本地 id 并立即投递一次
 *   rejected 完成（脚本看到一次正常的 $http 失败，不在引擎内抛错）；
 *   其余写类调用（setClipboard／insertText）尽力而为，读返回值类
 *   （openUrl／userNotification）断时返回 false（与“未授权限返回
 *   false，不抛错”同口径）。
 * - 本地 $http id 自 [LOCAL_HTTP_ID_BASE] 起，与主进程
 *   AndroidScriptHost 自 1 起的 id 空间不重叠。
 */
class IsolatedScriptHost(
    private val remote: IScriptHost,
    private val logSink: (String) -> Unit,
    private val deliverCompletion: (HttpCompletion) -> Unit,
) : ScriptHost {
    private val nextLocalHttpId = AtomicLong(LOCAL_HTTP_ID_BASE)

    override fun log(text: String) = logSink(text)

    override fun http(json: String): Long {
        val callback = object : IScriptHostCallback.Stub() {
            override fun onHttpCompletion(id: Long, rejected: Boolean, payload: String) {
                deliverCompletion(HttpCompletion(id, rejected, payload))
            }
        }
        return try {
            remote.http(json, callback)
        } catch (e: RemoteException) {
            val id = nextLocalHttpId.getAndIncrement()
            deliverCompletion(HttpCompletion(id, true, "script host unavailable"))
            id
        }
    }

    override fun setClipboard(text: String) {
        runCatching { remote.setClipboard(text) }
    }

    override fun insertText(text: String) {
        if (text.isEmpty()) return
        runCatching { remote.insertText(text) }
    }

    override fun openUrl(url: String): Boolean =
        runCatching { remote.openUrl(url) }.getOrDefault(false)

    override fun userNotification(title: String, shortcutName: String, content: String): Boolean =
        runCatching { remote.userNotification(title, shortcutName, content) }.getOrDefault(false)

    override fun onHttpCompletion(completion: HttpCompletion) {
        deliverCompletion(completion)
    }

    companion object {
        /** 隔离侧本地 $http id 起点（主进程侧自 1 起，不重叠）。 */
        const val LOCAL_HTTP_ID_BASE = 2_000_000_000L
    }
}
