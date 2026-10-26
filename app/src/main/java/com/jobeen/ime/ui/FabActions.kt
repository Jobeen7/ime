// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.RimeSyncPrefs
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
     * 一键同步：先上传方案目录、再下载方案目录、最后同步全部词典
     * （三段顺序跑、共用 WebDAV 锁；新模型只增量、不删对端文件，
     * 故无删除传播暂停与 force 重跑）。方案上传或下载失败即整体
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
