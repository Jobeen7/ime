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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    // FAB 栈状态与会话：会话按名共享，复用引擎，不随本页销毁而停引擎。
    private var fabSession: RimeSession? = null
    private var fabDeployRunning by mutableStateOf(false)
    private var fabSyncRunning by mutableStateOf(false)

    private fun fabSession(): RimeSession =
        fabSession ?: RimeDaemon.createSession(FAB_SESSION_NAME).also { fabSession = it }

    /** FAB 部署：复用现有 RimeApi.deploy（关停 + 重启 + 重选方案集），不新写部署逻辑。 */
    private fun doFabDeploy() {
        if (fabDeployRunning) return
        fabDeployRunning = true
        lifecycleScope.launch {
            runCatching {
                fabSession().runOnReady { deploy() }
            }.onSuccess {
                ToastUtil.showToast(getString(R.string.fab_deploy_done))
            }.onFailure {
                Timber.w(it, "FAB deploy failed")
                ToastUtil.showToast(getString(R.string.fab_deploy_failed))
            }
            fabDeployRunning = false
        }
    }

    /**
     * FAB 一键同步：复用现有 WebDavSync 上传流程（内含先下载合并再 PUT，
     * 默认当前方案词典）。未配置 WebDAV 时明示并指引到用户词典页。
     */
    private fun doFabSync() {
        if (fabSyncRunning) return
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast(getString(R.string.fab_sync_not_configured))
            startActivity(Intent(this, UserDictActivity::class.java))
            return
        }
        fabSyncRunning = true
        lifecycleScope.launch {
            try {
                val session = fabSession()
                val dict = currentDictName(session)
                if (dict == null) {
                    ToastUtil.showToast(getString(R.string.fab_sync_no_dict))
                    return@launch
                }
                val result = WebDavSync.upload(dict, session)
                result
                    .onSuccess { count ->
                        ToastUtil.showToast(getString(R.string.fab_sync_success, count))
                    }
                    .onFailure {
                        Timber.w(it, "FAB sync failed")
                        ToastUtil.showToast(getString(R.string.fab_sync_failed))
                    }
            } catch (e: Exception) {
                Timber.w(e, "FAB sync failed")
                ToastUtil.showToast(getString(R.string.fab_sync_engine_not_ready))
            } finally {
                fabSyncRunning = false
            }
        }
    }

    /** 当前方案词典名：先读当前方案的 translator/dictionary，读不到回落首个用户词典。 */
    private suspend fun currentDictName(session: RimeSession): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                Rime.runOnRimeMain {
                    val schemaId = currentSchema().schemaId
                    RimeConfig.openSchema(schemaId).use { config ->
                        config.getString("translator/dictionary")?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    }
                }
            }.getOrNull()
        } ?: runCatching {
            session.runOnReady { getUserDictList() }.firstOrNull()
        }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initState = ImeApplication.getInstance().state.value
        if (initState != ImeApplication.AppState.Finished) {
            initLauncher.launch(Intent(this, InitActivity::class.java))
        } else if (!isImeConfigured()) {
            setupImeLauncher.launch(Intent(this, SetupActivity::class.java))
        }

        setContent {
            var themeMode by remember { mutableIntStateOf(KeyboardManager.Theme.getMode(this@MainActivity)) }

            ImeTheme(themeMode = themeMode) {
                MainScreen(
                    currentThemeMode = themeMode,
                    onThemeModeChanged = { mode ->
                        KeyboardManager.Theme.setMode(this@MainActivity, mode)
                        themeMode = mode
                    },
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
                    onDeploy = { doFabDeploy() },
                    onSync = { doFabSync() },
                    deployRunning = fabDeployRunning,
                    syncRunning = fabSyncRunning,
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
