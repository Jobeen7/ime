// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.service

import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.engine.rime.data.CrossProcessUserDataLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 部署／同步执行结果三态（B-X-2）：忙与失败必须分开——并发进入
 * 是 Busy（调用方可提示“稍后再试”），执行失败是 Failed；
 * Boolean 无法区分两者（旧口径一律 false）。
 */
sealed interface MaintenanceResult {
    /** 已有部署／同步在进行（或客户端等待超时，服务端仍在跑）。 */
    data object Busy : MaintenanceResult

    /** 执行失败。 */
    data object Failed : MaintenanceResult

    /** 执行成功。 */
    data object Success : MaintenanceResult
}

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
 * 立即返回 [MaintenanceResult.Busy]，不排队、不重入。
 *
 * 跨进程互斥（B-X-1）：单飞只拦本实例；两进程间的部署↔导入/
 * 更新互斥由 [CrossProcessUserDataLock]（userDataDir 下的
 * `.maintenance.lock`）承担——执行前先取文件锁，锁被占报 Busy、
 * 取锁异常报 Failed；锁在手时执行体再失败即真失败。同步的
 * LevelDB 锁失败在 native 侧只回 Boolean、无原因码，故忙／失败
 * 的区分点就落在文件锁上：合作进程的争用在取锁时即报 Busy，
 * 持锁后执行体返回 false 按 Failed 计。
 */

class RimeMaintenanceExecutor(
    private val ops: RimeMaintenanceOps,
    private val deployTimeoutMs: Long,
    /** 跨进程文件锁目录（userDataDir）：null 时不取文件锁（旧行为，供单测）。 */
    private val lockDir: File? = null,
) {
    private val claim = AtomicBoolean(false)

    /** 取跨进程文件锁后执行；锁被占报 Busy、取锁异常报 Failed，不进执行体。 */
    private suspend fun withUserDataLock(block: suspend () -> MaintenanceResult): MaintenanceResult {
        if (lockDir == null) return block()
        return when (CrossProcessUserDataLock.tryAcquire(lockDir)) {
            CrossProcessUserDataLock.AcquireResult.BUSY -> MaintenanceResult.Busy
            CrossProcessUserDataLock.AcquireResult.FAILED -> MaintenanceResult.Failed
            CrossProcessUserDataLock.AcquireResult.ACQUIRED -> try {
                block()
            } finally {
                CrossProcessUserDataLock.release()
            }
        }
    }

    /** 是否有部署/同步正在进行（入口守门或引擎部署标志任一置位）。 */
    fun isBusy(): Boolean = claim.get() || EngineDeployState.isRunning()

    /**
     * 全量部署并等完成。已有操作在进行时立即返回 [MaintenanceResult.Busy]；
     * 否则返回部署是否完成（COMPLETED 为 [MaintenanceResult.Success]，其余为
     * [MaintenanceResult.Failed]）。
     */
    suspend fun deploy(): MaintenanceResult {
        if (!claim.compareAndSet(false, true)) return MaintenanceResult.Busy
        try {
            return withUserDataLock {
                if (ops.deploy(deployTimeoutMs) == DeployAwaitResult.COMPLETED) {
                    MaintenanceResult.Success
                } else {
                    MaintenanceResult.Failed
                }
            }
        } finally {
            claim.set(false)
        }
    }

    /** librime 用户数据同步。已有操作在进行时立即返回 [MaintenanceResult.Busy]。 */
    suspend fun syncUserData(): MaintenanceResult {
        if (!claim.compareAndSet(false, true)) return MaintenanceResult.Busy
        try {
            return withUserDataLock {
                if (ops.syncUserData()) MaintenanceResult.Success else MaintenanceResult.Failed
            }
        } finally {
            claim.set(false)
        }
    }
}
