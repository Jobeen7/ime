// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.service

import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.EngineDeployState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * :ime Service 侧的 Binder 入口执行体（WO52 3.1b）。
 *
 * 只做入口单飞守门与执行体分派，不重写部署/同步逻辑：
 * 部署与 librime 同步的实际执行在 [RimeMaintenanceOps]（即应用
 * 进程内直调的既有路径）；执行体内的防重入仍由既有守门承担——
 * 部署由 [RimeEngine.deployAndAwait][com.jobeen.ime.engine.RimeEngine.deployAndAwait]
 * 的 deployMutex＋[EngineDeployState]，同步由 rime-main 串行
 * （RimeDispatcher mutex）。本守门只拦 Binder 线程并发进入：
 * 两个 Binder 线程同时调 deploy()/syncUserData() 时，后到者
 * 立即返回 false，不排队、不重入（与对端链 v64.g 同位）。
 */
class RimeMaintenanceExecutor(
    private val ops: RimeMaintenanceOps,
    private val deployTimeoutMs: Long,
) {
    private val claim = AtomicBoolean(false)

    /** 是否有部署/同步正在进行（入口守门或引擎部署标志任一置位）。 */
    fun isBusy(): Boolean = claim.get() || EngineDeployState.isRunning()

    /**
     * 全量部署并等完成。已有操作在进行时立即返回 false；
     * 否则返回部署是否完成（COMPLETED 为 true，其余为 false）。
     */
    suspend fun deploy(): Boolean {
        if (!claim.compareAndSet(false, true)) return false
        try {
            return ops.deploy(deployTimeoutMs) == DeployAwaitResult.COMPLETED
        } finally {
            claim.set(false)
        }
    }

    /** librime 用户数据同步。已有操作在进行时立即返回 false。 */
    suspend fun syncUserData(): Boolean {
        if (!claim.compareAndSet(false, true)) return false
        try {
            return ops.syncUserData()
        } finally {
            claim.set(false)
        }
    }
}
