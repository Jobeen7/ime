// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.AppReset
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.GroupDivider
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.ResetConfirmDialogs
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow
import kotlinx.coroutines.launch

/**
 * RIME 页：只含 Lee 定的 6 项，不加日志、不加简繁切换。
 * 部署 = 与 FAB 部署同一动作（Activity 传入）；同步 = 目录同步（同 FAB 同步）；
 * 方案重置 = 与关于页同一重置动作（AppReset.resetToInitialState）；
 * 方案列表管理 = 跳转现有管理页；通知开关 = 键盘 RIME 通知；
 * 同步文件夹 = UUID 名 + 完整远程路径（显示用，不可编辑）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RimeScreen(
    onBack: () -> Unit,
    deployRunning: Boolean,
    onDeploy: () -> Unit,
    syncRunning: Boolean,
    onSync: () -> Unit,
    onOpenManage: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 回调内不可用 stringResource，提前在组合阶段解析
    val msgResetFailed = stringResource(R.string.about_reset_failed)
    var resetStep by remember { mutableStateOf(0) }
    var resetting by remember { mutableStateOf(false) }
    var noticeEnabled by remember { mutableStateOf(true) }
    var syncFolderUuid by remember { mutableStateOf("") }
    var syncRemotePath by remember { mutableStateOf("") }
    var webdavConfigured by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        noticeEnabled = RimeSyncPrefs.isNoticeEnabled(context)
        webdavConfigured = UserDictPrefs.isConfigured()
        // 首次打开即生成并持久化 UUID（之后重置会清掉它，见 RimeSyncPrefs）。
        syncFolderUuid = RimeSyncPrefs.syncFolderUuid(context)
        syncRemotePath = if (webdavConfigured) {
            WebDavSync.dirSyncRemoteUrl(context)
        } else {
            ""
        }
    }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 重置执行：与关于页同一动作；成功后以新任务重进设置 home。 */
    fun doReset() {
        if (resetting) return
        resetting = true
        scope.launch {
            val result = AppReset.resetToInitialState(context)
            resetting = false
            result.onSuccess {
                val intent = android.content.Intent(
                    context,
                    com.jobeen.ime.ui.MainActivity::class.java,
                ).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)
                (context as? android.app.Activity)?.finish()
            }.onFailure {
                toast(msgResetFailed)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.rime_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            GroupCard {
                NavRow(
                    icon = Icons.Filled.RocketLaunch,
                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    title = stringResource(R.string.rime_deploy),
                    subtitle = if (deployRunning) {
                        stringResource(R.string.rime_deploy_running)
                    } else {
                        null
                    },
                    onClick = { if (!deployRunning) onDeploy() },
                )
                GroupDivider()
                NavRow(
                    icon = Icons.Filled.Sync,
                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    title = stringResource(R.string.rime_sync),
                    subtitle = if (syncRunning) {
                        stringResource(R.string.rime_sync_running)
                    } else {
                        null
                    },
                    onClick = { if (!syncRunning) onSync() },
                )
                GroupDivider()
                NavRow(
                    icon = Icons.Filled.Refresh,
                    title = stringResource(R.string.rime_reset),
                    subtitle = if (resetting) {
                        stringResource(R.string.about_reset_busy)
                    } else {
                        null
                    },
                    titleColor = MaterialTheme.colorScheme.error,
                    iconContainer = MaterialTheme.colorScheme.errorContainer,
                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { if (!resetting) resetStep = 1 },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            GroupCard {
                NavRow(
                    icon = Icons.Filled.List,
                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    title = stringResource(R.string.rime_manage),
                    onClick = onOpenManage,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            GroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    SwitchRow(
                        title = stringResource(R.string.rime_notice),
                        checked = noticeEnabled,
                        onCheckedChange = {
                            noticeEnabled = it
                            RimeSyncPrefs.setNoticeEnabled(context, it)
                        },
                    )
                    Text(
                        text = stringResource(R.string.rime_notice_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            GroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rime_sync_folder),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = syncFolderUuid,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GroupDivider()
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rime_sync_folder_path),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (webdavConfigured) {
                            syncRemotePath
                        } else {
                            stringResource(R.string.rime_sync_folder_unconfigured)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    // 重置二次确认：与关于页同一对话框、同一动作，绝不加“不再询问”。
    ResetConfirmDialogs(
        step = resetStep,
        onStepChange = { resetStep = it },
        onConfirmed = { doReset() },
    )
}
