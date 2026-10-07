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
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw BackupManager.BackupException("无法读取备份文件")
                // 上限防御：备份是本地小数据，超过 64MB 视为异常文件
                if (bytes.size > 64 * 1024 * 1024) {
                    throw BackupManager.BackupException("备份文件过大")
                }
                BackupManager.restoreBackup(bytes, password)
                password.fill(' ')
                withContext(Dispatchers.Main) {
                    uiState.busyText = null
                    ToastUtil.showToast(getString(R.string.backup_restore_done))
                    // 延迟杀进程让 Toast 有机会显示；系统会重新拉起应用与键盘
                    window.decorView.postDelayed({ Process.killProcess(Process.myPid()) }, 600)
                }
            } catch (e: Exception) {
                password.fill(' ')
                Timber.w(e, "Restore backup failed")
                withContext(Dispatchers.Main) {
                    uiState.busyText = null
                    ToastUtil.showToast("还原失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }
}
