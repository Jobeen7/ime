// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.RimeEngine
import kotlinx.coroutines.runBlocking

/**
 * 部署/同步跨进程 Service（WO52 3.1b）：跑在 `:ime` 进程，
 * 对应用进程暴露 [IRimeService] Binder 面。
 *
 * 进程模型（与元书 :ime 进程对位，但不照搬其单进程引擎）：
 * Jime 引擎仍在应用进程，:ime 进程内另起一份引擎实例执行部署/
 * 同步（同一应用数据目录、同一 userDataDir/sharedDataDir），
 * 执行体即应用进程内直调的既有路径（[RimeMaintenanceOps]），
 * 不另写第二套部署/同步逻辑。应用进程部署/同步入口
 * （FabActions）改走 [RimeMaintenanceClient]，绑定失败时
 * 回落本进程执行。
 *
 * 注意（真机待验）：两进程各持一份 librime 实例共用同一用户
 * 目录；部署（start_maintenance）与同步（sync_user_data）
 * 在 :ime 进程执行期间，应用进程引擎的 LevelDB 句柄与内存态
 * 仍在——远端部署成功后调用方经 reload() 收敛本进程引擎；
 * 跨进程的部署↔方案导入/更新互斥未建（UpdateImportGate 是
 * 进程内守门），不要在导入/更新进行中途触发远端部署。
 * 前台服务不需要：绑定期间系统按绑定服务进程保活。
 */
class RimeMaintenanceService : Service() {

    private val executor: RimeMaintenanceExecutor by lazy {
        RimeMaintenanceExecutor(
            ops = EngineRimeMaintenanceOps(),
            deployTimeoutMs = maintenanceDeployTimeout(applicationContext),
        )
    }

    private val binder = object : IRimeService.Stub() {
        // Binder 线程上阻塞直至完成（部署可达数十分钟）；单飞
        // 守门在 executor 内，并发进入者立即返回 false。
        override fun deploy(): Boolean = runBlocking { executor.deploy() }
        override fun syncUserData(): Boolean = runBlocking { executor.syncUserData() }
        override fun isBusy(): Boolean = executor.isBusy()
    }

    override fun onCreate() {
        super.onCreate()
        ensureEngine()
    }

    override fun onBind(intent: Intent): IBinder = binder

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
