package com.jobeen.ime.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.ui.screen.MainScreen
import com.jobeen.ime.ui.theme.ImeTheme

class MainActivity : ComponentActivity() {

    private val initLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!isImeConfigured()) {
            setupImeLauncher.launch(Intent(this, SetupActivity::class.java))
        }
    }

    private val setupImeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) finish()
    }

    // FAB 会话：按名共享，复用引擎，不随本页销毁而停引擎。
    // 运行状态不再本地持有：部署订阅 EngineDeployState.running，
    // 同步订阅 FabSyncState.running（进程级，两处 Activity 共用）。
    private var fabSession: RimeSession? = null

    /**
     * 红条状态：从系统输入法设置读当前 IME（复用 isImeConfigured，不另写
     * 第二套判断）。onResume 刷新——用户在系统选择器切走/切回后回到
     * 设置页，红条立即跟上，不用重启应用。
     */
    private var imeConfiguredState = mutableStateOf(true)

    override fun onResume() {
        super.onResume()
        imeConfiguredState.value = isImeConfigured()
    }

    /** 系统输入法选择器（红条点按动作；系统级弹窗，非应用内页面）。 */
    private fun showImePicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    private fun fabSession(): RimeSession =
        fabSession ?: RimeDaemon.createSession(FAB_SESSION_NAME).also { fabSession = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 跟随系统主题的迁移已落到共享入口
        // KeyboardManager.Theme（getMode 读即迁移）：此处在
        // setContent 之前再显式迁一次，组合副作用不再留在
        // setContent 内；直启其它页不依赖本页也已迁完。
        KeyboardManager.Theme.migrateToSystem(this)
        imeConfiguredState.value = isImeConfigured()

        val initState = ImeApplication.getInstance().state.value
        if (initState != ImeApplication.AppState.Finished) {
            initLauncher.launch(Intent(this, InitActivity::class.java))
        } else if (!isImeConfigured()) {
            setupImeLauncher.launch(Intent(this, SetupActivity::class.java))
        }

        setContent {
            val deployRunning by EngineDeployState.running.collectAsStateWithLifecycle()
            val syncRunning by FabSyncState.running.collectAsStateWithLifecycle()
            // 应用主题已删除：恒跟随系统（迁移见
            // KeyboardManager.Theme.migrateToSystem / getMode）。
            ImeTheme(themeMode = KeyboardManager.Theme.MODE_SYSTEM) {
                MainScreen(
                    onOpenImeSetup = {
                        setupImeLauncher.launch(
                            Intent(this@MainActivity, SetupActivity::class.java)
                        )
                    },
                    onOpenSchemaSettings = {
                        startActivity(
                            Intent(this@MainActivity, SchemaSettingsActivity::class.java)
                        )
                    },
                    onOpenSchemeUpdate = {
                        startActivity(
                            Intent(this@MainActivity, SchemeUpdateActivity::class.java)
                        )
                    },
                    onOpenRime = {
                        startActivity(
                            Intent(this@MainActivity, RimeActivity::class.java)
                        )
                    },
                    isImeConfigured = imeConfiguredState.value,
                    onOpenImePicker = { showImePicker() },
                    onOpenKeyboardSettings = {
                        startActivity(
                            Intent(this@MainActivity, KeyboardSettingsActivity::class.java)
                        )
                    },
                    onOpenKeyboardThemeSettings = {
                        startActivity(
                            Intent(this@MainActivity, KeyboardThemeSettingsActivity::class.java)
                        )
                    },
                    onOpenClipboard = {
                        startActivity(
                            Intent(this@MainActivity, ClipboardActivity::class.java)
                        )
                    },
                    onOpenModelSettings = {
                        startActivity(
                            Intent(this@MainActivity, PredictionSettingsActivity::class.java)
                        )
                    },
                    onOpenVoiceSettings = {
                        startActivity(
                            Intent(this@MainActivity, VoiceSettingsActivity::class.java)
                        )
                    },
                    onOpenUserDict = {
                        startActivity(
                            Intent(this@MainActivity, UserDictActivity::class.java)
                        )
                    },
                    onOpenBackup = {
                        startActivity(
                            Intent(this@MainActivity, BackupActivity::class.java)
                        )
                    },
                    onOpenFiles = {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                DocumentsContract.buildRootUri(
                                    AppFilesDocumentsProvider.AUTHORITY,
                                    AppFilesDocumentsProvider.ROOT_ID,
                                ),
                            ),
                        )
                    },
                    onOpenAbout = {
                        startActivity(
                            Intent(this@MainActivity, AboutActivity::class.java)
                        )
                    },
                    onDeploy = { FabActions.deploy(this@MainActivity, appScope) },
                    onSync = {
                        FabActions.sync(
                            this@MainActivity,
                            appScope,
                            sessionProvider = { fabSession() },
                            onNotConfigured = {
                                startActivity(
                                    Intent(this@MainActivity, UserDictActivity::class.java),
                                )
                            },
                        )
                    },
                    deployRunning = deployRunning,
                    syncRunning = syncRunning,
                )
            }
        }
    }

    private fun isImeConfigured(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val ourComponent = ComponentName(this, ImeInputMethodService::class.java)
        val isEnabled = imm.enabledInputMethodList.any {
            it.packageName == ourComponent.packageName && it.serviceName == ourComponent.className
        }
        val currentId =
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ourId = ourComponent.flattenToShortString()
        return isEnabled && currentId == ourId
    }

    companion object {
        private const val FAB_SESSION_NAME = "home-fab"
    }
}
