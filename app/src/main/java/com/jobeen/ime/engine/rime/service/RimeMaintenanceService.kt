// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.RimeEngine
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.data.CrossProcessUserDataLock
import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.runBlocking

/**
 * 部署/同步跨进程 Service（WO52 3.1b）：跑在 `:ime` 进程，
 * 对应用进程暴露 [IRimeService] Binder 面。
 *
 * 进程模型（与对端 :ime 进程对位，但不照搬其单进程引擎）：
 * Jime 引擎仍在应用进程，:ime 进程内另起一份引擎实例执行部署/
 * 同步（同一应用数据目录、同一 userDataDir/sharedDataDir），
 * 执行体即应用进程内直调的既有路径（[RimeMaintenanceOps]），
 * 不另写第二套部署/同步逻辑。应用进程部署/同步入口
 * （FabActions）改走 [RimeMaintenanceClient]，绑定失败时
 * 回落本进程执行。
 *
 * 注意：两进程各持一份 librime 实例共用同一用户目录；部署
 * （start_maintenance）与同步（sync_user_data）在 :ime 进程
 * 执行期间，应用进程引擎的 LevelDB 句柄与内存态仍在——远端
 * 部署成功后调用方经 reload() 收敛本进程引擎。跨进程互斥由
 * [CrossProcessUserDataLock]（userDataDir 下的 `.maintenance.lock`）
 * 承担：部署／同步（本服务）与方案导入／更新（应用进程，
 * UpdateImportGate 持有处）进事务前先取锁，锁被占报 Busy；
 * 远端部署后本进程引擎收敛、同步假成功是否消除仍真机待验。
 * 前台服务不需要：绑定期间系统按绑定服务进程保活。
 */
class RimeMaintenanceService : Service() {

    private val executor: RimeMaintenanceExecutor by lazy {
        RimeMaintenanceExecutor(
            ops = EngineRimeMaintenanceOps(),
            deployTimeoutMs = maintenanceDeployTimeout(applicationContext),
            lockDir = DataManager.userDataDir,
        )
    }

    private val binder = object : IRimeService.Stub() {
        // Binder 线程上阻塞直至完成（部署可达数十分钟）；单飞
        // 守门在 executor 内，并发进入者立即返回 Busy（AIDL 只传
        // Boolean：忙与失败在远端都落为 false，客户端先查 isBusy()
        // 区分，见 RimeMaintenanceClient）。
        override fun deploy(): Boolean =
            runBlocking { executor.deploy() == MaintenanceResult.Success }
        override fun syncUserData(): Boolean =
            runBlocking { executor.syncUserData() == MaintenanceResult.Success }
        override fun isBusy(): Boolean = executor.isBusy()
    }

    override fun onCreate() {
        super.onCreate()
        ensureEngine()
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * 服务销毁时销维护会话（B-X-1）：同步用的 `rime-maintenance`
     * 会话只在同步时建、从不销，服务死后残留会话会拖住 Rime 实例
     * 不 finalize；此处补 [RimeDaemon.destroySession]，会话已销
     * 时为空操作。引擎自身的输入会话不受影响（名不同；会话表空
     * 时才 finalize，而输入会话仍在表内）。
     */
    override fun onDestroy() {
        runCatching {
            RimeDaemon.destroySession(EngineRimeMaintenanceOps.MAINTENANCE_SESSION_NAME)
        }
        super.onDestroy()
    }

    /**
     * :ime 进程内建引擎：Application.onCreate 只在主进程跑
     * AppStartup（ImeApplication.isMainProcess 守门），:ime
     * 进程的引擎须此处自建。已建则复用，不重复 initialize。
     * 资源解压（resource.zip → shared/）由主进程负责；绑定
     * 意味着主进程已活，资源目录已就绪。
     */
    private fun ensureEngine() {
        if (EngineFactory.current() != null) return
        val engine = EngineFactory.switchTo(applicationContext, RimeEngine::class)
        engine.initialize(applicationContext)
    }
}
