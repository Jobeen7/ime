// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.service

import android.content.Context
import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.RimeEngine
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File

/**
 * :ime Service 内的部署/同步执行体（WO52 3.1b/3.1c）。
 *
 * 应用进程内直调路径在此复用为 Service 内执行体，不另写第二套
 * 部署/同步逻辑：部署走既有 [RimeEngine.deployAndAwaitIfChanged]，
 * librime 同步走既有 JNI 导出 [RimeApi.syncUserData][com.jobeen.ime.engine.rime.core.RimeApi.syncUserData]。
 * 与 WebDAV 文本快照（WebDavSync）并存、互不替代。
 */
interface RimeMaintenanceOps {
    /** 全量部署（输入未变时可跳过重编）；返回部署等待结果。 */
    suspend fun deploy(timeoutMs: Long): DeployAwaitResult

    /** librime 用户数据同步（sync_user_data）。 */
    suspend fun syncUserData(): Boolean
}

/**
 * [RimeMaintenanceOps] 的引擎实现：在调用方所在进程取当前引擎执行。
 * Service 进程内由 [RimeMaintenanceService] 先行建好引擎；应用进程
 * 内回落路径用主进程已建好的引擎。
 */
class EngineRimeMaintenanceOps : RimeMaintenanceOps {

    override suspend fun deploy(timeoutMs: Long): DeployAwaitResult {
        val engine = EngineFactory.currentAs<RimeEngine>()
            ?: return DeployAwaitResult.FAILED
        return engine.deployAndAwaitIfChanged(timeoutMs)
    }

    override suspend fun syncUserData(): Boolean {
        return try {
            val session = RimeDaemon.createSession(MAINTENANCE_SESSION_NAME)
            session.runOnReady { syncUserData() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "rime maintenance syncUserData failed")
            false
        }
    }

    companion object {
        /** 维护会话名：与输入会话、FAB 会话分开，不扰动输入会话。 */
        internal const val MAINTENANCE_SESSION_NAME = "rime-maintenance"
    }
}

/**
 * 当前目录部署超时（与切换路径同一套算法：180 秒起、按目录大小算、
 * 上限 1800 秒）。与 [com.jobeen.ime.ui.FabActions.deployTimeoutForCurrentDir]
 * 同算法——ui 层不可被 engine 层依赖，故此处复述，不跨层引用。
 */
internal fun maintenanceDeployTimeout(context: Context): Long {
    val dir = File(DataManager.schemeDirsRoot, SchemeDirs.currentDirName(context))
    return SchemePackageImport.deployTimeoutForDir(dir)
}
