package com.jobeen.ime.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobeen.ime.base.deeplink.DeepLinkRouter
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.ui.screen.MainScreen

class MainActivity : ImeActivity() {
    private val initLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // U-S6：初始化超时点“退出”回 RESULT_CANCELED 时，旧代码不看
        // resultCode、照常打开主页——引擎未就绪却提供部署／同步入口。
        // 未完成即关本页（重进应用会再次等初始化），不再当作已完成。
        if (result.resultCode != RESULT_OK) {
            finish()
            return@registerForActivityResult
        }
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

    /** deep link 待导航页键（WO52 4.2）：MainScreen 消费后清掉。 */
    private var pendingDeepLinkPageKey = mutableStateOf<String?>(null)

    /**
     * jime:// 路由（WO52 4.2）：onCreate 与 onNewIntent 均走此处。
     * phrase 带 id 时直接起 PhraseActivity（EXTRA_PHRASE_ID）；
     * clipboard 带 id 时直接起 ClipboardActivity（EXTRA_CLIPBOARD_ID，
     * 落点到指定条目）；其余页键交 MainScreen 的 rowClicks（与点按同一回调）。
     */
    private fun handleDeepLink(intent: Intent?) {
        val target = DeepLinkRouter.resolve(intent?.action, intent?.dataString) ?: return
        if (target.pageKey == "phrase") {
            val phraseIntent = Intent(this, PhraseActivity::class.java)
            target.params["id"]?.toLongOrNull()?.let { id ->
                phraseIntent.putExtra(PhraseActivity.EXTRA_PHRASE_ID, id)
            }
            startActivity(phraseIntent)
            return
        }
        if (target.pageKey == "clipboard") {
            val clipboardIntent = Intent(this, ClipboardActivity::class.java)
            target.params["id"]?.toLongOrNull()?.let { id ->
                clipboardIntent.putExtra(ClipboardActivity.EXTRA_CLIPBOARD_ID, id)
            }
            startActivity(clipboardIntent)
            return
        }
        pendingDeepLinkPageKey.value = target.pageKey
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResumeExtra() {
        imeConfiguredState.value = isImeConfigured()
    }

    /** 系统输入法选择器（红条点按动作；系统级弹窗，非应用内页面）。 */
    private fun showImePicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    private fun fabSession(): RimeSession =
        fabSession ?: RimeDaemon.createSession(FAB_SESSION_NAME).also { fabSession = it }

    // POST_NOTIFICATIONS 申请（WO52 13.1）：Android 13+ 运行时申请，
    // 每安装只问一次；拒绝不拦任何功能，完成通知静默（见
    // NotificationChannels 降级行为），前台服务照常跑。
    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果只决定通知能否发出，不影响任何功能，不再提示 */ }

    private fun requestPostNotificationsOnce() {
        if (!com.jobeen.ime.base.notify.NotificationChannels.isPermissionRequired()) return
        if (com.jobeen.ime.base.notify.NotificationChannels.isPostNotificationsGranted(this)) return
        val prefs = getSharedPreferences("notif_permission", Context.MODE_PRIVATE)
        if (prefs.getBoolean("post_notifications_requested", false)) return
        prefs.edit().putBoolean("post_notifications_requested", true).apply()
        postNotificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreateBeforeContent(savedInstanceState: Bundle?) {
        requestPostNotificationsOnce()
        // C-L-12：只在全新创建时处理 deep link——重建（旋转等，
        // savedInstanceState 非空）时 intent 里仍是旧 deep link，
        // 再跑一次会二次跳转；新 intent 照常走 onNewIntent。
        if (savedInstanceState == null) handleDeepLink(intent)
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
    }

    @Composable
    override fun Content() {
        val deployRunning by EngineDeployState.running.collectAsStateWithLifecycle()
        val syncRunning by FabSyncState.running.collectAsStateWithLifecycle()
        // 应用主题已删除：恒跟随系统（迁移见
        // KeyboardManager.Theme.migrateToSystem / getMode）。
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
            onOpenEmoji = {
                startActivity(
                    Intent(this@MainActivity, EmojiSettingsActivity::class.java)
                )
            },
            onOpenKeyboardMenu = {
                startActivity(
                    Intent(this@MainActivity, KeyboardMenuSettingsActivity::class.java)
                )
            },
            onOpenToolbar = {
                startActivity(
                    Intent(this@MainActivity, ToolbarActivity::class.java)
                )
            },
            onOpenSymbolBar = {
                startActivity(
                    Intent(this@MainActivity, SymbolBarSettingsActivity::class.java)
                )
            },
            onOpenInputStats = {
                startActivity(
                    Intent(this@MainActivity, InputStatsActivity::class.java)
                )
            },
            onOpenAppearance = {
                startActivity(
                    Intent(this@MainActivity, AppearanceActivity::class.java)
                )
            },
            onOpenClipboard = {
                startActivity(
                    Intent(this@MainActivity, ClipboardActivity::class.java)
                )
            },
            onOpenPhrase = {
                startActivity(
                    Intent(this@MainActivity, PhraseActivity::class.java)
                )
            },
            onOpenAi = {
                startActivity(
                    Intent(this@MainActivity, AiActivity::class.java)
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
            deepLinkPageKey = pendingDeepLinkPageKey.value,
            onDeepLinkConsumed = { pendingDeepLinkPageKey.value = null },
        )
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
