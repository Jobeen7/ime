package com.jobeen.ime.ui.screen

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.R
import com.jobeen.ime.base.ngram.DownloadResult
import com.jobeen.ime.base.ngram.GramModelDownloader
import com.jobeen.ime.base.update.WanxiangUpdateManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.GroupDivider
import com.jobeen.ime.ui.screen.ScreenComponent.ProgressButton
import com.jobeen.ime.ui.screen.ScreenComponent.SectionHeader
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 方案更新独立页：语法模型下载 + 万象更新检查 + 本地/远程信息。
 * 机器与原方案页更新区同一套（WanxiangUpdateManager / GramModelDownloader
 * 的进程级状态流），只是换了落点：不再出现在方案页，改由设置 home 的
 * 「方案更新」行进入。本页逻辑与原更新区同口径（守门、结果消费标记、
 * 应用级作用域下载、更新后 reload 与复检）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeUpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updatePrefs = remember {
        context.getSharedPreferences(WanxiangUpdateManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    var grammarLanguage by remember { mutableStateOf<String?>(null) }
    var grammarReady by remember { mutableStateOf(false) }
    val grammarDownloading by GramModelDownloader.isRunning.collectAsStateWithLifecycle()
    val grammarProgress by GramModelDownloader.progress.collectAsStateWithLifecycle()
    val grammarResult by GramModelDownloader.lastResult.collectAsStateWithLifecycle()
    var grammarResultSeenAtMs by remember { mutableLongStateOf(0L) }
    val shownGrammarResult = grammarResult?.takeIf { it.finishedAtMs > grammarResultSeenAtMs }
    val grammarFailed = !grammarReady && !grammarDownloading &&
        shownGrammarResult?.result == DownloadResult.FAILED
    var grammarPending by remember { mutableStateOf(false) }

    var updateInfo by remember { mutableStateOf<WanxiangUpdateManager.UpdateInfo?>(null) }
    val updating by WanxiangUpdateManager.isRunning.collectAsStateWithLifecycle()
    val updateProgress by WanxiangUpdateManager.progress.collectAsStateWithLifecycle()
    val updateResult by WanxiangUpdateManager.lastResult.collectAsStateWithLifecycle()
    var updateResultSeenAtMs by remember { mutableLongStateOf(0L) }
    val shownUpdateResult = updateResult?.takeIf { it.finishedAtMs > updateResultSeenAtMs }
    val updateDone = shownUpdateResult?.result == DownloadResult.SUCCESS
    val updateFailed = shownUpdateResult?.result == DownloadResult.FAILED
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateCheckFailed by remember { mutableStateOf(false) }
    var updatePending by remember { mutableStateOf(false) }
    var localInfo by remember { mutableStateOf<WanxiangUpdateManager.LocalInfo?>(null) }
    var currentSchemaName by remember { mutableStateOf("") }

    val stageDownloadingSchema = stringResource(R.string.schema_update_downloading_schema)
    val stageExtracting = stringResource(R.string.schema_update_extracting)
    val stageDownloadingGram = stringResource(R.string.schema_update_downloading_gram)
    val stageDeploying = stringResource(R.string.schema_update_deploying)
    val updateStageText = when (updateProgress?.stage) {
        WanxiangUpdateManager.UpdateProgress.Stage.SCHEMA_DOWNLOAD -> stageDownloadingSchema
        WanxiangUpdateManager.UpdateProgress.Stage.DICTS_EXTRACT -> stageExtracting
        WanxiangUpdateManager.UpdateProgress.Stage.GRAM_DOWNLOAD -> stageDownloadingGram
        null -> ""
    }

    fun downloadGrammar(language: String) {
        if (grammarPending || updatePending) return
        if (GramModelDownloader.isRunning.value || WanxiangUpdateManager.isRunning.value) return
        grammarPending = true
        grammarResultSeenAtMs = System.currentTimeMillis()
        val workScope =
            (context.applicationContext as? ImeApplication)?.applicationScope ?: scope
        workScope.launch {
            try {
                if (GramModelDownloader.download(language) == DownloadResult.SUCCESS) {
                    EngineFactory.current()?.reload()
                }
            } finally {
                grammarPending = false
            }
        }
    }

    fun checkWanxiangUpdate() {
        if (updatePending || checkingUpdate) return
        if (WanxiangUpdateManager.isRunning.value) return
        WanxiangUpdateManager.ensureVersionHousekeeping(context)
        checkingUpdate = true
        updateResultSeenAtMs = System.currentTimeMillis()
        updateCheckFailed = false
        scope.launch {
            val result = WanxiangUpdateManager.checkForUpdates(updatePrefs)
            checkingUpdate = false
            result.onSuccess { info ->
                updateInfo = info
            }.onFailure {
                updateCheckFailed = true
            }
        }
    }

    fun startWanxiangUpdate(info: WanxiangUpdateManager.UpdateInfo) {
        if (updatePending || grammarPending) return
        if (WanxiangUpdateManager.isRunning.value || GramModelDownloader.isRunning.value) return
        updatePending = true
        updateResultSeenAtMs = System.currentTimeMillis()
        updateCheckFailed = false
        val workScope =
            (context.applicationContext as? ImeApplication)?.applicationScope ?: scope
        workScope.launch {
            try {
                val result = WanxiangUpdateManager.downloadAndApply(
                    updatePrefs,
                    info,
                    context.cacheDir,
                )
                if (result == DownloadResult.SUCCESS) {
                    EngineFactory.current()?.reload()
                    WanxiangUpdateManager.checkForUpdates(updatePrefs)
                        .onSuccess { refreshed ->
                            updateInfo = refreshed
                        }
                        .onFailure {
                            updateCheckFailed = true
                        }
                    localInfo = WanxiangUpdateManager.getLocalInfo(updatePrefs)
                }
            } finally {
                updatePending = false
            }
        }
    }

    LaunchedEffect(Unit) {
        WanxiangUpdateManager.ensureVersionHousekeeping(context)
        localInfo = WanxiangUpdateManager.getLocalInfo(updatePrefs)
        val detected = withContext(Dispatchers.IO) {
            runCatching {
                Rime.runOnRimeMain {
                    val currentSchema = currentSchema()
                    val language = RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                        config.getString("grammar/language")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    language to currentSchema.schemaId
                }
            }.getOrNull()
        }
        grammarLanguage = detected?.first
        val schemaId = detected?.second
        if (schemaId != null) {
            // 变体是目录（方案包）的属性：先按当前目录内容判定；
            // Base 与 Lite 共用 wanxiang_t9 / wanxiang_english，按
            // 方案 id 派生会把 Base 目录误判为 Lite。目录判定不明
            // （内置目录无文件、合并目录多主方案并存）时回退旧口径。
            val dirVariant = WanxiangUpdateManager.variantNameOfDir(
                File(DataManager.schemeDirsRoot, SchemeDirs.currentDirName(context)),
            )
            currentSchemaName = dirVariant
                ?: WanxiangUpdateManager.variantNameOf(schemaId, schemaId)
                ?: schemaId
        }
        if (grammarLanguage != null) {
            grammarReady = File(DataManager.sharedDataDir, "$grammarLanguage.gram").isFile
        }
    }

    var wasUpdating by remember { mutableStateOf(false) }
    LaunchedEffect(updating) {
        if (wasUpdating && !updating) {
            localInfo = WanxiangUpdateManager.getLocalInfo(updatePrefs)
        }
        wasUpdating = updating
    }

    LaunchedEffect(grammarDownloading) {
        if (!grammarDownloading && grammarLanguage != null) {
            grammarReady = File(DataManager.sharedDataDir, "$grammarLanguage.gram").isFile
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.schema_update_title),
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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SectionHeader(stringResource(R.string.schema_model_grammar))
            GroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.schema_model_grammar),
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                grammarLanguage == null ->
                                    stringResource(R.string.schema_grammar_model_unavailable)
                                grammarReady ->
                                    stringResource(R.string.schema_grammar_model_ready)
                                grammarFailed ->
                                    stringResource(R.string.schema_grammar_model_failed)
                                else -> stringResource(R.string.schema_grammar_model_desc)
                            },
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        grammarReady -> Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        grammarDownloading -> ProgressButton(grammarProgress)
                        grammarLanguage != null -> Button(
                            onClick = { downloadGrammar(grammarLanguage!!) },
                            enabled = !updating && !updatePending,
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(stringResource(R.string.download), fontSize = rowSubFontSize)
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.schema_update_local_info))
            GroupCard {
                val local = localInfo
                val noGram = stringResource(R.string.schema_update_no_gram)
                UpdateInfoRow(
                    label = stringResource(R.string.schema_update_current_schema),
                    value = currentSchemaName.ifEmpty { "—" },
                )
                GroupDivider()
                UpdateInfoRow(
                    label = stringResource(R.string.schema_update_local_schema),
                    value = local?.schemaVersion ?: "—",
                )
                GroupDivider()
                UpdateInfoRow(
                    label = stringResource(R.string.schema_update_local_dict),
                    value = local?.dictFingerprint?.takeIf { it.isNotEmpty() }?.let {
                        WanxiangUpdateManager.shortFingerprint(it)
                    } ?: "—",
                )
                GroupDivider()
                UpdateInfoRow(
                    label = stringResource(R.string.schema_update_local_gram),
                    value = local?.gramFingerprint?.let {
                        WanxiangUpdateManager.shortFingerprint(it)
                    } ?: noGram,
                )
            }

            val info = updateInfo
            if (info != null) {
                val local = localInfo
                val diffColor =
                    if (isSystemInDarkTheme()) Color(0xFF69F0AE) else Color(0xFF2E7D32)
                val darkColor = MaterialTheme.colorScheme.onSurface
                val schemaDiff = local != null && local.schemaVersion != info.schemaRemoteVersion
                val dictDiff = info.dictRemoteFingerprint != null &&
                    local?.dictFingerprint?.isNotEmpty() == true &&
                    info.dictRemoteFingerprint != local.dictFingerprint
                val gramDiff = info.gramRemoteFingerprint != null &&
                    local?.gramFingerprint != null &&
                    info.gramRemoteFingerprint != local.gramFingerprint
                SectionHeader(stringResource(R.string.schema_update_remote_info))
                GroupCard {
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_remote_schema),
                        value = info.schemaRemoteVersion,
                        valueColor = if (schemaDiff) diffColor else darkColor,
                    )
                    GroupDivider()
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_remote_dict),
                        value = info.dictRemoteFingerprint?.let {
                            WanxiangUpdateManager.shortFingerprint(it)
                        } ?: "—",
                        valueColor = if (dictDiff) diffColor else darkColor,
                    )
                    GroupDivider()
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_remote_gram),
                        value = info.gramRemoteFingerprint?.let {
                            WanxiangUpdateManager.shortFingerprint(it)
                        } ?: "—",
                        valueColor = if (gramDiff) diffColor else darkColor,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val statusText = when {
                    updating && updateProgress != null -> {
                        val p = updateProgress!!
                        val pct = if (p.total > 0L) {
                            (p.downloaded * 100 / p.total).toInt()
                        } else {
                            0
                        }
                        "$updateStageText $pct%"
                    }
                    updating -> updateStageText.ifEmpty {
                        stringResource(R.string.schema_update_deploying)
                    }
                    updateCheckFailed -> stringResource(R.string.schema_update_check_failed)
                    updatePending && updateDone -> stageDeploying
                    updateDone -> stringResource(R.string.schema_update_success)
                    updateFailed -> stringResource(R.string.schema_update_failed)
                    checkingUpdate -> stringResource(R.string.schema_update_checking)
                    info == null -> ""
                    info.schemaUpdateAvailable ->
                        "${stringResource(R.string.schema_update_available)} " +
                            info.schemaRemoteVersion
                    info.gramUpdateAvailable ->
                        stringResource(R.string.schema_update_gram_only)
                    !info.gramContentVerified ->
                        stringResource(R.string.schema_update_unverified)
                    else -> "${stringResource(R.string.schema_update_latest)} " +
                        "(${info.schemaRemoteVersion})"
                }
                Text(
                    text = statusText,
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when {
                    updateDone -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Button(
                            onClick = { checkWanxiangUpdate() },
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.schema_update_check),
                                fontSize = rowSubFontSize,
                                maxLines = 1,
                            )
                        }
                    }
                    updating -> ProgressButton(
                        progress = updateProgress?.let {
                            if (it.total > 0L) it.downloaded.toFloat() / it.total.toFloat() else 0f
                        } ?: 0f,
                    )
                    checkingUpdate -> Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.schema_update_checking),
                            fontSize = rowSubFontSize,
                            maxLines = 1,
                        )
                    }
                    info?.hasUpdate == true -> Button(
                        onClick = { startWanxiangUpdate(info) },
                        enabled = !grammarDownloading && !grammarPending,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.schema_update_start),
                            fontSize = rowSubFontSize,
                        )
                    }
                    else -> Button(
                        onClick = { checkWanxiangUpdate() },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.schema_update_check),
                            fontSize = rowSubFontSize,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UpdateInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = valueColor,
            maxLines = 1,
        )
    }
}
