// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.EngineDeployState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

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
 * 原子单飞守门（U-S1）：同步抢占，抢到才算持有；释放幂等。
 * 部署的运行标志只在部署作业内部才置位，先查 [EngineDeployState.isRunning]
 * 再 launch 之间有窗口，双击／菜单＋FAB 可并发跑两次部署——本守门在
 * 查完后、launch 前同步抢占，覆盖该窗口；作业完成／取消／未启动时
 * 一律经 [Job.invokeOnCompletion] 释放，不依赖协程体跑到 finally。
 */
internal class SingleFlightClaim {
    private val claimed = AtomicBoolean(false)

    /** 抢占成功返回 true；已有持有者返回 false。 */
    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)

    fun release() {
        claimed.set(false)
    }
}

/**
 * 两处 Activity（设置 home 与 RIME 页）共用的 FAB 部署 / 同步动作。
 * 只接收 [CoroutineScope] + [Context] 与轻量回调，不依赖具体 Activity
 * 类；结果只在设置页 Toast 一次，不再向键盘投递待发通知——双通道
 * （Toast＋待发通知）会让同一结果在设置页与键盘各弹一次，且待发
 * 通知是单槽、后一条覆盖前一条（U-S2）；同步未配置时回调给调用方处理导航。
 *
 * 部署：走引擎统一部署路径（deployAndAwait，进作业队列串行执行），
 * 不直连会话 deploy()（会打断进行中的切换部署编译，见原 MainActivity
 * 注释）。转圈状态直接订阅 [EngineDeployState.running]：部署入口
 * 即同步置位，作业 finally 复位；完成判定以部署作业的 Finish/失败
 * 信号为准（deployAndAwaitIfChanged 的 COMPLETED，含输入未变跳过），方案表非空只作辅助条件：
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
     * deployAndAwaitIfChanged 的 COMPLETED = 部署作业走完 deploy()+join
     * 并发出 Finish，或输入未变时跳过部署直接发出 Finish（见
     * deployAndAwaitIfChanged）；FAILED = 入口被挡（忙/孤儿/交叉闸/
     * 作用域已死）、作业被丢弃或编译失败；TIMEOUT_RUNNING = 等候超时、
     * 作业仍在后台跑（记孤儿，下次部署会被忙门挡下）。三者中只有
     * COMPLETED 能走向成功，且成功还须方案表非空（COMPLETED 后按旧口径
     * 2s 间隔轮询复核，上限 SCHEMAS_READY_TIMEOUT_MS；表恒空说明部署
     * 产出无效，仍算失败）。部署超时用 deployTimeoutForCurrentDir（与
     * 切换路径同算法）。本函数不捕获 CancellationException（调用方取消
     * 时穿透；sync() 外层 catch 同样重抛取消，见 U-S3）。
     */
    private suspend fun deployAndCheckSchemas(context: Context, engine: IEngine?): Boolean {
        val result = (engine as? RimeEngine)
            ?.deployAndAwaitIfChanged(deployTimeoutForCurrentDir(context))
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

    /** 部署单飞守门：补 [EngineDeployState.isRunning] 先查后做的窗口。 */
    private val deployClaim = SingleFlightClaim()

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
        // U-S1：先查后做的窗口——上两行查完到作业内标志置位之间，
        // 第二次调用仍能看到未运行而 launch 并发部署；此处同步抢占，
        // 未抢到即报忙返回（FAB／菜单／RIME 页三路同走本函数，共用同一守门）。
        if (!deployClaim.tryClaim()) {
            ToastUtil.showToast(context.getString(R.string.fab_deploy_busy))
            return
        }
        // 部署先行规范化当前目录 custom 文件：旧版写出的裸字符串
        // schema_list 项 librime 读不到，本次部署会编不出词库；
        // 规范化失败也不拦部署（部署本身仍按旧文件走）。
        runCatching {
            val dir = java.io.File(DataManager.schemeDirsRoot, SchemeDirs.currentDirName(context))
            SchemeEnabled.normalizeCustomFileToMapFormat(java.io.File(dir, "default.custom.yaml"))
        }
        val job = try {
            scope.launch {
                val deployed = deployAndCheckSchemas(context, engine)
                if (deployed) {
                    ToastUtil.showToast(context.getString(R.string.fab_deploy_done))
                } else {
                    ToastUtil.showToast(context.getString(R.string.fab_deploy_failed))
                }
            }
        } catch (e: Exception) {
            deployClaim.release()
            throw e
        }
        // 完成／取消／作用域已死致协程体未启动时一律释放（释放幂等）。
        job.invokeOnCompletion { deployClaim.release() }
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
                    }.onFailure {
                        Timber.d("FAB sync: stage dict done: failed")
                        Timber.w(it, "FAB dict sync failed")
                        ToastUtil.showToast(context.getString(R.string.fab_sync_failed))
                    }
            } catch (e: CancellationException) {
                // U-S3：取消不是失败——原样重抛，不报“引擎未就绪”，
                // 不向键盘投递错误的待发通知；finally 仍复位运行态。
                throw e
            } catch (e: Exception) {
                Timber.w(e, "FAB dict sync failed")
                ToastUtil.showToast(context.getString(R.string.fab_sync_engine_not_ready))
            } finally {
                FabSyncState.setRunning(false)
            }
        }
    }
}
