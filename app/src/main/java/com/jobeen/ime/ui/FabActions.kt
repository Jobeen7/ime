// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemeEnabled
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.awaitSchemasReady
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.IEngine
import com.jobeen.ime.engine.RimeEngine
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 同步运行状态（进程级，唯一）：与部署标志 [EngineDeployState.running]
 * 并列。两处 Activity 的 FAB / 同步行一律订阅它显示转圈，不再各持
 * 本地布尔；一处同步进行中，另一处同样显示运行中、点击被守门挡下。
 */
object FabSyncState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun isRunning(): Boolean = _running.value

    internal fun setRunning(running: Boolean) {
        _running.value = running
    }
}

/**
 * 两处 Activity（设置 home 与 RIME 页）共用的 FAB 部署 / 同步动作。
 * 只接收 [CoroutineScope] + [Context] 与轻量回调，不依赖具体 Activity
 * 类；结果 surfacing 与原两处一致：Toast + [RimeSyncPrefs.postNotice]
 *（键盘下次出现时显示），同步未配置时回调给调用方处理导航。
 *
 * 部署：走引擎统一部署路径（deployAndAwait，进作业队列串行执行），
 * 不直连会话 deploy()（会打断进行中的切换部署编译，见原 MainActivity
 * 注释）。转圈状态直接订阅 [EngineDeployState.running]：部署入口
 * 即同步置位，作业 finally 复位；完成判定以部署作业的 Finish/失败
 * 信号为准（deployAndAwait 的 COMPLETED），方案表非空只作辅助条件：
 * COMPLETED 后仍须方案表非空才报成功，任一不满足都按失败处理。
 * 丢弃（忙/交叉闸/作用域已死）、编译失败、超时在跑一律不是成功。
 */
object FabActions {

    /**
     * 部署完成后等方案表刷新的上限：表刷新只需几十秒，与部署超时
     * 解耦（以前与部署共用 600 秒，部署完成后的等待被不必要拉长）。
     */
    private const val SCHEMAS_READY_TIMEOUT_MS = 60_000L

    /**
     * 当前目录部署超时：与切换路径同一套算法（见
     * SchemePackageImport.deployTimeoutMs：180 秒起、按目录大小算、
     * 上限 1800 秒）。切换路径是自己加总再调 deployTimeoutMs，口径与
     * deployTimeoutForDir 相同；FAB 以前是固定 600 秒，大目录会误报
     * 失败、小目录则比切换路径更宽松，两处现已统一。
     */
    internal fun deployTimeoutForCurrentDir(context: Context): Long {
        val dir = java.io.File(DataManager.schemeDirsRoot, SchemeDirs.currentDirName(context))
        return SchemePackageImport.deployTimeoutForDir(dir)
    }

    /**
     * 一次部署并判定成败：以部署作业的 Finish/失败信号为准，方案表
     * 非空只作辅助条件、不能单独定成功。
     * deployAndAwait 的 COMPLETED = 部署作业走完 deploy()+join 并发出
     * Finish；FAILED = 入口被挡（忙/孤儿/交叉闸/作用域已死）、作业被丢弃
     * 或编译失败；TIMEOUT_RUNNING = 等候超时、作业仍在后台跑（记孤儿，
     * 下次部署会被忙门挡下）。三者中只有 COMPLETED 能走向成功，且
     * 成功还须方案表非空（COMPLETED 后按旧口径 2s 间隔轮询复核，
     * 上限 SCHEMAS_READY_TIMEOUT_MS；表恒空说明部署产出无效，仍算
     * 失败）。部署超时用 deployTimeoutForCurrentDir（与切换路径同
     * 算法）。本函数不捕获 CancellationException（调用方取消时穿透；
     * sync() 外层 catch 的既有捕获行为不在此改）。
     */
    private suspend fun deployAndCheckSchemas(context: Context, engine: IEngine?): Boolean {
        val result = (engine as? RimeEngine)
            ?.deployAndAwait(deployTimeoutForCurrentDir(context))
            ?: DeployAwaitResult.FAILED
        if (result != DeployAwaitResult.COMPLETED) {
            Timber.w("FAB deploy not completed: $result")
            return false
        }
        val ready = awaitSchemasReady(
            poll = {
                runCatching {
                    EngineFactory.current()?.schemasList()?.isNotEmpty() == true
                }.getOrDefault(false)
            },
            timeoutMs = SCHEMAS_READY_TIMEOUT_MS,
            intervalMs = 2000L,
        )
        if (!ready) {
            Timber.w("FAB deploy completed but schemas still empty")
        }
        return ready
    }

    fun deploy(context: Context, scope: CoroutineScope) {
        if (EngineDeployState.isRunning()) {
            ToastUtil.showToast(context.getString(R.string.fab_deploy_busy))
            return
        }
        val engine = EngineFactory.current()
        if (engine == null) {
            ToastUtil.showToast(context.getString(R.string.fab_deploy_failed))
            return
        }
        // 部署先行规范化当前目录 custom 文件：旧版写出的裸字符串
        // schema_list 项 librime 读不到，本次部署会编不出词库；
        // 规范化失败也不拦部署（部署本身仍按旧文件走）。
        runCatching {
            val dir = java.io.File(DataManager.schemeDirsRoot, SchemeDirs.currentDirName(context))
            SchemeEnabled.normalizeCustomFileToMapFormat(java.io.File(dir, "default.custom.yaml"))
        }
        scope.launch {
            val deployed = deployAndCheckSchemas(context, engine)
            if (deployed) {
                ToastUtil.showToast(context.getString(R.string.fab_deploy_done))
                RimeSyncPrefs.postNotice(context, context.getString(R.string.fab_deploy_done))
            } else {
                ToastUtil.showToast(context.getString(R.string.fab_deploy_failed))
                RimeSyncPrefs.postNotice(context, context.getString(R.string.fab_deploy_failed))
            }
        }
    }

    /**
     * 一键同步：只同步用户词（词典段）。方案文件（yaml、lua、词库
     * dict.yaml）不参与同步——方案词库随方案更新走；手动上传/下载
     * 方案目录仍在用户词典页。部署段随方案下载一起拿掉（无可部署
     * 内容）；手动部署仍走 FAB 部署按钮（与菜单重启引擎同机制）。
     * 词典单本失败只记 failed、报部分成功；取词典列表失败则整体失败。
     */
    fun sync(
        context: Context,
        scope: CoroutineScope,
        sessionProvider: () -> RimeSession,
        onNotConfigured: () -> Unit,
    ) {
        if (FabSyncState.isRunning()) return
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast(context.getString(R.string.fab_sync_not_configured))
            onNotConfigured()
            return
        }
        FabSyncState.setRunning(true)
        scope.launch {
            try {
                Timber.d("FAB sync: stage dict start")
                WebDavSync.syncAllDicts(sessionProvider())
                    .onSuccess { failedDicts ->
                        Timber.d("FAB sync: stage dict done: failed=${failedDicts.size}")
                        val msg = if (failedDicts.isNotEmpty()) {
                            context.getString(
                                R.string.fab_dict_sync_partial,
                                failedDicts.size,
                            )
                        } else {
                            context.getString(R.string.fab_dict_sync_success)
                        }
                        ToastUtil.showToast(msg)
                        RimeSyncPrefs.postNotice(context, msg)
                    }.onFailure {
                        Timber.d("FAB sync: stage dict done: failed")
                        Timber.w(it, "FAB dict sync failed")
                        ToastUtil.showToast(context.getString(R.string.fab_sync_failed))
                        RimeSyncPrefs.postNotice(
                            context,
                            context.getString(R.string.fab_sync_failed),
                        )
                    }
            } catch (e: Exception) {
                Timber.w(e, "FAB dict sync failed")
                ToastUtil.showToast(context.getString(R.string.fab_sync_engine_not_ready))
                RimeSyncPrefs.postNotice(
                    context,
                    context.getString(R.string.fab_sync_engine_not_ready),
                )
            } finally {
                FabSyncState.setRunning(false)
            }
        }
    }
}
