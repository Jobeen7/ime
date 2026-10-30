package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow
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
    var webdavAllowHttp by mutableStateOf(false)
    var lastUpload by mutableStateOf(0L)
    var lastDownload by mutableStateOf(0L)
    /** 后台定期同步（2026-10-29 批量第 5 项）：开关／间隔／上次结果 */
    var periodicSyncEnabled by mutableStateOf(false)
    var periodicIntervalHours by mutableStateOf(24)
    var lastPeriodicSync by mutableStateOf(0L)
    var lastPeriodicSyncOk by mutableStateOf(false)
    /** 服务器上的备份文件列表；非空时弹出选择对话框 */
    var remoteFiles by mutableStateOf<List<String>?>(null)
    /** 已删除词管理对话框：非空时打开，内容为当前已删词列表 */
    var deletedWords by mutableStateOf<List<String>?>(null)
    var noticeTitle by mutableStateOf("")
    var noticeMessage by mutableStateOf<String?>(null)
    /** 词典列表加载失败（超时/异常）时为真：区别于“加载中”（null）和“确实为空”（空表）。
     * 失败不锁死为空表，界面出重试入口；切后台再回来时 Activity 也会重载。 */
    var dictsLoadFailed by mutableStateOf(false)
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
    onAllowHttpChange: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onUploadSchemes: () -> Unit,
    onDownloadSchemes: () -> Unit,
    onTestConnection: () -> Unit,
    onPickRemoteFile: (String) -> Unit,
    onDismissRemoteFiles: () -> Unit,
    onDismissNotice: () -> Unit,
    onShowDeletedWords: () -> Unit,
    onRestoreDeletedWord: (String) -> Unit,
    onDismissDeletedWords: () -> Unit,
    onOpenWordCreation: () -> Unit,
    onPeriodicSyncEnabledChange: (Boolean) -> Unit,
    onPeriodicIntervalChange: (Int) -> Unit,
    onRetryDictList: () -> Unit = {},
) {
    var showDictDialog by remember { mutableStateOf(false) }
    var showHttpRiskConfirm by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var serverText by remember { mutableStateOf(state.webdavServer) }
    var usernameText by remember { mutableStateOf(state.webdavUsername) }
    var passwordText by remember { mutableStateOf(state.webdavPassword) }
    var syncPathText by remember { mutableStateOf(state.webdavSyncPath) }

    val dicts = state.dicts
    val enabled = !state.busy && state.selectedDict != null

    SettingsPageScaffold(
        title = stringResource(R.string.user_dict),
        onBack = onBack,
    ) {
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
                dicts == null && state.dictsLoadFailed -> {
                    // 加载失败态：不冒充“暂无词典”，给重试入口。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.user_dict_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.Button(onClick = onRetryDictList) {
                            Text(stringResource(R.string.user_dict_retry))
                        }
                    }
                }
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
                        GroupCard {
                            NavRow(
                                title = state.selectedDict.orEmpty(),
                                subtitle = stringResource(R.string.user_dict_switch_dict),
                                onClick = { if (!state.busy) showDictDialog = true },
                            )
                        }
                    }

                    SettingsGroup(title = stringResource(R.string.word_creation_title)) {
                        NavRow(
                            title = stringResource(R.string.word_creation_title),
                            subtitle = stringResource(R.string.word_creation_desc),
                            onClick = { if (!state.busy) onOpenWordCreation() },
                        )
                    }

                    SettingsGroup(title = stringResource(R.string.user_dict_manual_backup)) {
                        NavRow(
                            title = stringResource(R.string.user_dict_export),
                            subtitle = stringResource(R.string.user_dict_export_desc),
                            onClick = { if (enabled) onExport() },
                        )
                        NavRow(
                            title = stringResource(R.string.user_dict_import),
                            subtitle = stringResource(R.string.user_dict_import_desc),
                            onClick = { if (enabled) onImport() },
                        )
                    }

                    SettingsGroup(title = stringResource(R.string.user_dict_webdav_sync)) {
                        // WebDAV 表单：字段与保存逻辑不动，只收进分组卡片。
                        Column(Modifier.padding(vertical = 12.dp)) {
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
                        }
                    }


                    SettingsGroup(title = stringResource(R.string.user_dict_periodic_sync)) {
                        SwitchRow(
                            title = stringResource(R.string.user_dict_periodic_sync),
                            checked = state.periodicSyncEnabled,
                            onCheckedChange = onPeriodicSyncEnabledChange,
                            showDivider = true,
                        )
                        NavRow(
                            title = stringResource(R.string.user_dict_periodic_interval),
                            subtitle = stringResource(
                                R.string.user_dict_periodic_every_hours,
                                state.periodicIntervalHours,
                            ),
                            onClick = { showIntervalDialog = true },
                        )
                        val lastSyncText = if (state.lastPeriodicSync <= 0L) {
                            formatTime(0L)
                        } else {
                            formatTime(state.lastPeriodicSync) + "（" +
                                stringResource(
                                    if (state.lastPeriodicSyncOk) {
                                        R.string.user_dict_periodic_last_ok
                                    } else {
                                        R.string.user_dict_periodic_last_failed
                                    }
                                ) + "）"
                        }
                        Text(
                            text = stringResource(
                                R.string.user_dict_periodic_last_sync,
                                lastSyncText,
                            ),
                            modifier = Modifier.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }


                    SettingsGroup(title = stringResource(R.string.user_dict_advanced)) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            SwitchRow(
                                title = stringResource(R.string.user_dict_allow_http),
                                checked = state.webdavAllowHttp,
                                onCheckedChange = { wantOn ->
                                    // 开启必须先弹风险确认；关闭直接生效
                                    if (wantOn) showHttpRiskConfirm = true else onAllowHttpChange(false)
                                },
                                enabled = !state.busy,
                            )
                            Text(
                                text = stringResource(R.string.user_dict_allow_http_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NavRow(
                            title = stringResource(R.string.user_dict_upload),
                            subtitle = "上次上传：${formatTime(state.lastUpload)}",
                            onClick = { if (enabled) onUpload() },
                        )
                        NavRow(
                            title = stringResource(R.string.user_dict_download),
                            subtitle = stringResource(
                                R.string.user_dict_download_hint,
                                formatTime(state.lastDownload),
                            ),
                            onClick = { if (enabled) onDownload() },
                        )
                        NavRow(
                            title = stringResource(R.string.user_dict_upload_schemes),
                            subtitle = stringResource(R.string.user_dict_upload_schemes_hint),
                            onClick = { if (!state.busy) onUploadSchemes() },
                        )
                        NavRow(
                            title = stringResource(R.string.user_dict_download_schemes),
                            subtitle = stringResource(R.string.user_dict_download_schemes_hint),
                            onClick = { if (!state.busy) onDownloadSchemes() },
                        )
                        NavRow(
                            title = stringResource(R.string.user_dict_deleted_words),
                            subtitle = stringResource(R.string.user_dict_deleted_words_hint),
                            onClick = { if (!state.busy) onShowDeletedWords() },
                        )
                    }
                }
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

    val deletedWords = state.deletedWords
    if (deletedWords != null) {
        SettingsDialog(
            onDismissRequest = onDismissDeletedWords,
            title = stringResource(R.string.user_dict_deleted_words),
            scrollable = false,
            body = {
                if (deletedWords.isEmpty()) {
                    Text(stringResource(R.string.user_dict_deleted_words_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(deletedWords) { word ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(word, Modifier.weight(1f))
                                TextButton(onClick = { onRestoreDeletedWord(word) }) {
                                    Text(stringResource(R.string.user_dict_restore))
                                }
                            }
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.user_dict_dialog_close),
                    onClick = onDismissDeletedWords,
                ),
            ),
        )
    }

    if (showHttpRiskConfirm) {
        SettingsConfirmDialog(
            onDismissRequest = { showHttpRiskConfirm = false },
            title = stringResource(R.string.user_dict_allow_http_confirm_title),
            text = stringResource(R.string.user_dict_allow_http_confirm_msg),
            confirmText = stringResource(R.string.user_dict_allow_http_confirm_ok),
            onConfirm = {
                showHttpRiskConfirm = false
                onAllowHttpChange(true)
            },
            dismissText = stringResource(R.string.user_dict_cancel),
        )
    }

    val noticeMsg = state.noticeMessage
    if (noticeMsg != null) {
        SettingsConfirmDialog(
            onDismissRequest = onDismissNotice,
            title = state.noticeTitle,
            text = noticeMsg,
            confirmText = stringResource(R.string.user_dict_dialog_close),
            onConfirm = onDismissNotice,
        )
    }

    if (showIntervalDialog) {
        val options = com.jobeen.ime.engine.rime.data.userdict.PeriodicSyncPrefs.INTERVAL_OPTIONS
        SingleChoiceDialog(
            title = stringResource(R.string.user_dict_periodic_interval),
            options = options.map {
                stringResource(R.string.user_dict_periodic_every_hours, it)
            },
            selectedIndex = options.indexOf(state.periodicIntervalHours),
            onSelect = { index ->
                onPeriodicIntervalChange(options[index])
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }
}
