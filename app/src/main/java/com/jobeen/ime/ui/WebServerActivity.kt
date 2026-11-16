package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.base.webserver.WebServerService
import com.jobeen.ime.ui.screen.WebServerScreen

/**
 * Wi-Fi 传输页（2026-11-16 简化，按元书 iOS 模式）：
 * 进页即运行、离页即停，无启停按钮。
 * 启动走 ImeActivity.onResumeExtra（每次回到前台都确保服务在跑；
 * 已在跑时 start 是空操作，服务只走 onStartCommand）；
 * 停止覆写 onPause（退回、切后台、被别页盖住都停服）；转屏／外观
 * 模式切换引起的重建（isChangingConfigurations）不是离页，不停服
 * （否则旋转即吊销 token、回来要重新登录）。
 * 传输层（HTTPS／密码／证书／指纹）一律不动；未设密码时服务拒绝
 * 启动是传输层原有行为（WebServerService.onCreate），本页不再设密码入口。
 */
class WebServerActivity : ImeActivity() {

    override fun onResumeExtra() {
        WebServerService.start(this)
    }

    override fun onPause() {
        // 转屏／外观模式重建不是离页：不停服，onResumeExtra 会在重建后再次确保运行。
        if (!isChangingConfigurations) {
            WebServerService.stop(this)
        }
        super.onPause()
    }

    @Composable
    override fun Content() {
        WebServerScreen(
            onBack = { finish() },
        )
    }
}
