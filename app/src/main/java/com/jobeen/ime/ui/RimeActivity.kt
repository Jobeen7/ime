package com.jobeen.ime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.ui.screen.RimeScreen
import com.jobeen.ime.ui.theme.ImeTheme

/**
 * RIME 页宿主：与设置 home 的 FAB 共用同一套部署/同步动作
 * （部署 = FabActions.deploy：引擎统一部署路径 deployAndAwait＋方案表复核；
 * 同步 = FabActions.sync：一键同步：方案上传/下载＋词典同步）。
 */
class RimeActivity : ComponentActivity() {

    private val rimeSession by lazy { RimeDaemon.createSession("rime") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode = remember {
                mutableIntStateOf(KeyboardManager.Theme.getMode(this))
            }
            val deployRunning by EngineDeployState.running.collectAsStateWithLifecycle()
            val syncRunning by FabSyncState.running.collectAsStateWithLifecycle()
            ImeTheme(themeMode = themeMode.intValue) {
                RimeScreen(
                    onBack = { finish() },
                    deployRunning = deployRunning,
                    // 与主界面 FAB 同口径：走引擎统一部署路径，不直连会话
                    // deploy()（会打断进行中的切换部署编译，见 MainActivity）。
                    // 此前这里 deploy() 一返回即 Toast 完成，是假完成：返回只
                    // 代表引擎刚启动，不代表编译编完。改为等方案表就绪再报完成。
                    onDeploy = { FabActions.deploy(this@RimeActivity, appScope) },
                    syncRunning = syncRunning,
                    onSync = {
                        FabActions.sync(
                            this@RimeActivity,
                            appScope,
                            sessionProvider = { rimeSession },
                            onNotConfigured = {
                                startActivity(
                                    Intent(
                                        this@RimeActivity,
                                        UserDictActivity::class.java,
                                    ),
                                )
                            },
                        )
                    },
                    onOpenManage = {
                        startActivity(
                            Intent(this@RimeActivity, SchemaSettingsActivity::class.java)
                                .putExtra(SchemaSettingsActivity.EXTRA_OPEN_MANAGE, true),
                        )
                    },
                )
            }
        }
    }
}
