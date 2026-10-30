// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import android.app.job.JobParameters
import android.app.job.JobService
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
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
        if (!PeriodicSyncPrefs.enabled) {
            jobFinished(params, false)
            return false
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
                        allOk = dicts.isNotEmpty()
                        for (dict in dicts) {
                            val result = WebDavSync.upload(dict, session)
                            if (result.isFailure) {
                                Timber.w("Periodic sync upload failed for $dict")
                                allOk = false
                            }
                        }
                        // 词典表为空时不算成功（未同步任何内容）
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

    companion object {
        private const val SESSION_NAME = "periodic-sync"
    }
}
