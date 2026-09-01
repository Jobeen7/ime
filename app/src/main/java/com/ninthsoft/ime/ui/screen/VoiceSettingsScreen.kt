package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.speech.SherpaSpeechClient
import com.ninthsoft.ime.ui.screen.ScreenComponent.ActionRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.ProgressButton
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var downloadBtnWidth by remember { mutableStateOf(0.dp) }
    var progressFraction by remember { mutableStateOf(0f) }
    var isExtracting by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val startDownload: () -> Unit = download@{
        if (downloadState !is DownloadUiState.Idle) return@download
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
                        when {
                            modelReady -> Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )

                            downloadState is DownloadUiState.Downloading -> ProgressButton(
                                progressFraction,
                                isExtracting,
                                downloadBtnWidth,
                            )

                            else -> Button(
                                onClick = startDownload,
                                modifier = Modifier
                                    .height(32.dp)
                                    .onSizeChanged {
                                        downloadBtnWidth = with(density) { it.width.toDp() }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.download),
                                    fontSize = 13.sp,
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
            }
        }
    }
}
