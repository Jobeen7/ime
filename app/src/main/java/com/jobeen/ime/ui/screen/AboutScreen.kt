package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.base.net.UpdateCheckResult
import com.jobeen.ime.base.net.VersionChecker
import com.jobeen.ime.base.update.AppUpdateManager
import com.jobeen.ime.ui.screen.ScreenComponent.ActionRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLogs: () -> Unit = {}) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 自更新内联状态（查到新版后在「版本更新」组内展开，不弹对话框）
    var updateInfo by remember { mutableStateOf<UpdateCheckResult.Available?>(null) }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadPercent by remember { mutableStateOf(0) }
    var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }
    // 协程内不可用 stringResource，提前在组合阶段解析
    val msgCheckFailed = stringResource(R.string.update_check_failed)
    val msgAvailable = stringResource(R.string.update_available)
    val msgLatest = stringResource(R.string.update_latest)
    val msgDownloadFailed = stringResource(R.string.update_download_failed)
    val msgNeedPermission = stringResource(R.string.update_need_permission)
    val msgFileInvalid = stringResource(R.string.update_file_invalid)

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 尝试安装：缺「安装未知应用」授权时先去设置，文件留着，用户回来再点即可
    fun tryInstall() {
        val file = downloadedFile ?: return
        if (!AppUpdateManager.isInstallable(context, file)) {
            downloadedFile = null
            toast(msgFileInvalid)
            return
        }
        if (AppUpdateManager.needsInstallPermission(context)) {
            toast(msgNeedPermission)
            runCatching { AppUpdateManager.openInstallPermissionSettings(context) }
            return
        }
        runCatching { AppUpdateManager.installApk(context, file) }
            .onSuccess { updateInfo = null }
            .onFailure { toast(msgDownloadFailed) }
    }

    fun startDownload() {
        val info = updateInfo ?: return
        if (downloading) return
        downloading = true
        downloadPercent = 0
        downloadJob = scope.launch {
            val file = AppUpdateManager.downloadApk(
                context, info.apkUrl, info.apkName, info.apkSize, info.apkSha256,
            ) { downloaded, total ->
                downloadPercent = if (total > 0) {
                    (downloaded * 100 / total).toInt().coerceIn(0, 100)
                } else {
                    0
                }
            }
            downloading = false
            downloadJob = null
            if (file == null) {
                toast(msgDownloadFailed)
            } else {
                downloadedFile = file
                tryInstall()
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        AppUpdateManager.cancelDownload()
        downloadJob = null
        downloading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.about_us),
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
            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .clickable(onClick = onOpenLogs),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_about_logo),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier.size(100.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.about_description_text),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SettingsGroup(title = stringResource(R.string.about_update)) {
                ActionRow(
                    title = stringResource(R.string.current_version, versionName),
                    subtitle = null,
                    trailing = {
                        Button(
                            onClick = {
                                if (checkingUpdate) return@Button
                                checkingUpdate = true
                                scope.launch {
                                    val result = VersionChecker.check(versionName)
                                    checkingUpdate = false
                                    when (result) {
                                        is UpdateCheckResult.Available -> {
                                            if (result.apkUrl.isEmpty()) {
                                                // 发行版没有 APK 资产时退回旧流程：打开 Release 页
                                                toast(msgAvailable)
                                                runCatching {
                                                    context.startActivity(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            android.net.Uri.parse(result.pageUrl),
                                                        )
                                                    )
                                                }.onFailure { toast(msgCheckFailed) }
                                            } else {
                                                updateInfo = result
                                            }
                                        }
                                        UpdateCheckResult.Latest -> toast(msgLatest)
                                        UpdateCheckResult.Failed -> toast(msgCheckFailed)
                                    }
                                }
                            },
                            enabled = !checkingUpdate,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 10.dp,
                                vertical = 2.dp,
                            ),
                        ) {
                            Text(
                                text = if (checkingUpdate) {
                                    stringResource(R.string.checking_update)
                                } else {
                                    stringResource(R.string.check_update)
                                },
                                fontSize = rowSubFontSize,
                            )
                        }
                    },
                )

                // 查到新版后在组内内联展开（不弹对话框）：一行版本信息 + 操作按钮，
                // 下载中时下方再出一行进度条。只有点「检查更新」才会走到这里。
                updateInfo?.let { info ->
                    ActionRow(
                        title = stringResource(R.string.update_found_version, info.version),
                        subtitle = stringResource(
                            R.string.update_package_size,
                            android.text.format.Formatter.formatFileSize(context, info.apkSize),
                        ),
                        trailing = {
                            when {
                                downloading -> Text(
                                    stringResource(R.string.update_downloading, downloadPercent),
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                downloadedFile != null -> Button(
                                    onClick = { tryInstall() },
                                    modifier = Modifier.height(30.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 10.dp,
                                        vertical = 2.dp,
                                    ),
                                ) {
                                    Text(
                                        stringResource(R.string.update_install_now),
                                        fontSize = rowSubFontSize,
                                    )
                                }
                                else -> Button(
                                    onClick = { startDownload() },
                                    modifier = Modifier.height(30.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 10.dp,
                                        vertical = 2.dp,
                                    ),
                                ) {
                                    Text(
                                        stringResource(R.string.update_download_install),
                                        fontSize = rowSubFontSize,
                                    )
                                }
                            }
                        },
                        showDivider = true,
                    )
                    if (downloading) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { downloadPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                androidx.compose.material3.TextButton(
                                    onClick = { cancelDownload() },
                                ) {
                                    Text(
                                        stringResource(R.string.update_cancel_download),
                                        fontSize = rowSubFontSize,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

}
