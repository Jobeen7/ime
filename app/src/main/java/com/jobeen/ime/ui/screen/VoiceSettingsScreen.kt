package com.jobeen.ime.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.base.speech.ModelDownloader
import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.ui.screen.ScreenComponent.ActionRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.ProgressButton
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    autoDownload: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var modelReady by remember { mutableStateOf(SherpaSpeechClient.isModelReady(context)) }
    var downloadState by remember { mutableStateOf<DownloadUiState>(DownloadUiState.Idle) }
    var updateCheckState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var downloadBtnWidth by remember { mutableStateOf(0.dp) }
    var progressFraction by remember { mutableStateOf(0f) }
    var isExtracting by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val checkUpdate: () -> Unit = check@{
        if (updateCheckState is UpdateCheckState.Checking) return@check
        updateCheckState = UpdateCheckState.Checking
        scope.launch {
            updateCheckState = when (SherpaSpeechClient.checkModelUpdate(context)) {
                ModelDownloader.UpdateCheckResult.HasUpdate -> UpdateCheckState.HasUpdate
                ModelDownloader.UpdateCheckResult.UpToDate -> UpdateCheckState.UpToDate
                ModelDownloader.UpdateCheckResult.Failed -> UpdateCheckState.CheckFailed
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.voice_settings),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SettingsGroup(
                title = stringResource(R.string.voice_model_group),
            ) {
                ActionRow(
                    title = stringResource(R.string.voice_recognition_model),
                    subtitle = if (modelReady) {
                        stringResource(R.string.voice_model_ready)
                    } else {
                        stringResource(R.string.voice_model_not_ready)
                    },
                    trailing = {
                        // 未下载 → 下载按钮；已就绪 → 检查更新流程
                        val checkButton = @Composable { labelRes: Int, onClick: () -> Unit, enabled: Boolean ->
                            Button(
                                onClick = onClick,
                                enabled = enabled,
                                modifier = Modifier
                                    .height(32.dp)
                                    .onSizeChanged {
                                        downloadBtnWidth = with(density) { it.width.toDp() }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    stringResource(labelRes),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                        when {
                            downloadState is DownloadUiState.Downloading -> ProgressButton(
                                progressFraction,
                                isExtracting,
                                downloadBtnWidth,
                            )

                            !modelReady -> checkButton(R.string.download, startDownload, true)

                            else -> when (updateCheckState) {
                                UpdateCheckState.Idle,
                                UpdateCheckState.CheckFailed,
                                -> checkButton(R.string.check_update, checkUpdate, true)

                                UpdateCheckState.Checking ->
                                    checkButton(R.string.schema_update_checking, {}, false)

                                // 查到新版才显示下载
                                UpdateCheckState.HasUpdate ->
                                    checkButton(R.string.download, startDownload, true)

                                UpdateCheckState.UpToDate -> Text(
                                    stringResource(R.string.update_latest),
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
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
            }
            // 语音热词状态：词表文件由键盘主进程生成，这里只读展示，
            // 让“热词到底有没有生效”在屏幕上可查，而不是黑盒。
            var hotwords by remember { mutableStateOf<List<String>?>(null) }
            var showHotwords by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                hotwords = withContext(Dispatchers.IO) {
                    runCatching {
                        val f = com.jobeen.ime.base.speech.SpeechHotwords.file(context)
                        if (f.isFile) f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                        else emptyList()
                    }.getOrDefault(emptyList())
                }
            }
            SettingsGroup(
                title = stringResource(R.string.voice_hotwords_group),
            ) {
                ActionRow(
                    title = stringResource(R.string.voice_hotwords_title),
                    subtitle = when (val hw = hotwords) {
                        null -> "…"
                        else -> if (hw.isEmpty()) {
                            stringResource(R.string.voice_hotwords_missing)
                        } else {
                            stringResource(R.string.voice_hotwords_ready, hw.size)
                        }
                    },
                    trailing = {
                        if (!hotwords.isNullOrEmpty()) {
                            Button(
                                onClick = { showHotwords = true },
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(stringResource(R.string.voice_hotwords_view), fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    },
                )
                // 解码方式三档：逐档隔离"带热词后语音无反应"的子嫌疑
                var decodeMode by remember {
                    mutableStateOf(com.jobeen.ime.base.speech.SpeechHotwords.readMode(context))
                }
                Text(
                    stringResource(R.string.voice_decode_mode_title),
                    fontSize = barFontSize,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                val modeScope = rememberCoroutineScope()
                val modeOptions = listOf(
                    com.jobeen.ime.base.speech.SpeechHotwords.DecodeMode.GREEDY to
                        R.string.voice_decode_greedy,
                    com.jobeen.ime.base.speech.SpeechHotwords.DecodeMode.BEAM to
                        R.string.voice_decode_beam,
                    com.jobeen.ime.base.speech.SpeechHotwords.DecodeMode.BEAM_HOTWORDS to
                        R.string.voice_decode_beam_hotwords,
                )
                for ((mode, labelRes) in modeOptions) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                decodeMode = mode
                                modeScope.launch(Dispatchers.IO) {
                                    com.jobeen.ime.base.speech.SpeechHotwords.writeMode(context, mode)
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = decodeMode == mode,
                            onClick = null,
                        )
                        Text(stringResource(labelRes), fontSize = 14.sp)
                    }
                }
            }
            // 诊断信息：模型文件构成 + 最近一次引擎构造结果（服务写入
            // 的状态文件），让语音异常在屏幕上可查，不再黑盒试错。
            var modelFilesDesc by remember { mutableStateOf<String?>(null) }
            var engineStatus by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                val (filesDesc, status) = withContext(Dispatchers.IO) {
                    val dir = com.jobeen.ime.data.App.speechModelDir
                    val names = dir.listFiles().orEmpty().filter { it.isFile }
                    fun hasSet(ext: String) = listOf("encoder", "decoder", "joiner").all { comp ->
                        names.any {
                            it.extension.equals(ext, true) &&
                                it.nameWithoutExtension.contains(comp, true)
                        }
                    }
                    val desc = when {
                        hasSet("bin") && hasSet("onnx") -> "both"
                        hasSet("bin") -> "qnn"
                        hasSet("onnx") -> "cpu"
                        else -> "none"
                    }
                    desc to com.jobeen.ime.base.speech.SpeechHotwords.readEngineStatus(context)
                }
                modelFilesDesc = filesDesc
                engineStatus = status
            }
            SettingsGroup(
                title = stringResource(R.string.voice_diag_group),
            ) {
                ActionRow(
                    title = stringResource(R.string.voice_model_files_title),
                    subtitle = when (modelFilesDesc) {
                        "both" -> stringResource(R.string.voice_files_both)
                        "qnn" -> stringResource(R.string.voice_files_qnn_only)
                        "cpu" -> stringResource(R.string.voice_files_cpu_only)
                        "none" -> stringResource(R.string.voice_files_none)
                        else -> "…"
                    },
                    trailing = {},
                )
                ActionRow(
                    title = stringResource(R.string.voice_engine_status_title),
                    subtitle = engineStatus ?: stringResource(R.string.voice_engine_status_none),
                    trailing = {},
                )
            }
            if (showHotwords) {
                val hw = hotwords.orEmpty()
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showHotwords = false },
                    title = { Text(stringResource(R.string.voice_hotwords_dialog_title, hw.size)) },
                    text = {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.height(360.dp),
                        ) {
                            items(hw.size) { i ->
                                Text(hw[i], fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { showHotwords = false }) {
                            Text(stringResource(R.string.voice_hotwords_close))
                        }
                    },
                )
            }
        }
    }
}
