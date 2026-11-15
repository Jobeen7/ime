package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.base.webserver.WebServerService
import com.jobeen.ime.base.webserver.WifiTransferAuth
import com.jobeen.ime.ui.screen.WebServerScreen

/** Wi-Fi 传输设置页（WO52 4.4）：密码、启停、局域网地址。 */
class WebServerActivity : ImeActivity() {

    private var hasPassword by mutableStateOf(false)

    override fun onCreateBeforeContent(savedInstanceState: android.os.Bundle?) {
        hasPassword = WifiTransferAuth.hasPassword(WifiTransferAuth.prefs(this))
    }

    @Composable
    override fun Content() {
        WebServerScreen(
            onBack = { finish() },
            hasPassword = hasPassword,
            onSavePassword = { password ->
                WifiTransferAuth.setPassword(WifiTransferAuth.prefs(this), password)
                hasPassword = true
                ToastUtil.showToast(getString(R.string.web_server_password_saved))
            },
            onStart = { WebServerService.start(this) },
            onStop = { WebServerService.stop(this) },
            onRegenerate = { WebServerService.regenerateCertificate(this) },
        )
    }
}
