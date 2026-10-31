package com.jobeen.ime.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jobeen.ime.R
import com.jobeen.ime.base.speech.ModelDownloader
import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.base.speech.VoiceCorrectionStore
import com.jobeen.ime.base.speech.VoiceInputSwitchStore
import com.jobeen.ime.base.speech.cloud.BuiltinPlugins
import com.jobeen.ime.base.speech.cloud.KeystoreSecretStore
import com.jobeen.ime.base.speech.cloud.PluginStore
import com.jobeen.ime.base.speech.cloud.SharedPrefsStore
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow
import java.io.File
import com.jobeen.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed interface DownloadUiState {
    data object Idle : DownloadUiState
    data class Downloading(
        val fileIndex: Int = 0,
        val fileCount: Int = 1,
        val progress: Float = 0f,
    ) : DownloadUiState

    data object Failed : DownloadUiState
}

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object HasUpdate : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data object CheckFailed : UpdateCheckState
    data object Unverified : UpdateCheckState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    autoDownload: Boolean = false,
    /** 点在线服务商进插件页时带插件 id；普通打开插件页时传 null。 */
    onOpenCloudVoicePlugins: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    var modelReady by remember { mutableStateOf(SherpaSpeechClient.isModelReady(context)) }
    var downloadState by remember { mutableStateOf<DownloadUiState>(DownloadUiState.Idle) }
    var updateCheckState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var progressFraction by remember { mutableStateOf(0f) }
    var isExtracting by remember { mutableStateOf(false) }

    // 语音专名纠错：与输入法服务同文件、同进程的词对存储实例
    // （开关与清除经 Store 的跨实例同步即时作用到服务侧实例）
    val correctionStore = remember {
        VoiceCorrectionStore.shared(File(context.filesDir, VoiceCorrectionStore.FILE_NAME))
    }
    var correctionEnabled by remember { mutableStateOf(correctionStore.enabled) }
    var correctionsCleared by remember { mutableStateOf(false) }

    // 语音输入总开关：默认开；关闭后两个语音启动入口均不启动
    // （空格长按、工具栏语音键、自定义语音按钮同经该口）。
    val voiceSwitchStore = remember {
        VoiceInputSwitchStore.shared(File(context.filesDir, VoiceInputSwitchStore.FILE_NAME))
    }
    var voiceEnabled by remember { mutableStateOf(voiceSwitchStore.enabled) }

    // 识别服务商：与云端插件管理页同一份偏好（cloud_voice）＋同一插件库；
    // 本页只做选择＋状态，不重复造管理页（导入／密钥／设置仍进管理页）。
    val pluginPrefs = remember { SharedPrefsStore(context, PluginStore.PREFS_NAME) }
    val pluginStore = remember { PluginStore(context.filesDir, pluginPrefs) }
    val secretStore = remember { KeystoreSecretStore(pluginPrefs) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var plugins by remember { mutableStateOf(pluginStore.list()) }
    var preferred by remember { mutableStateOf(pluginStore.getPreferred()) }
    var optIn by remember { mutableStateOf(pluginStore.isOptIn()) }
    var secretsMissingIds by remember {
        mutableStateOf(pluginSecretsMissingIds(plugins) { id, field -> secretStore.has(id, field) })
    }
    var providerDialog by remember { mutableStateOf(false) }
    var optInConfirmTarget by remember { mutableStateOf<String?>(null) }

    fun refreshProviderState() {
        plugins = pluginStore.list()
        preferred = pluginStore.getPreferred()
        optIn = pluginStore.isOptIn()
        secretsMissingIds = pluginSecretsMissingIds(plugins) { id, field -> secretStore.has(id, field) }
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    val scope = rememberCoroutineScope()

    val checkUpdate: () -> Unit = check@{
        if (updateCheckState is UpdateCheckState.Checking) return@check
        updateCheckState = UpdateCheckState.Checking
        scope.launch {
            updateCheckState = when (SherpaSpeechClient.checkModelUpdate(context)) {
                ModelDownloader.UpdateCheckResult.HasUpdate -> UpdateCheckState.HasUpdate
                ModelDownloader.UpdateCheckResult.UpToDate -> UpdateCheckState.UpToDate
                ModelDownloader.UpdateCheckResult.Failed -> UpdateCheckState.CheckFailed
                ModelDownloader.UpdateCheckResult.Unverified -> UpdateCheckState.Unverified
            }
        }
    }

    val startDownload: () -> Unit = download@{
        // 下载中禁止重复触发；失败后允许重试、已就绪时允许重新下载
        if (downloadState is DownloadUiState.Downloading) return@download
        downloadState = DownloadUiState.Downloading()
        progressFraction = 0f
        isExtracting = false
        scope.launch {
            val ok = runCatching {
                SherpaSpeechClient.downloadModel(
                    context = context,
                    onProgress = { p ->
                        scope.launch(Dispatchers.Main.immediate) {
                            val fileFraction = if (p.total > 0) {
                                p.downloaded.toFloat() / p.total.toFloat()
                            } else {
                                0f
                            }
                            progressFraction =
                                ((p.fileIndex - 1) + fileFraction) / p.fileCount.coerceAtLeast(1)
                        }
                    },
                    onExtract = { current, total ->
                        scope.launch(Dispatchers.Main.immediate) {
                            isExtracting = true
                            progressFraction =
                                if (total > 0) current.toFloat() / total.toFloat() else 0f
                        }
                    },
                )
            }.getOrElse { e ->
                Timber.e(e, "Speech model download failed")
                false
            }
            progressFraction = 0f
            isExtracting = false
            if (ok) {
                modelReady = SherpaSpeechClient.isModelReady(context)
                // 刚从服务端下载的一定是最新版
                updateCheckState = UpdateCheckState.UpToDate
                SherpaSpeechClient.initialize(context)
                downloadState = DownloadUiState.Idle
            } else {
                downloadState = DownloadUiState.Failed
            }
        }
    }

    LaunchedEffect(Unit) {
        if (autoDownload && !modelReady) startDownload()
    }

    // 内置插件首次打开即种子安装（与管理页同口径），装完刷新列表。
    LaunchedEffect(Unit) {
        val changed = withContext(Dispatchers.IO) {
            runCatching {
                BuiltinPlugins.ensure(pluginStore, { name ->
                    runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()
                }, secretStore)
            }.getOrDefault(emptyList())
        }
        if (changed.isNotEmpty()) refreshProviderState()
    }

    // 从权限页／插件管理页返回时刷新：权限状态与服务商选择都可能已变。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshProviderState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.voice_settings),
        onBack = onBack,
    ) {
        // Hub（2026-10-29 22:04 Lee 口述）：识别服务与模型卡＋
        // “语音输入”小标题＋首选语音输入法卡；点识别服务与模型
        // 卡进细则页（不再直接进对话框），点首选卡进对话框。
        // 顶部两张卡（参考结构）：识别服务与模型、首选语音输入法；
        // 均取现有首选服务商状态（两态共用，故定义在分支外）。
        val preferredId = preferred
        val preferredMeta = preferredId?.let { id -> plugins.singleOrNull { it.id == id } }
        val providerDisplay = when {
            preferredId == null -> stringResource(R.string.voice_provider_local)
            preferredMeta != null -> builtinDisplayName(preferredMeta.id)?.let {
                stringResource(it)
            } ?: preferredMeta.id
            else -> preferredId
        }
        // 语音页（2026-10-29 23:52 视频行结构，hub／细则替掉后）：
        // 单页行结构，顺序＝启用开关（裸行）→麦克风权限行→
        // 首选语音输入行→本地语音模型行→JS语音插件行→专名纠错
        // （专名纠错为 23:32 决定 3“留”的推导落位：视频四行无处
        // 安放，故保留为第 5 块，仅保功能不增新功能，等否决）。
        // 行在同一张 GroupCard 内（标题＋副标题行式，分隔线按
        // Jime 统一装饰系统不画）；提示小字与用法说明保留在行间／页底。

        // 裸行（2026-10-29 23:00 Lee 裁图）：标题＋开关，无卡片、无行内说明。
        SwitchRow(
            title = stringResource(R.string.voice_input_enable),
            checked = voiceEnabled,
            onCheckedChange = {
                voiceEnabled = it
                voiceSwitchStore.setEnabled(it)
            },
        )
        Spacer(Modifier.height(12.dp))
        GroupCard {
            // 麦克风权限行（2026-10-29 23:37 推导）：无副标题，状态进标题
            // 文字；未授权点行进权限页，已授权不点按；回来刷新不变
            // （ON_RESUME 的 refreshProviderState 已覆盖）。
            if (micGranted) {
                VoiceStaticRow(title = stringResource(R.string.voice_mic_on))
            } else {
                NavRow(
                    title = stringResource(R.string.voice_mic_grant),
                    onClick = {
                        val intent = Intent(
                            context,
                            com.jobeen.ime.base.speech.SpeechPermissionActivity::class.java,
                        )
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
            NavRow(
                title = stringResource(R.string.voice_preferred_input),
                subtitle = if (preferredId == null) {
                    stringResource(R.string.voice_preferred_unset)
                } else {
                    providerDisplay
                },
                onClick = { providerDialog = true },
            )
        val providerHint = voiceProviderHint(
            preferredId = preferredId,
            modelReady = modelReady,
            optIn = optIn,
            secretsMissingIds = secretsMissingIds,
            preferredInstalled = preferredId == null || preferredMeta != null,
        )
        if (providerHint != VoiceProviderHint.NONE) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when (providerHint) {
                        VoiceProviderHint.NO_USABLE_ENGINE -> R.string.voice_provider_hint_no_engine
                        VoiceProviderHint.PLUGIN_UNAVAILABLE -> R.string.voice_provider_hint_unavailable
                        VoiceProviderHint.CLOUD_NOT_ENABLED -> R.string.voice_provider_hint_not_enabled
                        VoiceProviderHint.PLUGIN_SECRETS_MISSING -> R.string.voice_provider_hint_keys
                        VoiceProviderHint.NONE -> R.string.voice_provider_hint_no_engine
                    },
                ),
                fontSize = rowSubFontSize,
                color = if (providerHint == VoiceProviderHint.CLOUD_NOT_ENABLED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        // 本地语音模型行：点按即原模型卡动作（未下载／有新版→下载，
        // 未设值／检查失败／未验证→检查更新，下载中／检查中／已是最新
        // →无动作）；状态放副标题。
        val modelDownloading = downloadState is DownloadUiState.Downloading
        val modelSubtitle = when {
            modelDownloading -> stringResource(R.string.downloading) +
                " ${(progressFraction * 100).toInt()}%"
            updateCheckState is UpdateCheckState.HasUpdate ->
                stringResource(R.string.update_available)
            updateCheckState is UpdateCheckState.UpToDate ->
                stringResource(R.string.update_latest)
            modelReady -> stringResource(R.string.voice_model_ready)
            else -> stringResource(R.string.voice_model_not_ready)
        }
        // when 分支体的大括号是语句块而非 lambda，故直接取函数值；
        // startDownload／checkUpdate 本身就是 () -> Unit 的 val。
        val onModelCardClick: (() -> Unit)? = when {
            modelDownloading -> null
            !modelReady -> startDownload
            updateCheckState is UpdateCheckState.HasUpdate -> startDownload
            updateCheckState is UpdateCheckState.Idle ||
                updateCheckState is UpdateCheckState.CheckFailed ||
                updateCheckState is UpdateCheckState.Unverified -> checkUpdate
            else -> null
        }
        val modelClick = onModelCardClick
        if (modelClick == null) {
            VoiceStaticRow(
                title = stringResource(R.string.voice_local_model),
                subtitle = modelSubtitle,
            )
        } else {
            NavRow(
                title = stringResource(R.string.voice_local_model),
                subtitle = modelSubtitle,
                onClick = { modelClick() },
            )
        }
        if (downloadState is DownloadUiState.Failed) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.voice_model_download_failed),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (updateCheckState is UpdateCheckState.CheckFailed) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.update_check_failed),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (updateCheckState is UpdateCheckState.Unverified) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.voice_model_update_unverified),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavRow(
            title = stringResource(R.string.voice_js_plugins),
            subtitle = stringResource(R.string.cloud_voice_plugins_desc),
            onClick = { onOpenCloudVoicePlugins(null) },
        )
            SwitchRow(
                title = stringResource(R.string.voice_correction_enable),
                checked = correctionEnabled,
                onCheckedChange = {
                    correctionEnabled = it
                    correctionStore.setEnabled(it)
                },
                showDivider = true,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        correctionStore.clear()
                        correctionsCleared = true
                    }
                    .padding(vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.voice_correction_clear_pairs),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (correctionsCleared) {
                            stringResource(R.string.voice_correction_cleared)
                        } else {
                            stringResource(R.string.voice_correction_clear_desc)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.voice_usage),
            fontSize = rowSubFontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
            if (providerDialog) {
                // 服务商选择对话框：Jime 通用对话框样式（深色跟随 Jime 深色主题），
                // 在线／本地离线两组（本地只放自带模型＋远程三家）；行副标题＝状态＋真实能力标签（不搬对方标签集）。
                SettingsDialog(
                    onDismissRequest = { providerDialog = false },
                    title = stringResource(R.string.voice_provider),
                    body = {
                        // 标签文案先在组合作用域解析成表（变换 lambda 内
                        // 不可调组合函数），行内只做纯查表拼接。
                        val tagStrings = mapOf(
                            VoiceProviderTag.LOCAL to
                                stringResource(R.string.voice_tag_local),
                            VoiceProviderTag.ONLINE to
                                stringResource(R.string.voice_tag_online),
                            VoiceProviderTag.STREAMING to
                                stringResource(R.string.voice_tag_streaming),
                            VoiceProviderTag.BUILTIN to
                                stringResource(R.string.cloud_voice_builtin),
                            VoiceProviderTag.CUSTOM to
                                stringResource(R.string.voice_tag_custom),
                        )
                        val allProviderRows = voiceProviderRows(
                            modelReady = modelReady,
                            plugins = plugins,
                            preferredId = preferred,
                            secretsMissingIds = secretsMissingIds,
                        )
                        val (onlineProviderRows, localProviderRows) =
                            partitionProviderDialogRows(allProviderRows)
                        @Composable
                        fun ProviderRow(row: VoiceProviderRow) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (row.isLocal) {
                                                pluginStore.setPreferred(null)
                                                refreshProviderState()
                                                providerDialog = false
                                            } else if (row.selected) {
                                                // 已选在线行：点按即进该插件页（输 key／改设置）。
                                                providerDialog = false
                                                onOpenCloudVoicePlugins(row.pluginId)
                                            } else if (!optIn) {
                                                // 选云端插件且开启开关关着：先经隐私确认再同时开开关＋设首选，
                                                // 不静默改动用户未确认过的云端 consent；确认后进插件页。
                                                optInConfirmTarget = row.pluginId
                                                providerDialog = false
                                            } else {
                                                pluginStore.setPreferred(row.pluginId)
                                                refreshProviderState()
                                                providerDialog = false
                                                onOpenCloudVoicePlugins(row.pluginId)
                                            }
                                        }
                                        .padding(vertical = 12.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = if (row.isLocal) {
                                                stringResource(R.string.voice_provider_local)
                                            } else {
                                                builtinDisplayName(row.key)?.let {
                                                    stringResource(it)
                                                } ?: row.key
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        val tagText = voiceProviderTags(
                                            isLocal = row.isLocal,
                                            isBuiltin = !row.isLocal &&
                                                BuiltinPlugins.isBuiltin(row.key),
                                        ).joinToString(" · ") { tag -> tagStrings.getValue(tag) }
                                        Text(
                                            text = stringResource(
                                                when (row.status) {
                                                    VoiceProviderStatus.LOCAL_READY ->
                                                        R.string.voice_model_ready
                                                    VoiceProviderStatus.LOCAL_NOT_DOWNLOADED ->
                                                        R.string.voice_model_not_ready
                                                    VoiceProviderStatus.PLUGIN_READY ->
                                                        R.string.voice_provider_plugin_ready
                                                    VoiceProviderStatus.PLUGIN_SECRETS_MISSING ->
                                                        R.string.cloud_voice_secret_missing
                                                },
                                            ) + " · " + tagText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (row.selected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                        }
                        Column {
                            if (onlineProviderRows.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.voice_tag_online),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                )
                                onlineProviderRows.forEach { ProviderRow(it) }
                            }
                            if (localProviderRows.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.voice_dialog_group_local),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                                )
                                localProviderRows.forEach { ProviderRow(it) }
                            }
                        }
                    },
                    actions = listOf(
                        SettingsDialogAction(
                            text = stringResource(R.string.cancel),
                            onClick = { providerDialog = false },
                        ),
                    ),
                )
            }
            val confirmTarget = optInConfirmTarget
            if (confirmTarget != null) {
                SettingsConfirmDialog(
                    onDismissRequest = { optInConfirmTarget = null },
                    title = stringResource(R.string.voice_provider_cloud_confirm_title),
                    text = stringResource(R.string.voice_provider_cloud_confirm_text),
                    confirmText = stringResource(R.string.voice_provider_cloud_confirm_ok),
                    onConfirm = {
                        optInConfirmTarget = null
                        pluginStore.setOptIn(true)
                        pluginStore.setPreferred(confirmTarget)
                        refreshProviderState()
                        onOpenCloudVoicePlugins(confirmTarget)
                    },
                    dismissText = stringResource(R.string.cancel),
                    onDismiss = { optInConfirmTarget = null },
                )
            }
    }
}

/**
 * 不可点按的行（与 NavRow 同一套字号／纵向留白，仅无点按）：
 * 用于已授权的权限行（状态即标题）与无动作的本地模型行，
 * 避免出现点按无反应的死涟漪。
 */
@Composable
private fun VoiceStaticRow(
    title: String,
    subtitle: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
