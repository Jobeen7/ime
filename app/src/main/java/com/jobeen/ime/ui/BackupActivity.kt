package com.jobeen.ime.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.backup.readBackupBytes
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.BackupScreen
import com.jobeen.ime.ui.screen.BackupUiState
import com.jobeen.ime.ui.theme.ImeTheme
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.AppAppearanceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与还原：口令在对话框收集，本类负责 SAF 文件读写与加解密调用。
 * 还原成功后杀掉主进程重启——各设置管理器有内存缓存、键盘服务也在本
 * 进程，重启是让覆盖后的设置整体生效的最可靠方式（系统会自动拉起键盘）。
 */
class BackupActivity : ComponentActivity() {
    // 返回即刷新：创建时记下的外观模式；onResume 比对，变了才重建。
    private var createdAppearanceMode: AppAppearanceMode? = null

    private val uiState = BackupUiState()
    private var pendingBackupPassword: CharArray? = null
    private var pendingRestoreUri: Uri? = null

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val password = pendingBackupPassword
        pendingBackupPassword = null
        if (uri != null && password != null) {
            doCreateBackup(uri, password)
        } else {
            password?.fill(' ')
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreUri = uri
            uiState.showRestorePasswordDialog = true
        }
    }

    /**
     * 返回即刷新：从外观页返回时，若应用外观模式已变则重建本页，
     * 主题立即生效；模式未变不重建；重建后新模式即记录值，不循环。
     */
    override fun onResume() {
        super.onResume()
        val recordedAppearanceMode = createdAppearanceMode
        if (recordedAppearanceMode != null &&
            AppAppearance.appearanceModeChanged(recordedAppearanceMode, AppAppearance.getMode(this))
        ) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAppearanceMode = AppAppearance.getMode(this)
        enableEdgeToEdge()
        observeRestoreState()

        val themeMode = AppAppearance.themeMode(this)

        setContent {
            ImeTheme(themeMode = themeMode, dynamicColor = AppAppearance.useDynamicColor(this)) {
                BackupScreen(
                    state = uiState,
                    onBack = { finish() },
                    onCreateBackup = { password ->
                        pendingBackupPassword = password.toCharArray()
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        createDocumentLauncher.launch("jime-backup-$stamp.${BackupManager.FILE_EXTENSION}")
                    },
                    onPickRestoreFile = {
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onRestoreWithPassword = { password ->
                        val uri = pendingRestoreUri
                        pendingRestoreUri = null
                        if (uri != null) doRestore(uri, password.toCharArray())
                    },
                )
            }
        }
    }

    private fun doCreateBackup(uri: Uri, password: CharArray) {
        uiState.busyText = getString(R.string.backup_working_create)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = BackupManager.createBackup(password)
                password.fill(' ')
                val written = contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    true
                } ?: false
                withContext(Dispatchers.Main) {
                    uiState.busyText = null
                    ToastUtil.showToast(
                        if (written) getString(R.string.backup_done) else "备份写入失败"
                    )
                }
            } catch (e: Exception) {
                password.fill(' ')
                Timber.w(e, "Create backup failed")
                withContext(Dispatchers.Main) {
                    uiState.busyText = null
                    ToastUtil.showToast("备份失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun doRestore(uri: Uri, password: CharArray) {
        uiState.busyText = getString(R.string.backup_working_restore)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 流式读取并在读入过程中执行 64MB 硬上限：超大文件在超限
                // 当刻即中止，不会先全量读入内存才被大小检查拦下
                val bytes = contentResolver.openInputStream(uri)?.use { readBackupBytes(it) }
                    ?: throw BackupManager.BackupException("无法读取备份文件")
                // 还原本体交给应用级作用域执行：旋转/退出本页不再取消
                // 还原（旧实现挂在本页作用域上，旋转会把还原与回滚一并
                // 腰斩，留下半还原状态）。结果经 restoreState 回报，
                // 由 onCreate 的收集器统一处理。
                BackupManager.startRestore(bytes, password)
            } catch (e: Exception) {
                password.fill(' ')
                Timber.w(e, "Read backup file failed")
                withContext(Dispatchers.Main) {
                    uiState.busyText = null
                    ToastUtil.showToast("还原失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 观察应用级还原状态：终态统一在这里提示/重启并消费掉。 */
    private fun observeRestoreState() {
        lifecycleScope.launch {
            BackupManager.restoreState.collect { state ->
                when (state) {
                    BackupManager.RestoreState.Running ->
                        uiState.busyText = getString(R.string.backup_working_restore)
                    BackupManager.RestoreState.Done -> {
                        BackupManager.consumeRestoreState()
                        uiState.busyText = null
                        ToastUtil.showToast(getString(R.string.backup_restore_done))
                        // 进程重启由 BackupManager 在置 Done 时自行安排：
                        // 离开本页的用户收不到这条观察，重启不能只挂在这里
                    }
                    is BackupManager.RestoreState.Failed -> {
                        BackupManager.consumeRestoreState()
                        uiState.busyText = null
                        ToastUtil.showToast("还原失败：${state.message}")
                    }
                    BackupManager.RestoreState.Idle -> Unit
                }
            }
        }
    }
}
