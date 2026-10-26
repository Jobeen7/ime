// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jobeen.ime.data.manager.SchemeDirs
import java.net.URLEncoder
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
 * 同步文件夹与完整远程路径均可点按编辑：文件夹名是目录同步的远端
 * 文件夹标识（对齐 Rime 原生多设备同步的 installation_id 角色，改后
 * 下次同步即以新文件夹为词典合并对象）；完整路径 = 同步根／文件夹／
 * 当前目录，编辑时只允许改同步路径与文件夹，服务器地址仍归用户词典页。
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
    // 编辑弹窗状态：非空 = 打开中，值为编辑框初始文本。
    var uuidEdit by remember { mutableStateOf<String?>(null) }
    var pathEdit by remember { mutableStateOf<String?>(null) }
    // 旧残留清理：非空 = 确认弹窗打开中，值为 UUID 根的直接子项。
    var cleanupEntries by remember { mutableStateOf<List<WebDavSync.UuidRootEntry>?>(null) }
    var cleanupBusy by remember { mutableStateOf(false) }

    /** 编辑后刷新两行显示（路径由偏好现算，与同步用同一构成）。 */
    fun refreshSyncDisplay() {
        syncFolderUuid = RimeSyncPrefs.syncFolderUuid(context)
        syncRemotePath = if (UserDictPrefs.isConfigured()) {
            WebDavSync.dirSyncRemoteUrl(context)
        } else {
            ""
        }
    }

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
                    onClick = { if (!resetting) resetStep = 1 },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            GroupCard {
                NavRow(
                    icon = Icons.Filled.List,
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
                        .clickable { uuidEdit = syncFolderUuid }
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
                    modifier = Modifier
                        .clickable(enabled = webdavConfigured) {
                            pathEdit = syncRemotePath
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = webdavConfigured && !cleanupBusy) {
                            scope.launch {
                                cleanupBusy = true
                                val result = WebDavSync.listUuidRootEntries(syncFolderUuid)
                                cleanupBusy = false
                                result.onSuccess { entries ->
                                    if (entries.isEmpty()) {
                                        toast(
                                            context.getString(R.string.rime_sync_cleanup_empty)
                                        )
                                    } else {
                                        cleanupEntries = entries
                                    }
                                }.onFailure {
                                    toast(
                                        it.message
                                            ?: context.getString(R.string.rime_sync_cleanup_failed)
                                    )
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rime_sync_cleanup_orphans),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
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

    // 同步文件夹编辑：单段名，非法不保存、弹窗不关闭。
    uuidEdit?.let { initial ->
        var text by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { uuidEdit = null },
            title = { Text(stringResource(R.string.rime_sync_folder_edit_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (RimeSyncPrefs.setSyncFolderUuid(context, text)) {
                        refreshSyncDisplay()
                        uuidEdit = null
                    } else {
                        toast(context.getString(R.string.rime_sync_folder_invalid))
                    }
                }) { Text(stringResource(R.string.schema_manage_save)) }
            },
            dismissButton = {
                TextButton(onClick = { uuidEdit = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 完整远程路径编辑：拆回同步路径与文件夹名（服务器地址不动）。
    pathEdit?.let { initial ->
        var text by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { pathEdit = null },
            title = { Text(stringResource(R.string.rime_sync_folder_path_edit_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (applyEditedRemotePath(context, text)) {
                        PathEditOutcome.OK -> {
                            refreshSyncDisplay()
                            pathEdit = null
                        }
                        PathEditOutcome.INVALID -> {
                            toast(context.getString(R.string.rime_sync_path_invalid))
                        }
                        PathEditOutcome.SERVER_CHANGED -> {
                            toast(context.getString(R.string.rime_sync_path_server_changed))
                        }
                    }
                }) { Text(stringResource(R.string.schema_manage_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pathEdit = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 旧残留清理确认：只删文件，文件夹只计跳过、不自动删。
    cleanupEntries?.let { entries ->
        val fileCount = entries.count { !it.isDirectory }
        val dirCount = entries.count { it.isDirectory }
        AlertDialog(
            onDismissRequest = { cleanupEntries = null },
            title = { Text(stringResource(R.string.rime_sync_cleanup_orphans)) },
            text = {
                Text(
                    stringResource(
                        R.string.rime_sync_cleanup_confirm,
                        fileCount,
                        dirCount,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val doomed = entries.filter { !it.isDirectory }
                    cleanupEntries = null
                    scope.launch {
                        cleanupBusy = true
                        var deleted = 0
                        var failed = 0
                        for (entry in doomed) {
                            if (WebDavSync.deleteRemoteFile(
                                    WebDavSync.uuidFileUrl(syncFolderUuid, entry.name)
                                )
                            ) {
                                deleted++
                            } else {
                                failed++
                            }
                        }
                        cleanupBusy = false
                        var msg = context.getString(
                            R.string.rime_sync_cleanup_done,
                            deleted,
                            dirCount,
                        )
                        if (failed > 0) {
                            msg += context.getString(
                                R.string.rime_sync_cleanup_failed_count,
                                failed,
                            )
                        }
                        toast(msg)
                    }
                }) { Text(stringResource(R.string.schema_manage_save)) }
            },
            dismissButton = {
                TextButton(onClick = { cleanupEntries = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 完整远程路径编辑的解析结果。 */
private enum class PathEditOutcome { OK, INVALID, SERVER_CHANGED }

/**
 * 把编辑后的完整远程路径拆回偏好：
 * - 末尾必须为当前目录名（URL 编码后），否则 INVALID；
 * - 倒数第二段为文件夹名，须单段合法，否则 INVALID；
 * - 剩余前缀必须为当前服务器（或其下路径），否则 SERVER_CHANGED
 *   （服务器地址改动请到用户词典页，那里连同账号密码一起改）。
 * 成功时写回 syncPath 与文件夹名。
 */
private fun applyEditedRemotePath(context: Context, input: String): PathEditOutcome {
    val text = input.trim().trimEnd('/')
    val dir = SchemeDirs.currentDirName(context)
    val encodedDir = URLEncoder.encode(dir, Charsets.UTF_8.name()).replace("+", "%20")
    if (!text.endsWith("/$encodedDir")) return PathEditOutcome.INVALID
    val head = text.removeSuffix("/$encodedDir")
    val slash = head.lastIndexOf('/')
    if (slash < 0) return PathEditOutcome.INVALID
    val root = head.substring(0, slash)
    val uuid = head.substring(slash + 1)
    if (!RimeSyncPrefs.isValidSyncFolderName(uuid)) return PathEditOutcome.INVALID
    val server = UserDictPrefs.normalizedServer()
    if (root != server && !root.startsWith("$server/")) {
        return PathEditOutcome.SERVER_CHANGED
    }
    val syncPath = if (root == server) "" else root.removePrefix("$server/")
    UserDictPrefs.syncPath = syncPath
    RimeSyncPrefs.setSyncFolderUuid(context, uuid)
    return PathEditOutcome.OK
}
