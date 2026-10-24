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
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.data.manager.awaitSchemasReady
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import kotlinx.coroutines.launch
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

    /**
     * FAB 部署：走引擎统一部署路径（Action.Reload，进作业队列串行执行），
     * 不再直连 Daemon 会话调 deploy()。直连会绕过作业队列，在切换部署
     * 编译中途关停引擎、重启编译，并把引擎作业队列堵在旧部署的
     * joinMaintenanceThread 上，方案页从此永久显示“正在部署…”（2026-10-25
     * Base 导入实证：切换部署 03:52:03 开始，03:53:17 一次直连部署打断后，
     * 引擎再无 Finish 消息、方案表恒空）。
     * 部署进行中直接拒（Toast），不排队、不重启：排队会在本次部署后再跑
     * 一轮全量编译，等于把等待时间翻倍。
     * 完成判定等引擎方案表就绪（每 2s 轮询，最多 10 分钟）；超时按失败处理。
     * 等待期间 fabDeployRunning 保持 true，FAB 转圈即可见进度。
     */
    private fun doFabDeploy() {
        if (fabDeployRunning) return
        if (EngineDeployState.isRunning()) {
            ToastUtil.showToast(getString(R.string.fab_deploy_busy))
            return
        }
        val engine = EngineFactory.current()
        if (engine == null) {
            ToastUtil.showToast(getString(R.string.fab_deploy_failed))
            return
        }
        fabDeployRunning = true
        lifecycleScope.launch {
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
                ToastUtil.showToast(getString(R.string.fab_deploy_done))
                RimeSyncPrefs.postNotice(this@MainActivity, getString(R.string.fab_deploy_done))
            } else {
                Timber.w("FAB deploy timed out waiting for schemas")
                ToastUtil.showToast(getString(R.string.fab_deploy_failed))
                RimeSyncPrefs.postNotice(this@MainActivity, getString(R.string.fab_deploy_failed))
            }
            fabDeployRunning = false
        }
    }

    /**
     * FAB 一键同步：目录同步（新口径，覆盖原词库合并定义）——经现有
     * WebDAV 客户端，把整个用户方案目录同步到远端 UUID 文件夹。
     * 用户词典页原有的词库合并上传/下载保持不动（另一条线）。
     * 未配置 WebDAV 时明示并指引到用户词典页。
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
                val result = WebDavSync.syncSchemeDir(fabSession())
                result.onSuccess { r ->
                    val msg = if (r.failedFiles.isEmpty()) {
                        getString(R.string.fab_dir_sync_success, r.uploaded, r.downloaded)
                    } else {
                        getString(
                            R.string.fab_dir_sync_partial,
                            r.uploaded,
                            r.downloaded,
                            r.failedFiles.size,
                        )
                    }
                    ToastUtil.showToast(msg)
                    RimeSyncPrefs.postNotice(this@MainActivity, msg)
                }.onFailure {
                    Timber.w(it, "FAB dir sync failed")
                    ToastUtil.showToast(getString(R.string.fab_sync_failed))
                    RimeSyncPrefs.postNotice(
                        this@MainActivity,
                        getString(R.string.fab_sync_failed),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "FAB dir sync failed")
                ToastUtil.showToast(getString(R.string.fab_sync_engine_not_ready))
            } finally {
                fabSyncRunning = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        imeConfiguredState.value = isImeConfigured()

        val initState = ImeApplication.getInstance().state.value
        if (initState != ImeApplication.AppState.Finished) {
            initLauncher.launch(Intent(this, InitActivity::class.java))
        } else if (!isImeConfigured()) {
            setupImeLauncher.launch(Intent(this, SetupActivity::class.java))
        }

        setContent {
            // 应用主题已删除（Lee 2026-10-25 定）：恒跟随系统。旧手动值
            // 在此一次性迁回跟随系统，其余页面读同一偏好即跟随。
            if (KeyboardManager.Theme.getMode(this@MainActivity) !=
                KeyboardManager.Theme.MODE_SYSTEM
            ) {
                KeyboardManager.Theme.setMode(
                    this@MainActivity,
                    KeyboardManager.Theme.MODE_SYSTEM,
                )
            }

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
