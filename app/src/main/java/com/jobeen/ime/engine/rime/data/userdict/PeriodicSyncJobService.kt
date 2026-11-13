// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import android.app.job.JobParameters
import android.app.job.JobService
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.input.ImeInputMethodService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 后台定期同步任务（2026-10-29 批量第 5 项）：
 * 系统按间隔唤醒 → 建独立引擎会话（不扰动输入法与词典页会话）
 * → 逐词典走与手动上传同一口径（先下载合并、再导出上传，
 * 远端较新时绝不静默覆盖）→ 记最后同步时间与成败 → 销毁会话。
 *
 * 未配置 WebDAV / 开关关闭 / 会话超时均记失败、不抛崩溃；
 * Job 被系统打断时返回 true（下次周期重跑）。
 */
class PeriodicSyncJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        // E2（WO39-9）可见性门：开关关→不跑；键盘隐藏→不跑同步、
        // jobFinished(params, true) 要求系统重排；可见→跑。门判定
        // 是纯函数 decideSyncStart（见 companion），本段只做分发，
        // 原同步体原样保留在 RUN 分支之后、不重缩进。
        when (decideSyncStart(PeriodicSyncPrefs.enabled, ImeInputMethodService.isKeyboardVisible)) {
            SyncGateDecision.SKIP_DISABLED -> {
                jobFinished(params, false)
                return false
            }
            SyncGateDecision.RESCHEDULE -> {
                Timber.i("Periodic sync deferred: keyboard not visible")
                jobFinished(params, true)
                return false
            }
            SyncGateDecision.RUN -> Unit
        }
        scope.launch {
            var allOk = false
            try {
                val session = RimeDaemon.createSession(SESSION_NAME)
                try {
                    val ready = withTimeoutOrNull(120_000L) {
                        session.runOnReady { }
                        true
                    }
                    if (ready == true) {
                        val dicts = session.runOnReady { getUserDictList() }
                        val toSync = dictsToSync(dicts)
                        allOk = dicts.isNotEmpty()
                        for (dict in toSync) {
                            val result = WebDavSync.upload(dict, session)
                            if (result.isFailure) {
                                Timber.w("Periodic sync upload failed for $dict")
                                allOk = false
                            }
                        }
                        // 词典表为空时不算成功（未同步任何内容）；
                        // 仅含跳过词典（如 stats）时无上传、无失败即成功。
                        if (dicts.isEmpty()) allOk = false
                    } else {
                        Timber.w("Periodic sync: Rime session timeout")
                    }
                } finally {
                    runCatching { RimeDaemon.destroySession(SESSION_NAME) }
                }
            } catch (e: Exception) {
                Timber.w(e, "Periodic sync failed")
                allOk = false
            }
            PeriodicSyncPrefs.lastSyncTime = System.currentTimeMillis()
            PeriodicSyncPrefs.lastSyncOk = allOk
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.cancel()
        return true
    }

    /**
     * E2（WO39-9）可见性门判定结果：RUN 跑同步；RESCHEDULE 键盘
     * 隐藏、不跑并要求系统重排；SKIP_DISABLED 开关关、不跑不重排。
     */
    internal enum class SyncGateDecision { RUN, RESCHEDULE, SKIP_DISABLED }

    companion object {
        private const val SESSION_NAME = "periodic-sync"

        /**
         * 可见性门判定（纯函数，供单测）：开关关→不跑；键盘隐藏→重排；
         * 可见→跑。开关关优先于可见性（关时无论可见与否都不跑）。
         */
        internal fun decideSyncStart(enabled: Boolean, keyboardVisible: Boolean): SyncGateDecision =
            when {
                !enabled -> SyncGateDecision.SKIP_DISABLED
                !keyboardVisible -> SyncGateDecision.RESCHEDULE
                else -> SyncGateDecision.RUN
            }

        /**
         * 待同步词典表：与 syncAllDicts 同口径，滤掉 stats（输入统计数据库，
         * 导出恒 -1，非用户词库）。纯函数，供单测。
         */
        internal fun dictsToSync(dicts: List<String>): List<String> =
            dicts.filterNot { WebDavSync.isSyncSkippedDict(it) }
    }
}
