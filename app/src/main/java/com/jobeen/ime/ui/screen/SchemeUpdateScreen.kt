package com.jobeen.ime.ui.screen

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.ProgressButton
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
    // 交叉守门：部署进行中（含超时孤儿）时更新页不启动检查/
    // 下载——部署失败回滚会删刚换上的新 dicts，偏好却已记新版，
    // 此后更新入口不再出现。管理器入口与换位前复核是强制关，
    // 此处订阅只决定按钮可用态与入口早退（防误触）。
    val deployRunning by EngineDeployState.running.collectAsStateWithLifecycle()
    val orphanRunning by EngineDeployState.orphanRunning.collectAsStateWithLifecycle()
    val deployBusy = deployRunning || orphanRunning
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
    // 更新跟随当前方案：目标与源在页面加载时按当前目录解析一次。
    var updateTarget by remember {
        mutableStateOf<WanxiangUpdateManager.UpdateTarget>(
            WanxiangUpdateManager.UpdateTarget.Shared,
        )
    }
    var updateSource by remember {
        mutableStateOf<WanxiangUpdateManager.UpdateSource>(
            WanxiangUpdateManager.UpdateSource.NoSource,
        )
    }

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
        if (EngineDeployState.isRunning() || EngineDeployState.isOrphanRunning()) return
        grammarPending = true
        grammarResultSeenAtMs = System.currentTimeMillis()
        val workScope =
            (context.applicationContext as? ImeApplication)?.applicationScope ?: scope
        workScope.launch {
            try {
                if (GramModelDownloader.download(language) == DownloadResult.SUCCESS) {
                    // 部署进行中不 reload：那会把进行中的全量编译
                    // 推倒重来；在途部署编的即是刚落盘的内容。
                    if (!EngineDeployState.isRunning() && !EngineDeployState.isOrphanRunning()) {
                        EngineFactory.current()?.reload()
                    }
                }
            } finally {
                grammarPending = false
            }
        }
    }

    fun checkWanxiangUpdate() {
        if (updatePending || checkingUpdate) return
        if (WanxiangUpdateManager.isRunning.value) return
        if (EngineDeployState.isRunning() || EngineDeployState.isOrphanRunning()) return
        val wanxiangSource =
            updateSource as? WanxiangUpdateManager.UpdateSource.Wanxiang ?: return
        WanxiangUpdateManager.ensureVersionHousekeeping(context)
        checkingUpdate = true
        updateResultSeenAtMs = System.currentTimeMillis()
        updateCheckFailed = false
        scope.launch {
            val result = WanxiangUpdateManager.checkForUpdates(
                updatePrefs, updateTarget, wanxiangSource,
            )
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
        if (EngineDeployState.isRunning() || EngineDeployState.isOrphanRunning()) return
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
                    // 部署进行中不 reload：理由同 downloadGrammar；
                    // 在途部署编的即是刚换上的内容。
                    if (!EngineDeployState.isRunning() && !EngineDeployState.isOrphanRunning()) {
                        EngineFactory.current()?.reload()
                    }
                    (updateSource as? WanxiangUpdateManager.UpdateSource.Wanxiang)?.let { source ->
                        WanxiangUpdateManager.checkForUpdates(updatePrefs, updateTarget, source)
                            .onSuccess { refreshed ->
                                updateInfo = refreshed
                            }
                            .onFailure {
                                updateCheckFailed = true
                            }
                    }
                    localInfo = WanxiangUpdateManager.getLocalInfo(
                        updatePrefs, updateTarget,
                        updateSource is WanxiangUpdateManager.UpdateSource.Wanxiang,
                    )
                }
            } finally {
                updatePending = false
            }
        }
    }

    LaunchedEffect(Unit) {
        WanxiangUpdateManager.ensureVersionHousekeeping(context)
        val target = WanxiangUpdateManager.resolveUpdateTarget(context)
        updateTarget = target
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
        val source = WanxiangUpdateManager.resolveUpdateSource(target, schemaId, schemaId)
        updateSource = source
        localInfo = WanxiangUpdateManager.getLocalInfo(
            updatePrefs, target,
            source is WanxiangUpdateManager.UpdateSource.Wanxiang,
        )
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
            localInfo = WanxiangUpdateManager.getLocalInfo(
                updatePrefs, updateTarget,
                updateSource is WanxiangUpdateManager.UpdateSource.Wanxiang,
            )
        }
        wasUpdating = updating
    }

    LaunchedEffect(grammarDownloading) {
        if (!grammarDownloading && grammarLanguage != null) {
            grammarReady = File(DataManager.sharedDataDir, "$grammarLanguage.gram").isFile
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.schema_update_title),
        onBack = onBack,
    ) {

            // 语法模型是万象专属：非万象目录不显示模型区（含下载按钮）。
            if (updateSource is WanxiangUpdateManager.UpdateSource.Wanxiang) {
            SettingsGroup(title = stringResource(R.string.schema_model_grammar)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
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
                            enabled = !updating && !updatePending && !deployBusy,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(stringResource(R.string.download), fontSize = rowSubFontSize)
                        }
                    }
                }
            }
            }

            SettingsGroup(title = stringResource(R.string.schema_update_local_info)) {
                val local = localInfo
                val noGram = stringResource(R.string.schema_update_no_gram)
                UpdateInfoRow(
                    label = stringResource(R.string.schema_update_current_schema),
                    value = currentSchemaName.ifEmpty { "—" },
                )
                UpdateInfoRow(
                    label = stringResource(R.string.schema_update_local_schema),
                    value = local?.schemaVersion?.takeIf { it.isNotEmpty() } ?: "—",
                )
                UpdateInfoRow(
                    label = stringResource(R.string.schema_update_local_dict),
                    value = local?.dictFingerprint?.takeIf { it.isNotEmpty() }?.let {
                        WanxiangUpdateManager.shortFingerprint(it)
                    } ?: "—",
                )
                // 非万象目录不显示模型行（模型是万象专属）。
                if (updateSource is WanxiangUpdateManager.UpdateSource.Wanxiang) {
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_local_gram),
                        value = local?.gramFingerprint?.let {
                            WanxiangUpdateManager.shortFingerprint(it)
                        } ?: noGram,
                    )
                }
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
                SettingsGroup(title = stringResource(R.string.schema_update_remote_info)) {
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_remote_schema),
                        value = info.schemaRemoteVersion,
                        valueColor = if (schemaDiff) diffColor else darkColor,
                    )
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_remote_dict),
                        value = info.dictRemoteFingerprint?.let {
                            WanxiangUpdateManager.shortFingerprint(it)
                        } ?: "—",
                        valueColor = if (dictDiff) diffColor else darkColor,
                    )
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
                    updateSource is WanxiangUpdateManager.UpdateSource.NoSource ->
                        stringResource(R.string.schema_update_no_source)
                    updateSource is WanxiangUpdateManager.UpdateSource.UnsupportedVariant ->
                        stringResource(R.string.schema_update_pro_unsupported)
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
                        enabled = !grammarDownloading && !grammarPending && !deployBusy,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.schema_update_start),
                            fontSize = rowSubFontSize,
                        )
                    }
                    // 无官方源时不提供检查按钮（状态区已说明原因）。
                    updateSource !is WanxiangUpdateManager.UpdateSource.Wanxiang -> {}
                    else -> Button(
                        onClick = { checkWanxiangUpdate() },
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
            .padding(vertical = 12.dp),
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
