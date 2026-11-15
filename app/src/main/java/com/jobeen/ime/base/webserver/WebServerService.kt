package com.jobeen.ime.base.webserver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jobeen.ime.R
import com.jobeen.ime.base.notify.NotificationChannels
import com.jobeen.ime.base.util.ToastUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** Wi-Fi 传输服务状态（设置页显示局域网地址与证书指纹用）。 */
data class WebServerState(
    val running: Boolean,
    val address: String? = null,
    val fingerprint: String? = null,
    val certificateRotated: Boolean = false,
)

/**
 * Wi-Fi 传输前台服务（WO52 4.4）。
 *
 * - 前台 dataSync，通知 id 1003，渠道 web_server
 *   （IMPORTANCE_LOW），START_STICKY，不可绑定。
 * - 端口段 8080–8090 依次尝试；全被占则提示并停服。
 * - 未设密码时拒绝启动并提示设密码（鉴权是本项安全底线，
 *   不照搬对端无鉴权做法）。
 * - 设置页（WebServerActivity）显示局域网地址
 *   https://<ip>:<port> 与证书 SHA-256 指纹（WO54）。
 * - 启动时先备好证书再起服务；证书生成／加载失败即停服，
 *   不降级 http（WO54）。
 */
class WebServerService : Service() {

    private var server: WebServer? = null
    private var startedAtMs = 0L
    @Volatile
    private var lastRequestMs = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = WifiTransferAuth.prefs(this)
        if (!WifiTransferAuth.hasPassword(prefs)) {
            ToastUtil.showToast(getString(R.string.web_server_no_password))
            failAndStop(R.string.web_server_no_password)
            return
        }
        val extRoot = getExternalFilesDir(null)
        if (extRoot == null) {
            ToastUtil.showToast(getString(R.string.web_server_no_storage))
            failAndStop(R.string.web_server_no_storage)
            return
        }
        val actions = WebServerActionsImpl(applicationContext)
        val store = FileWebServerStore(extRoot) { actions.totalBytes() }
        val router = WebServerRouter(
            store, actions,
            passwordHashProvider = { prefs.getString(WifiTransferAuth.KEY_PASSWORD_HASH, null) },
            revokedBeforeMsProvider = { WifiTransferAuth.revokedBeforeMs(prefs) },
        )
        // WO54：先备好证书再起服务；任一步失败即停服，不降级 http。
        val cert = try {
            WifiTransferCertManager.ensureCertificate()
        } catch (e: Exception) {
            Timber.w(e, "WebServer: certificate setup failed")
            ToastUtil.showToast(getString(R.string.web_server_cert_failed))
            failAndStop(R.string.web_server_cert_failed)
            return
        }
        val sslContext = try {
            WifiTransferCertManager.sslContext()
        } catch (e: Exception) {
            Timber.w(e, "WebServer: SSLContext setup failed")
            ToastUtil.showToast(getString(R.string.web_server_cert_failed))
            failAndStop(R.string.web_server_cert_failed)
            return
        }
        val fingerprint = WifiTransferCertManager.fingerprint(cert)
        val certificateRotated = WifiTransferCertManager.checkRotation(prefs, fingerprint)
        val webServer = WebServer(router, sslContext, onRequest = { lastRequestMs = System.currentTimeMillis() })
        val port = webServer.start()
        if (port == null) {
            Timber.w("WebServer: no free port in 8080-8090")
            ToastUtil.showToast(getString(R.string.web_server_no_port))
            failAndStop(R.string.web_server_no_port)
            return
        }
        server = webServer
        val address = WebServer.lanAddress(port)
        startForeground(
            NotificationChannels.WEB_SERVER_NOTIFICATION_ID,
            NotificationCompat.Builder(this, NotificationChannels.WEB_SERVER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_web_server_title))
                .setContentText(getString(R.string.notif_web_server_text, address))
                .setOngoing(true)
                .build(),
        )
        startedAtMs = System.currentTimeMillis()
        lastRequestMs = startedAtMs
        idleJob = serviceScope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                if (shouldAutoStop(System.currentTimeMillis(), startedAtMs, lastRequestMs)) {
                    notifyAutoStopped()
                    stopSelf()
                    break
                }
            }
        }
        _state.value = WebServerState(
            running = true,
            address = address,
            fingerprint = fingerprint,
            certificateRotated = certificateRotated,
        )
    }

    /**
     * C-L-08：早退分支先 startForeground 再 stopSelf——以前台方式
     * 启动的服务在 stopSelf 前未调 startForeground，系统会报
     * ForegroundServiceDidNotStartInTime。通知文案即失败原因，
     * 与成功启动的通知同 id／同渠道。
     */
    private fun failAndStop(messageRes: Int) {
        startForeground(
            NotificationChannels.WEB_SERVER_NOTIFICATION_ID,
            NotificationCompat.Builder(this, NotificationChannels.WEB_SERVER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_web_server_title))
                .setContentText(getString(messageRes))
                .build(),
        )
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        idleJob?.cancel()
        serviceScope.cancel()
        server?.close()
        server = null
        // C-L-09：停服即吊销此前签发的所有 token——重开后须重新登录，
        // 旧 token（即使未过期）不再可用。
        WifiTransferAuth.revokeTokensIssuedBefore(WifiTransferAuth.prefs(this))
        _state.value = WebServerState(running = false)
        super.onDestroy()
    }

    /** 自停前发一次完成通知（走 web_server 渠道；通知权限被拒时系统直接丢弃，不崩）。 */
    private fun notifyAutoStopped() {
        val notification = NotificationCompat.Builder(this, NotificationChannels.WEB_SERVER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_web_server_title))
            .setContentText(getString(R.string.notif_web_server_auto_stopped))
            .build()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NotificationChannels.WEB_SERVER_NOTIFICATION_ID + 1, notification)
        }
    }

    companion object {
        private val _state = MutableStateFlow(WebServerState(running = false))
        val state: StateFlow<WebServerState> = _state.asStateFlow()

        /** 空闲自停：30 分钟无请求（B-W-6）。 */
        const val IDLE_STOP_MS = 30L * 60L * 1000L
        /** 绝对自停：5 小时（留 1 小时余量，避开 Android 15 的 6 小时 dataSync 上限）。 */
        const val ABSOLUTE_STOP_MS = 5L * 60L * 60L * 1000L
        private const val IDLE_CHECK_INTERVAL_MS = 60_000L

        /** 自停决策（纯函数，单测时间注入）：空闲超限或绝对超限即停。 */
        fun shouldAutoStop(nowMs: Long, startedAtMs: Long, lastRequestMs: Long): Boolean =
            nowMs - lastRequestMs >= IDLE_STOP_MS || nowMs - startedAtMs >= ABSOLUTE_STOP_MS

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WebServerService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebServerService::class.java))
        }

        /** 手动重新生成证书（WO54 W54-5）：删条目 → 停服 → 重启服务（新证书在下次启动时生成）。 */
        fun regenerateCertificate(context: Context) {
            runCatching { WifiTransferCertManager.deleteCertificate() }
            stop(context)
            start(context)
        }
    }
}
