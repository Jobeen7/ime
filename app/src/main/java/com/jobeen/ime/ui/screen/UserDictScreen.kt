package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.ui.screen.ScreenComponent.ClickableSettingItem
import com.jobeen.ime.ui.screen.ScreenComponent.SectionHeader
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 用户词典设置界面的 UI 状态，由 UserDictActivity 持有并更新 */
class UserDictUiState {
    var dicts by mutableStateOf<List<String>?>(null)
    var selectedDict by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var busyMessage by mutableStateOf("")
    var webdavServer by mutableStateOf("")
    var webdavUsername by mutableStateOf("")
    var webdavPassword by mutableStateOf("")
    var webdavSyncPath by mutableStateOf("")
    var lastUpload by mutableStateOf(0L)
    var lastDownload by mutableStateOf(0L)
    /** 服务器上的备份文件列表；非空时弹出选择对话框 */
    var remoteFiles by mutableStateOf<List<String>?>(null)
    var noticeTitle by mutableStateOf("")
    var noticeMessage by mutableStateOf<String?>(null)
}

private fun formatTime(time: Long): String {
    if (time <= 0L) return "从未"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDictScreen(
    state: UserDictUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSaveWebDav: (server: String, username: String, password: String, syncPath: String) -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onTestConnection: () -> Unit,
    onPickRemoteFile: (String) -> Unit,
    onDismissRemoteFiles: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var showDictDialog by remember { mutableStateOf(false) }
    var serverText by remember { mutableStateOf(state.webdavServer) }
    var usernameText by remember { mutableStateOf(state.webdavUsername) }
    var passwordText by remember { mutableStateOf(state.webdavPassword) }
    var syncPathText by remember { mutableStateOf(state.webdavSyncPath) }

    val dicts = state.dicts
    val enabled = !state.busy && state.selectedDict != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.user_dict),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (state.busy) {
                Text(
                    text = state.busyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            when {
                dicts == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(32.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }
                dicts.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.user_dict_no_dict),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> {
                    if (dicts.size > 1) {
                        SectionHeader(stringResource(R.string.user_dict))
                        ClickableSettingItem(
                            title = state.selectedDict.orEmpty(),
                            subtitle = stringResource(R.string.user_dict_switch_dict),
                            onClick = { if (!state.busy) showDictDialog = true },
                            showSpacer = true,
                        )
                    }

                    SectionHeader(stringResource(R.string.user_dict_manual_backup))

                    ClickableSettingItem(
                        title = stringResource(R.string.user_dict_export),
                        subtitle = stringResource(R.string.user_dict_export_desc),
                        onClick = { if (enabled) onExport() },
                        icon = Icons.Filled.Upload,
                        showSpacer = true,
                    )

                    ClickableSettingItem(
                        title = stringResource(R.string.user_dict_import),
                        subtitle = stringResource(R.string.user_dict_import_desc),
                        onClick = { if (enabled) onImport() },
                        icon = Icons.Filled.Download,
                        showSpacer = true,
                    )

                    SectionHeader(stringResource(R.string.user_dict_webdav_sync))

                    OutlinedTextField(
                        value = serverText,
                        onValueChange = { serverText = it },
                        label = { Text(stringResource(R.string.user_dict_server)) },
                        placeholder = { Text(stringResource(R.string.user_dict_server_hint)) },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = usernameText,
                        onValueChange = { usernameText = it },
                        label = { Text(stringResource(R.string.user_dict_username)) },
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        label = { Text(stringResource(R.string.user_dict_password)) },
                        singleLine = true,
                        enabled = !state.busy,
                        visualTransformation = if (passwordText.isEmpty()) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = syncPathText,
                        onValueChange = { syncPathText = it },
                        label = { Text(stringResource(R.string.user_dict_sync_path)) },
                        placeholder = { Text(stringResource(R.string.user_dict_sync_path_hint)) },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.user_dict_test_connection))
                        }
                        Button(
                            onClick = { onSaveWebDav(serverText, usernameText, passwordText, syncPathText) },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.user_dict_save))
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    ClickableSettingItem(
                        title = stringResource(R.string.user_dict_upload),
                        subtitle = "上次上传：${formatTime(state.lastUpload)}",
                        onClick = { if (enabled) onUpload() },
                        icon = Icons.Filled.CloudUpload,
                        showSpacer = true,
                    )

                    ClickableSettingItem(
                        title = stringResource(R.string.user_dict_download),
                        subtitle = stringResource(
                            R.string.user_dict_download_hint,
                            formatTime(state.lastDownload),
                        ),
                        onClick = { if (enabled) onDownload() },
                        icon = Icons.Filled.CloudDownload,
                        showSpacer = true,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDictDialog && dicts != null) {
        SingleChoiceDialog(
            title = stringResource(R.string.user_dict),
            options = dicts,
            selectedIndex = dicts.indexOf(state.selectedDict).coerceAtLeast(0),
            onSelect = {
                state.selectedDict = dicts[it]
                showDictDialog = false
            },
            onDismiss = { showDictDialog = false },
        )
    }

    val remoteFiles = state.remoteFiles
    if (remoteFiles != null) {
        SingleChoiceDialog(
            title = stringResource(R.string.user_dict_pick_remote),
            options = remoteFiles,
            selectedIndex = 0,
            onSelect = { onPickRemoteFile(remoteFiles[it]) },
            onDismiss = onDismissRemoteFiles,
        )
    }

    val noticeMsg = state.noticeMessage
    if (noticeMsg != null) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            confirmButton = {
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.user_dict_dialog_close))
                }
            },
            title = { Text(state.noticeTitle) },
            text = { Text(noticeMsg) },
        )
    }
}
