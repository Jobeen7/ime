// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.jobeen.ime.data.manager.DeployAwaitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * :ime Service 的绑定器（WO52 3.1b）：一次绑定对应一次远端调用，
 * 调用结束即解绑，不长期持有连接。
 */
interface RimeServiceConnector {
    /** 绑定 :ime Service 并返回远端接口；绑定失败/超时返回 null。 */
    suspend fun connect(): IRimeService?

    /** 解绑（未绑定时为空操作）。 */
    fun disconnect()
}

/** [RimeServiceConnector] 的生产实现：经 [Context.bindService] 绑定。 */
class ContextRimeServiceConnector(
    private val appContext: Context,
) : RimeServiceConnector {

    private var connection: ServiceConnection? = null

    override suspend fun connect(): IRimeService? {
        val service = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine<IRimeService?> { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (cont.isActive) cont.resume(IRimeService.Stub.asInterface(binder))
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                connection = conn
                val bound = runCatching {
                    appContext.bindService(
                        Intent(appContext, RimeMaintenanceService::class.java),
                        conn,
                        Context.BIND_AUTO_CREATE,
                    )
                }.getOrDefault(false)
                if (!bound && cont.isActive) {
                    connection = null
                    cont.resume(null)
                }
                cont.invokeOnCancellation { disconnect() }
            }
        }
        if (service == null) disconnect()
        return service
    }

    override fun disconnect() {
        val conn = connection ?: return
        connection = null
        runCatching { appContext.unbindService(conn) }
    }

    companion object {
        /** 绑定等待上限：只限建连，不限部署/同步本身的执行时长。 */
        internal const val BIND_TIMEOUT_MS = 10_000L
    }
}

/**
 * 应用进程侧的部署/同步调用方（WO52 3.1b/3.1c）：部署与 librime
 * 同步一律先走 :ime Service 跨进程链。
 *
 * - 绑定成功：经 Binder 调远端执行；远端调用中抛 [RemoteException]
 *   时直接报失败，**不**回落本进程重跑——远端操作可能已开始，
 *   中途再起一份本进程部署/同步会与之并发（跨进程无互斥）。
 * - 绑定失败/超时（Service 未起）：回落本进程执行体
 *   （[EngineRimeMaintenanceOps]，即原直调路径），行为与改前一致。
 * [lastTransport] 记录上次实际走的通道，供验收/诊断查验。
 */
class RimeMaintenanceClient(
    private val connector: RimeServiceConnector,
    private val localOps: RimeMaintenanceOps,
) {

    enum class Transport {
        REMOTE,
        LOCAL_FALLBACK,
    }

    @Volatile
    var lastTransport: Transport? = null
        private set

    /** 全量部署并等完成（远端执行；绑定失败时回落本进程）。 */
    suspend fun deploy(timeoutMs: Long): Boolean {
        val remote = connectRemote()
        if (remote != null) {
            try {
                val ok = withContext(Dispatchers.IO) { remote.deploy() }
                lastTransport = Transport.REMOTE
                return ok
            } catch (e: RemoteException) {
                Timber.w(e, "rime maintenance remote deploy failed; no local fallback mid-call")
                lastTransport = Transport.REMOTE
                return false
            } finally {
                connector.disconnect()
            }
        }
        lastTransport = Transport.LOCAL_FALLBACK
        return runCatching { localOps.deploy(timeoutMs) }
            .getOrDefault(DeployAwaitResult.FAILED) == DeployAwaitResult.COMPLETED
    }

    /** librime 用户数据同步（远端执行；绑定失败时回落本进程）。 */
    suspend fun syncUserData(): Boolean {
        val remote = connectRemote()
        if (remote != null) {
            try {
                val ok = withContext(Dispatchers.IO) { remote.syncUserData() }
                lastTransport = Transport.REMOTE
                return ok
            } catch (e: RemoteException) {
                Timber.w(e, "rime maintenance remote syncUserData failed; no local fallback mid-call")
                lastTransport = Transport.REMOTE
                return false
            } finally {
                connector.disconnect()
            }
        }
        lastTransport = Transport.LOCAL_FALLBACK
        return runCatching { localOps.syncUserData() }.getOrDefault(false)
    }

    private suspend fun connectRemote(): IRimeService? =
        runCatching { connector.connect() }.getOrNull()
}

/** 生产用构造：应用进程内 [Context] 直取客户端（绑定＋本进程回落）。 */
fun Context.rimeMaintenanceClient(): RimeMaintenanceClient =
    RimeMaintenanceClient(
        ContextRimeServiceConnector(applicationContext),
        EngineRimeMaintenanceOps(),
    )
