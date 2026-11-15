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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** Wi-Fi 传输服务状态（设置页显示局域网地址用）。 */
data class WebServerState(
    val running: Boolean,
    val address: String? = null,
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
 *   http://<ip>:<port>。
 */
class WebServerService : Service() {

    private var server: WebServer? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = WifiTransferAuth.prefs(this)
        if (!WifiTransferAuth.hasPassword(prefs)) {
            ToastUtil.showToast(getString(R.string.web_server_no_password))
            stopSelf()
            return
        }
        val extRoot = getExternalFilesDir(null)
        if (extRoot == null) {
            ToastUtil.showToast(getString(R.string.web_server_no_storage))
            stopSelf()
            return
        }
        val actions = WebServerActionsImpl(applicationContext)
        val store = FileWebServerStore(extRoot) { actions.totalBytes() }
        val router = WebServerRouter(
            store, actions,
            passwordHashProvider = { prefs.getString(WifiTransferAuth.KEY_PASSWORD_HASH, null) },
        )
        val webServer = WebServer(router)
        val port = webServer.start()
        if (port == null) {
            Timber.w("WebServer: no free port in 8080-8090")
            ToastUtil.showToast(getString(R.string.web_server_no_port))
            stopSelf()
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
        _state.value = WebServerState(running = true, address = address)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.close()
        server = null
        _state.value = WebServerState(running = false)
        super.onDestroy()
    }

    companion object {
        private val _state = MutableStateFlow(WebServerState(running = false))
        val state: StateFlow<WebServerState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WebServerService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebServerService::class.java))
        }
    }
}
