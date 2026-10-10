package com.jobeen.ime.ui

import android.net.Uri
import android.os.Bundle
import android.os.Process
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeRestoreState()
        // 上次还原若在写入途中进程被杀，会留一份还原前的加密快照：
        // 提示用户凭备份口令回滚，或明确丢弃
        if (BackupManager.hasRestoreSnapshot() &&
            BackupManager.restoreState.value == BackupManager.RestoreState.Idle
        ) {
            uiState.showSnapshotRecoveryDialog = true
        }

        val themeMode = KeyboardManager.Theme.getMode(this)

        setContent {
            ImeTheme(themeMode = themeMode) {
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
                    onRollbackSnapshot = {
                        uiState.showSnapshotRecoveryDialog = false
                        uiState.showSnapshotPasswordDialog = true
                    },
                    onRollbackSnapshotWithPassword = { password ->
                        doRollbackSnapshot(password.toCharArray())
                    },
                    onDiscardSnapshot = {
                        uiState.showSnapshotRecoveryDialog = false
                        BackupManager.discardRestoreSnapshot()
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

    /** 用快照口令把上次中断的还原回滚掉（快照即还原前状态的一份备份）。 */
    private fun doRollbackSnapshot(password: CharArray) {
        uiState.busyText = getString(R.string.backup_working_restore)
        BackupManager.startRestore(ByteArray(0), password, rollbackSnapshot = true)
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
                        // 延迟杀进程让 Toast 有机会显示；系统会重新拉起应用与键盘
                        window.decorView.postDelayed(
                            { Process.killProcess(Process.myPid()) }, 600
                        )
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
