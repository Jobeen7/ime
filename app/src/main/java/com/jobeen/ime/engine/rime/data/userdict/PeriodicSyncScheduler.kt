// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * 后台定期同步调度（2026-10-29 批量第 5 项）：
 * 用框架 JobScheduler（不引入新依赖）：周期任务＋联网约束＋
 * 跨重启持久化；无需精确闹钟权限与前台服务。
 *
 * 强制停止后系统不再唤醒持久任务（标准行为），用户下次打开
 * 应用时由设置页开关状态重新调度（见 UserDictActivity）。
 */
object PeriodicSyncScheduler {
    const val JOB_ID = 0x5EED

    fun schedule(context: Context) {
        val intervalMs = PeriodicSyncPrefs.intervalHours * 3600_000L
        val component = ComponentName(context, PeriodicSyncJobService::class.java)
        val info = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(intervalMs)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.schedule(info)
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(JOB_ID)
    }

    fun isScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        return scheduler.allPendingJobs.any { it.id == JOB_ID }
    }
}
