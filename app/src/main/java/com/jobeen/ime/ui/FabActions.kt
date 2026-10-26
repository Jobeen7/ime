// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemeEnabled
import com.jobeen.ime.data.manager.awaitSchemasReady
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
 * 部署：走引擎统一部署路径（Action.Reload，进作业队列串行执行），
 * 不直连会话 deploy()（会打断进行中的切换部署编译，见原 MainActivity
 * 注释）。转圈状态直接订阅 [EngineDeployState.running]：reload() 入口
 * 即同步置位，覆盖动作排队等待的窗口，作业 finally 复位；完成判定
 * 仍等引擎方案表就绪（每 2s 轮询，最多 10 分钟），超时按失败处理。
 */
object FabActions {

    /** 同步下载后部署时、等候进行中部署结束的上限：与 deploy() 的等候上限同口径。 */
    private const val DEPLOY_WAIT_TIMEOUT_MS = 600_000L

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
            runCatching { engine.reload() }
            val ready = awaitSchemasReady(
                poll = {
                    runCatching {
                        EngineFactory.current()?.schemasList()?.isNotEmpty() == true
                    }.getOrDefault(false)
                },
                timeoutMs = 600_000L,
                intervalMs = 2000L,
            )
            if (ready) {
                ToastUtil.showToast(context.getString(R.string.fab_deploy_done))
                RimeSyncPrefs.postNotice(context, context.getString(R.string.fab_deploy_done))
            } else {
                Timber.w("FAB deploy timed out waiting for schemas")
                ToastUtil.showToast(context.getString(R.string.fab_deploy_failed))
                RimeSyncPrefs.postNotice(context, context.getString(R.string.fab_deploy_failed))
            }
        }
    }

    /**
     * 同步下载后的顺序部署：与 deploy() 同机制（Action.Reload 进作业
     * 队列串行 + 等方案表就绪），但跑在同步协程内、顺序等完成。
     * 部署进行中时先等它结束再 reload（reload 虽串行排队，但
     * awaitSchemasReady 会被旧部署的方案表误报为就绪）；等候上限与
     * deploy() 同口径。部署失败只记日志 + 下次键盘出现时通知，不
     * 中断后续词典同步（词典与方案相互独立；下载的文件已落盘，
     * 下次部署即生效）。引擎不可用时直接返回，词典同步自会报
     * 引擎未就绪。
     */
    private suspend fun deployDownloadedSchemes(context: Context) {
        val engine = EngineFactory.current()
        if (engine == null) {
            Timber.w("FAB sync: engine not ready, skip deploy after download")
            return
        }
        val waitDeadline = System.currentTimeMillis() + DEPLOY_WAIT_TIMEOUT_MS
        while (EngineDeployState.isRunning() && System.currentTimeMillis() < waitDeadline) {
            delay(1000L)
        }
        if (EngineDeployState.isRunning()) {
            Timber.w("FAB sync: deploy skipped, another deploy still running")
            return
        }
        // 与 deploy() 相同的部署前规范化：旧版写出的裸字符串
        // schema_list 项 librime 读不到，规范化失败也不拦部署。
        runCatching {
            val dir = java.io.File(DataManager.schemeDirsRoot, SchemeDirs.currentDirName(context))
            SchemeEnabled.normalizeCustomFileToMapFormat(java.io.File(dir, "default.custom.yaml"))
        }
        runCatching { engine.reload() }
        val ready = awaitSchemasReady(
            poll = {
                runCatching {
                    EngineFactory.current()?.schemasList()?.isNotEmpty() == true
                }.getOrDefault(false)
            },
            timeoutMs = 600_000L,
            intervalMs = 2000L,
        )
        if (!ready) {
            Timber.w("FAB sync: deploy timed out waiting for schemas")
            RimeSyncPrefs.postNotice(context, context.getString(R.string.fab_deploy_failed))
        }
    }

    /**
     * 一键同步：先上传方案目录、再下载方案目录、最后同步全部词典
     * （三段顺序跑、共用 WebDAV 锁；新模型只增量、不删对端文件，
     * 故无删除传播暂停与 force 重跑）。下载成功后先部署一次（下载的
     * 方案不部署不生效），再同步词典。方案上传或下载失败即整体
     * 失败并短路后续段；词典单本失败只记 failed、报部分成功。
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
                val upResult = WebDavSync.uploadSchemes()
                if (upResult.isFailure) {
                    Timber.w(upResult.exceptionOrNull(), "FAB schemes upload failed")
                    ToastUtil.showToast(context.getString(R.string.fab_sync_failed))
                    RimeSyncPrefs.postNotice(
                        context,
                        context.getString(R.string.fab_sync_failed),
                    )
                    return@launch
                }
                val downResult = WebDavSync.downloadSchemes()
                if (downResult.isFailure) {
                    Timber.w(downResult.exceptionOrNull(), "FAB schemes download failed")
                    ToastUtil.showToast(context.getString(R.string.fab_sync_failed))
                    RimeSyncPrefs.postNotice(
                        context,
                        context.getString(R.string.fab_sync_failed),
                    )
                    return@launch
                }
                // 下载的方案只有部署后才生效：此处顺序部署（与 deploy()
                // 同机制），词典同步不受部署成败影响、照常继续。
                deployDownloadedSchemes(context)
                val up = upResult.getOrThrow()
                val down = downResult.getOrThrow()
                WebDavSync.syncAllDicts(sessionProvider())
                    .onSuccess { failedDicts ->
                        val msg = if (failedDicts.isEmpty()) {
                            context.getString(
                                R.string.fab_dir_sync_success,
                                up,
                                down,
                            )
                        } else {
                            context.getString(
                                R.string.fab_dir_sync_partial,
                                up,
                                down,
                                failedDicts.size,
                            )
                        }
                        ToastUtil.showToast(msg)
                        RimeSyncPrefs.postNotice(context, msg)
                    }.onFailure {
                        Timber.w(it, "FAB dict sync failed")
                        ToastUtil.showToast(context.getString(R.string.fab_sync_failed))
                        RimeSyncPrefs.postNotice(
                            context,
                            context.getString(R.string.fab_sync_failed),
                        )
                    }
            } catch (e: Exception) {
                Timber.w(e, "FAB dir sync failed")
                ToastUtil.showToast(context.getString(R.string.fab_sync_engine_not_ready))
            } finally {
                FabSyncState.setRunning(false)
            }
        }
    }
}
