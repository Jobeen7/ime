package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.BuildConfig
import com.jobeen.ime.R
import com.jobeen.ime.base.log.AppLogBuffer
import com.jobeen.ime.base.net.UpdateCheckResult
import com.jobeen.ime.base.net.VersionChecker
import com.jobeen.ime.base.update.AppUpdateManager
import com.jobeen.ime.data.manager.AppReset
import com.jobeen.ime.ui.MainActivity
import com.jobeen.ime.base.log.DiagExport
import timber.log.Timber
import com.jobeen.ime.ui.screen.ScreenComponent.ActionRow
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.ResetConfirmDialogs
import com.jobeen.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLogs: () -> Unit = {}, onOpenDiagnostics: () -> Unit = {}) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }
    val versionCode = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
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
    val msgDebugUnsupported = stringResource(R.string.debug_update_unsupported)
    val msgDiagFailed = stringResource(R.string.about_diag_failed)
    val msgDiagEmpty = stringResource(R.string.about_diag_empty)
    val diagShareTitle = stringResource(R.string.about_diag_share)
    val msgResetFailed = stringResource(R.string.about_reset_failed)
    var resetStep by remember { mutableStateOf(0) }
    var resetting by remember { mutableStateOf(false) }

    if (showLicenses) {
        LicenseScreen(onBack = { showLicenses = false })
        return
    }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 重置执行：成功后以新任务重进设置 home（旧栈清掉，偏好重读默认值）。 */
    fun doReset() {
        if (resetting) return
        resetting = true
        scope.launch {
            val result = AppReset.resetToInitialState(context)
            resetting = false
            result.onSuccess {
                val intent = android.content.Intent(context, MainActivity::class.java).apply {
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

    /** 诊断导出：本进程 Timber 日志 + 应用/设备头，打包成 zip 分享。
     * 失败时把断点（步骤 + 异常类）写进日志缓冲（元数据级，无用户内容），
     * 下次导出成功时可直接从日志里看到上次断在哪一步；用户侧仍只出 Toast。
     * 日志与崩溃记录都为空时不分享空包，只出 Toast（正式版曾因缓冲
     * 未装而恒为空包）。崩溃记录（CrashTrap）附在正文末尾，不经日志缓冲。
     * 调用方在页面协程里调（scope 为主线程）：写 zip 在 IO 上下文跑，
     * 完成后回主线程起分享 / 弹提示（沿用本页 doReset 的协程体例）。 */
    suspend fun exportDiag() {
        val entries = AppLogBuffer.entries.value
        val pendingCrash = com.jobeen.ime.base.CrashTrap.readPendingCrashInfo(context)
        if (entries.isEmpty() && pendingCrash == null) {
            toast(msgDiagEmpty)
            return
        }
        var step = "build-txt"
        runCatching {
            val now = java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                java.util.Locale.getDefault(),
            ).format(java.util.Date())
            var txt = DiagExport.buildDiagnosticText(
                versionName = versionName,
                packageName = context.packageName,
                androidRelease = android.os.Build.VERSION.RELEASE,
                sdkInt = android.os.Build.VERSION.SDK_INT,
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                now = now,
                entries = entries,
            )
            if (pendingCrash != null) {
                // 导出侧同样截断：新记录写侧已 cap，但旧版落盘的超长记录仍可能被导出。
                txt += "\n\n=== 最近崩溃 ===\n" +
                    com.jobeen.ime.base.truncateCrashText(pendingCrash.text)
            }
            step = "write-zip"
            // 落盘必须进 cacheDir/shared/（file_paths.xml 唯一覆盖诊断 zip 的根）；
            // 旧实现写 cacheDir 根目录，getUriForFile 恒抛 IllegalArgumentException。
            val zipFile = withContext(Dispatchers.IO) {
                DiagExport.writeDiagZip(
                    DiagExport.diagZipFile(context.cacheDir, now),
                    txt,
                )
            }
            step = "get-uri"
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile,
            )
            step = "start-share"
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(
                    intent,
                    diagShareTitle,
                ),
            )
        }.onFailure { e ->
            Timber.tag("DiagExport").e(e, "DiagExport failed at $step: ${e::class.java.simpleName}")
            toast(msgDiagFailed)
        }
    }

    // 尝试安装：缺「安装未知应用」授权时先去设置，文件留着，用户回来再点即可
    // （不再本地验签：下载时已校验大小与登记摘要，签名不一致由系统
    // 安装器拒绝，见 Q12）
    fun tryInstall() {
        val file = downloadedFile ?: return
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

    /** 检查更新（顶部按钮用；行内逻辑与旧版一致）。 */
    fun checkUpdate() {
        if (BuildConfig.DEBUG) {
            toast(msgDebugUnsupported)
            return
        }
        if (checkingUpdate) return
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
                                ),
                            )
                        }.onFailure { toast(msgCheckFailed) }
                    } else {
                        updateInfo = result
                    }
                }
                UpdateCheckResult.Latest -> {
                    // 下载进行中不清除：卡片内含下载进度行与安装按钮，
                    // 清掉会藏起进行中的下载（检查按钮在下载时仍可用）。
                    if (!downloading) updateInfo = null
                    toast(msgLatest)
                }
                UpdateCheckResult.Failed -> {
                    if (!downloading) updateInfo = null
                    toast(msgCheckFailed)
                }
            }
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.about_us),
        onBack = onBack,
    ) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = LocalMonetCardColor.current,
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
                        text = stringResource(R.string.hero_app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.about_version_pill,
                                versionName,
                                versionCode,
                            ),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 顶部：简介行 + 检查更新按钮（上移）。
                    // logo 保持现有透明底（ic_about_logo 本身无底卡，不加实色底）。
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.about_description_text),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { checkUpdate() },
                        enabled = !checkingUpdate && !BuildConfig.DEBUG,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 20.dp,
                            vertical = 10.dp,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (checkingUpdate) {
                                stringResource(R.string.checking_update)
                            } else {
                                stringResource(R.string.check_update)
                            },
                            fontSize = rowSubFontSize,
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = msgDebugUnsupported,
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 查到新版后在行内内联展开（随检查更新按钮移到顶部，逻辑不变）：
            // 一行版本信息 + 操作按钮，下载中时下方再出一行进度条。
            // 只有点「检查更新」才会走到这里。
            updateInfo?.let { info ->
                GroupCard {
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

            Spacer(Modifier.height(8.dp))

            // 平铺列表（不分组）：一张卡内依次为导出诊断日志、
            // 重置应用设置、开源许可。重置行无副文、页底无脚注；
            // 点击后弹出的二次确认警告保留（ResetConfirmDialogs）。
            // 运行日志组已按 B 删掉，只留导出诊断日志。
            GroupCard {
                NavRow(
                    title = stringResource(R.string.about_diag_export),
                    onClick = { scope.launch { exportDiag() } },
                )
                NavRow(
                    title = stringResource(R.string.skin_diag_title),
                    onClick = onOpenDiagnostics,
                )
                NavRow(
                    title = stringResource(R.string.about_reset),
                    onClick = { if (!resetting) resetStep = 1 },
                    titleColor = MaterialTheme.colorScheme.error,
                )
                NavRow(
                    title = stringResource(R.string.licenses_index),
                    onClick = { showLicenses = true },
                )
            }
            if (resetting) {
                Text(
                    text = stringResource(R.string.about_reset_busy),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

    }

    // 重置二次确认：两次都只有取消 / 继续，绝不加“不再询问”。
    ResetConfirmDialogs(
        step = resetStep,
        onStepChange = { resetStep = it },
        onConfirmed = { doReset() },
    )

}
