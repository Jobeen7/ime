// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.awaitSchemasReady
import com.jobeen.ime.engine.EngineFactory
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
 * 部署：走引擎统一部署路径（Action.Reload，进作业队列串行执行），
 * 不直连会话 deploy()（会打断进行中的切换部署编译，见原 MainActivity
 * 注释）。转圈状态直接订阅 [EngineDeployState.running]：reload() 入口
 * 即同步置位，覆盖动作排队等待的窗口，作业 finally 复位；完成判定
 * 仍等引擎方案表就绪（每 2s 轮询，最多 10 分钟），超时按失败处理。
 */
object FabActions {

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
     * 一键同步：目录同步（覆盖原词库合并定义之外的整目录同步）。
     * 成功且删除传播因骤减暂停时，把暂停原因持久化到
     * [RimeSyncPrefs]（目录名 + 原因），并在提示中带上“本地文件骤减，
     * 已暂停跟删”；成功且未暂停时，若暂停标志属于当前目录则清除
     * （骤减条件已不存在，标志不再有效）。force 重跑（设置页“确认
     * 清理”入口）由调用方先清除标志再以 forceDeletePropagation=true
     * 调用，结果同样经本函数 Toast + 待显示通知。
     */
    fun sync(
        context: Context,
        scope: CoroutineScope,
        sessionProvider: () -> RimeSession,
        onNotConfigured: () -> Unit,
        forceDeletePropagation: Boolean = false,
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
                val result = WebDavSync.syncSchemeDir(
                    sessionProvider(),
                    forceDeletePropagation,
                )
                result.onSuccess { r ->
                    var msg = if (r.failedFiles.isEmpty()) {
                        context.getString(
                            R.string.fab_dir_sync_success,
                            r.uploaded,
                            r.downloaded,
                        )
                    } else {
                        context.getString(
                            R.string.fab_dir_sync_partial,
                            r.uploaded,
                            r.downloaded,
                            r.failedFiles.size,
                        )
                    }
                    val dir = SchemeDirs.currentDirName(context)
                    if (r.deletePropagationPaused) {
                        RimeSyncPrefs.setDeletePropagationPaused(
                            context,
                            dir,
                            r.deletePropagationPauseReason,
                        )
                        msg += context.getString(R.string.fab_dir_sync_paused_note)
                    } else if (RimeSyncPrefs.deletePropagationPause(context)?.dir == dir) {
                        RimeSyncPrefs.clearDeletePropagationPaused(context)
                    }
                    ToastUtil.showToast(msg)
                    RimeSyncPrefs.postNotice(context, msg)
                }.onFailure {
                    Timber.w(it, "FAB dir sync failed")
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
