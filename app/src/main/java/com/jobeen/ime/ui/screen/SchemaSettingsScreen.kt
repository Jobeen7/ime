package com.jobeen.ime.ui.screen

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.R
import com.jobeen.ime.base.ngram.DownloadResult
import com.jobeen.ime.base.ngram.GramModelDownloader
import com.jobeen.ime.base.update.WanxiangUpdateManager
import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.rime.core.SchemaItem
import com.jobeen.ime.data.schemaLayoutTag
import com.jobeen.ime.ui.screen.ScreenComponent.SectionHeader
import com.jobeen.ime.ui.screen.ScreenComponent.ActionRow
import com.jobeen.ime.ui.screen.ScreenComponent.ProgressButton
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowSubFontSize
import com.jobeen.ime.ui.theme.ExpressiveShapes
import kotlin.math.roundToInt
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs =
        remember { context.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val enabledSchemas = remember { mutableStateListOf<SchemaItem>() }
    val availableSchemas = remember { mutableStateListOf<SchemaItem>() }
    var loaded by remember { mutableStateOf(false) }
    var grammarLanguage by remember { mutableStateOf<String?>(null) }
    var grammarReady by remember { mutableStateOf(false) }
    // 下载中状态、进度与最近结果都读进程级 StateFlow（GramModelDownloader /
    // WanxiangUpdateManager 各一份）：页面 remember 状态随页面销毁丢失，
    // 两路下载都在应用级作用域跑、页面销毁不中断，重进页面只看局部
    // 状态会误判为空闲、重复启动同一路下载，进行中的进度与已完成
    // 的结果也无从读回（N1/N2）
    val grammarDownloading by GramModelDownloader.isRunning.collectAsStateWithLifecycle()
    val grammarProgress by GramModelDownloader.progress.collectAsStateWithLifecycle()
    val grammarResult by GramModelDownloader.lastResult.collectAsStateWithLifecycle()
    // 语法模型结果与下方方案更新侧同口径配「已消费时间」标记
    // （updateResultSeenAtMs）：点下载即消费掉旧结果，未消费的失
    // 败才呈失败态，不让上一次失败在重进页面后长期霸占状态行；
    // 结果本身在进程级 lastResult 里，重进页面仍能读到未消费的
    var grammarResultSeenAtMs by remember { mutableLongStateOf(0L) }
    val shownGrammarResult = grammarResult?.takeIf { it.finishedAtMs > grammarResultSeenAtMs }
    // 语法模型失败态从进程级结果派生：已就绪或下载进行中都不算失败；
    // 被下载闸拒绝（BUSY）不写结果，不会误报失败（N3）
    val grammarFailed = !grammarReady && !grammarDownloading &&
        shownGrammarResult?.result == DownloadResult.FAILED
    var grammarButtonWidth by remember { mutableStateOf(0.dp) }
    // 万象方案更新状态
    val updatePrefs = remember {
        context.getSharedPreferences(WanxiangUpdateManager.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var updateInfo by remember { mutableStateOf<WanxiangUpdateManager.UpdateInfo?>(null) }
    val updating by WanxiangUpdateManager.isRunning.collectAsStateWithLifecycle()
    val updateProgress by WanxiangUpdateManager.progress.collectAsStateWithLifecycle()
    val updateResult by WanxiangUpdateManager.lastResult.collectAsStateWithLifecycle()
    // 最近一次更新结果以「已消费时间」标记：点检查/开始更新即消费掉
    // 旧结果，状态文案回到检查口径，不让上一次结果长期霸占状态行；
    // 结果本身在进程级 lastResult 里，重进页面仍能读到未消费的结果
    var updateResultSeenAtMs by remember { mutableLongStateOf(0L) }
    val shownUpdateResult = updateResult?.takeIf { it.finishedAtMs > updateResultSeenAtMs }
    val updateDone = shownUpdateResult?.result == DownloadResult.SUCCESS
    val updateFailed = shownUpdateResult?.result == DownloadResult.FAILED
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateCheckFailed by remember { mutableStateOf(false) }
    var updateButtonWidth by remember { mutableStateOf(0.dp) }
    // 页面本地「已派发」标志（N3）：点击时同步置位、工作协程收尾
    // 复位。进程级 isRunning 要等下载协程真正跑起来才翻转、经
    // StateFlow 到达组合又有帧级延迟，只读它守门会留 1~2 帧双击
    // 窗口；守门同步读 isRunning.value 与本地标志双保险
    var grammarPending by remember { mutableStateOf(false) }
    var updatePending by remember { mutableStateOf(false) }
    // 本地信息（打开页面即计算，用于"本地信息"卡片展示）
    var currentSchemaName by remember { mutableStateOf("") }
    var localInfo by remember { mutableStateOf<WanxiangUpdateManager.LocalInfo?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 协程内不可用 stringResource，更新阶段文案提前在组合阶段解析
    val stageDownloadingSchema = stringResource(R.string.schema_update_downloading_schema)
    val stageExtracting = stringResource(R.string.schema_update_extracting)
    val stageDownloadingGram = stringResource(R.string.schema_update_downloading_gram)
    val stageDeploying = stringResource(R.string.schema_update_deploying)
    // 更新阶段文案从进程级进度派生：页面重进时进度已在管理器里、
    // 阶段文案随之可得，不再依赖发起页的写回（N2）
    val updateStageText = when (updateProgress?.stage) {
        WanxiangUpdateManager.UpdateProgress.Stage.SCHEMA_DOWNLOAD -> stageDownloadingSchema
        WanxiangUpdateManager.UpdateProgress.Stage.DICTS_EXTRACT -> stageExtracting
        WanxiangUpdateManager.UpdateProgress.Stage.GRAM_DOWNLOAD -> stageDownloadingGram
        null -> ""
    }

    // 异步加载输入方案，避免阻塞主线程（schemasList 内部走引擎线程）
    LaunchedEffect(Unit) {
        val allSchemas = EngineFactory.current()?.schemasList() ?: emptyList()
        if (allSchemas.isNotEmpty()) {
            val allItems =
                allSchemas.map { SchemaItem(it.id, it.name, it.layout, it.punctuation, it.kind) }
            val enabledIds = prefs.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList()
            val byId = allItems.associateBy { it.id }
            val seen = mutableSetOf<String>()
            enabledSchemas.clear()
            enabledSchemas.addAll(enabledIds.mapNotNull { byId[it]?.also { seen.add(it.id) } })
            availableSchemas.clear()
            availableSchemas.addAll(allItems.filter { it.id !in seen })
            // 将当前正在使用的方案置顶（顶部的方案即为"默认"方案）
            val currentId = withContext(Dispatchers.IO) {
                runCatching {
                    (EngineFactory.current() as? IRimeJob)?.awaitJob<String?>(null) {
                        currentSchema().schemaId
                    }
                }.getOrNull()
            }
            if (currentId != null) {
                val idx = enabledSchemas.indexOfFirst { it.id == currentId }
                if (idx > 0) {
                    val item = enabledSchemas.removeAt(idx)
                    enabledSchemas.add(0, item)
                    saveOrder(prefs, enabledSchemas)
                }
            }
            // 「当前选择的方案」显示万象变体短名（Base/Pro/Lite/Pure），按
            // 实际方案派生，不再写死 Lite：当前方案识别不出变体（如英文方案）
            // 时在已启用列表里找第一个万象拼音方案，都没有则显示当前方案名
            val current = enabledSchemas.firstOrNull { it.id == currentId }
                ?: enabledSchemas.firstOrNull()
            val variant = current?.let {
                WanxiangUpdateManager.variantNameOf(it.id, it.name)
            } ?: enabledSchemas.firstNotNullOfOrNull {
                WanxiangUpdateManager.variantNameOf(it.id, it.name)
            }
            currentSchemaName = variant ?: current?.name ?: ""
            // 计算本地信息（词库/模型指纹），用于"本地信息"卡片
            localInfo = WanxiangUpdateManager.getLocalInfo(updatePrefs)
            loaded = true
        }
    }

    // 打开页面先做版本切换清理（指纹记录/缓存跨版本作废，见
    // WanxiangUpdateManager.ensureVersionHousekeeping；checkForUpdates
    // 入口也会再调一次，幂等）
    LaunchedEffect(Unit) {
        WanxiangUpdateManager.ensureVersionHousekeeping(context)
    }

    // 更新结束（updating 由 true 变 false）后重算本地信息：更新可能
    // 在本页之外发起或结束，本地卡片要反映盘上最新状态（N2）
    var wasUpdating by remember { mutableStateOf(false) }
    LaunchedEffect(updating) {
        if (wasUpdating && !updating) {
            localInfo = WanxiangUpdateManager.getLocalInfo(updatePrefs)
        }
        wasUpdating = updating
    }

    // 语法模型下载结束（哪一页发起的都一样）后按盘上文件刷新就绪态
    LaunchedEffect(grammarDownloading) {
        if (!grammarDownloading && grammarLanguage != null) {
            grammarReady = File(DataManager.sharedDataDir, "$grammarLanguage.gram").isFile
        }
    }

    fun downloadGrammar(language: String) {
        // 与万象更新互斥：两路都写 sharedDataDir 下的语法模型文件，
        // 同时跑会各下一份约 400 MB 且落盘记录互相错位。守门同步读
        // 进程级状态（isRunning.value）与本地已派发标志（见
        // grammarPending 处注释）；管理器侧另有共用 DownloadGate
        // 兜底，穿过守门的重复调用返回 BUSY、不会被当成失败
        if (grammarPending || updatePending) return
        if (GramModelDownloader.isRunning.value || WanxiangUpdateManager.isRunning.value) return
        grammarPending = true
        // 发起下载即消费掉上一次的语法模型结果，状态文案回到未
        // 就绪口径（与 startWanxiangUpdate 消费更新结果同口径）
        grammarResultSeenAtMs = System.currentTimeMillis()
        // 下载跑在应用级作用域（与 startWanxiangUpdate 同口径，N1）：
        // 绑页面 scope 时旋转屏幕、系统切深浅色（Activity 重建）或
        // 离开页面都会把约 400 MB 的下载取消并删掉已下部分；进度与
        // 结果由管理器以进程级 StateFlow 发布，页面订阅即可，引擎
        // reload 也在这里收尾、不依赖页面存活
        val workScope =
            (context.applicationContext as? ImeApplication)?.applicationScope ?: scope
        workScope.launch {
            try {
                if (GramModelDownloader.download(language) == DownloadResult.SUCCESS) {
                    // The grammar database is loaded during engine startup.
                    EngineFactory.current()?.reload()
                }
            } finally {
                grammarPending = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val language = withContext(Dispatchers.IO) {
            runCatching {
                // openSchema 是 librime native 入口：页面协程（IO）直调会绕开
                // rime-main 串行线，改为派发到 rime-main 执行并等待结果。
                // 此前走 awaitJob 的 job 体仍在 jobs 线程，同样不算 rime-main
                Rime.runOnRimeMain {
                    val currentSchema = currentSchema()
                    RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                        config.getString("grammar/language")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
            }.getOrNull()
        }
        grammarLanguage = language
        if (language != null) {
            grammarReady = File(DataManager.sharedDataDir, "$language.gram").isFile
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun checkWanxiangUpdate() {
        if (updatePending || checkingUpdate) return
        if (WanxiangUpdateManager.isRunning.value) return
        // App 升级后的指纹记录/缓存清理统一走 WUM 的版本切换清理
        // （打开页面时已调过、这里再兜一次，幂等）：它对全部用户
        // 生效；旧实现在此整表 clear，但触发键只在应用内更新成功
        // 后才写入，没在应用内更新过的用户永远清不到
        WanxiangUpdateManager.ensureVersionHousekeeping(context)
        // 联网查询 GitHub 最新版本，与本地记录比对
        checkingUpdate = true
        // 发起新一轮检查即消费掉上一次的更新结果，状态文案回到检查口径
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
        // 与语法模型单独下载互斥（见 downloadGrammar 的同一口径）：
        // 守门同步读进程级状态与本地已派发标志，管理器侧另有共用
        // DownloadGate 兜底
        if (updatePending || grammarPending) return
        if (WanxiangUpdateManager.isRunning.value || GramModelDownloader.isRunning.value) return
        updatePending = true
        // 发起更新即消费掉上一次的结果，状态文案回到进行中口径
        updateResultSeenAtMs = System.currentTimeMillis()
        updateCheckFailed = false
        // 下载应用 + 引擎 reload 必须跑在应用级作用域：绑页面 scope 时用户
        // 中途离开页面会把协程取消，可能词库文件已替换而 reload 永不执行，
        // 引擎继续用旧部署。进度与结果由管理器以进程级 StateFlow 发布，
        // 页面订阅即可（页面已销毁时本协程对页面状态的写回无副作用）
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
                    // 词库与模型已在盘上换新，先触发 Rime 完整重新部署，
                    // 再做联网复检：复检只刷新远端信息展示，排在前面会让
                    // 引擎换新数据被一次网络往返（慢或失败时更久）无谓推迟
                    EngineFactory.current()?.reload()
                    // 更新本地版本显示。复检失败必须可见：置检查失败标志
                    // （状态文案里检查失败优先于更新成功，见下方 statusText），
                    // 否则远程卡停留在更新前数值、还显示成功，用户无从知道；
                    // 更新成功态保留（更新本身确实成功），按钮区在该状态
                    // 仍给出「检查更新」入口（见下方按钮分支），可当场复检
                    WanxiangUpdateManager.checkForUpdates(updatePrefs)
                        .onSuccess { refreshed ->
                            updateInfo = refreshed
                        }
                        .onFailure {
                            updateCheckFailed = true
                        }
                    // 重新计算本地指纹（更新后应与远端指纹一致）
                    localInfo = WanxiangUpdateManager.getLocalInfo(updatePrefs)
                }
                // FAILED 时管理器已发布 lastResult，状态文案从其派生；
                // BUSY（守门之外被闸拒绝）不置任何失败标志
            } finally {
                updatePending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.schema_settings),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { saveOrder(prefs, enabledSchemas); onBack() }) {
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
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup(title = stringResource(R.string.schema_update_title)) {
                // 语法模型（原"模型管理"分组已合并至此）
                ActionRow(
                    title = stringResource(R.string.schema_model_grammar),
                    subtitle = when {
                        grammarLanguage == null -> stringResource(R.string.schema_grammar_model_unavailable)
                        grammarReady -> stringResource(R.string.schema_grammar_model_ready)
                        grammarFailed -> stringResource(R.string.schema_grammar_model_failed)
                        else -> stringResource(R.string.schema_grammar_model_desc)
                    },
                    trailing = {
                        when {
                            grammarReady -> Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )

                            grammarDownloading -> ProgressButton(
                                grammarProgress, width = grammarButtonWidth
                            )

                            grammarLanguage != null -> Button(
                                onClick = { downloadGrammar(grammarLanguage!!) },
                                // 万象更新进行中禁用（与 downloadGrammar 的守门同口径）
                                enabled = !updating && !updatePending,
                                modifier = Modifier
                                    .height(32.dp)
                                    .onSizeChanged {
                                        grammarButtonWidth = with(density) { it.width.toDp() }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(stringResource(R.string.download), fontSize = rowSubFontSize)
                            }
                        }
                    },
                )

                val info = updateInfo
                val local = localInfo
                val noGram = stringResource(R.string.schema_update_no_gram)
                // 高亮色：仅用于远端信息与本地不一致时
                val diffColor = if (isSystemInDarkTheme()) Color(0xFF69F0AE) else Color(0xFF2E7D32)
                val darkColor = MaterialTheme.colorScheme.onSurface

                // 本地信息
                UpdateInfoSectionTitle(stringResource(R.string.schema_update_local_info))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_current_schema),
                        value = currentSchemaName.ifEmpty { "—" },
                        valueColor = MaterialTheme.colorScheme.onSurface,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_local_schema),
                        value = local?.schemaVersion ?: "—",
                        valueColor = darkColor,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_local_dict),
                        value = local?.dictFingerprint?.takeIf { it.isNotEmpty() }?.let {
                            WanxiangUpdateManager.shortFingerprint(it)
                        } ?: "—",
                        valueColor = darkColor,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    UpdateInfoRow(
                        label = stringResource(R.string.schema_update_local_gram),
                        value = local?.gramFingerprint?.let {
                            WanxiangUpdateManager.shortFingerprint(it)
                        } ?: noGram,
                        valueColor = darkColor,
                    )
                }

                // 远程信息（检查后显示）
                // 远程信息（检查后显示）：与本地不一致时用绿色高亮
                if (info != null) {
                    // 远端方案版本与本地不一致 → 高亮（本地未加载完成时不高亮）
                    val schemaDiff = local != null && local.schemaVersion != info.schemaRemoteVersion
                    // 远端词库指纹与本地不一致（双方都有值且不同）→ 高亮
                    val dictDiff = info.dictRemoteFingerprint != null &&
                        local?.dictFingerprint?.isNotEmpty() == true &&
                        info.dictRemoteFingerprint != local.dictFingerprint
                    // 远端模型指纹与本地不一致（双方都有值且不同）→ 高亮
                    val gramDiff = info.gramRemoteFingerprint != null &&
                        local?.gramFingerprint != null &&
                        info.gramRemoteFingerprint != local.gramFingerprint
                    UpdateInfoSectionTitle(stringResource(R.string.schema_update_remote_info))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ExpressiveShapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        UpdateInfoRow(
                            label = stringResource(R.string.schema_update_remote_schema),
                            value = info.schemaRemoteVersion,
                            valueColor = if (schemaDiff) diffColor else darkColor,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        UpdateInfoRow(
                            label = stringResource(R.string.schema_update_remote_dict),
                            value = info.dictRemoteFingerprint?.let {
                                WanxiangUpdateManager.shortFingerprint(it)
                            } ?: "—",
                            valueColor = if (dictDiff) diffColor else darkColor,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
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

                // 底部状态文本 + 操作按钮（右对齐）
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
                        // 检查失败排在更新成功之前：更新成功后的静默
                        // 复检若失败，最新事实是「复检没过、远程卡是旧
                        // 值」，继续显示成功会掩盖这一点；普通检查失败
                        // 时 updateDone 本就为假，顺序调整不影响它
                        updateCheckFailed -> stringResource(R.string.schema_update_check_failed)
                        // 下载已完成、本地协程还在做引擎重部署与联网
                        // 复检收尾：继续显示部署中，不抢先报成功
                        updatePending && updateDone -> stageDeploying
                        updateDone -> stringResource(R.string.schema_update_success)
                        updateFailed -> stringResource(R.string.schema_update_failed)
                        checkingUpdate -> stringResource(R.string.schema_update_checking)
                        info == null -> ""
                        // 文案按更新对象区分：版本号是方案的版本，只有
                        // 方案侧有更新时才拼它；仅语法模型有更新时拼上
                        // 方案版本号会让用户误以为方案也要更新（此前
                        // 只更新模型也显示「发现新版本 <方案版本>」）
                        info.schemaUpdateAvailable ->
                            "${stringResource(R.string.schema_update_available)} " +
                                info.schemaRemoteVersion
                        info.gramUpdateAvailable ->
                            stringResource(R.string.schema_update_gram_only)
                        // 远端内容未经登记摘要核实（摘要接口失败）时
                        // 不得宣称「已是最新」：模型侧无更新结论只是
                        // 下载记录的兜底判断，如实报未确认（V7b 口径）
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
                        // 更新成功后仍保留「检查更新」入口：成功回调里的
                        // 静默复检失败时远程卡是旧值，用户可当场手动复检，
                        // 不必退出页面重进
                        updateDone -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Button(
                                onClick = { checkWanxiangUpdate() },
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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
                            width = updateButtonWidth,
                        )

                        checkingUpdate -> Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.schema_update_checking),
                                fontSize = rowSubFontSize,
                                maxLines = 1,
                            )
                        }

                        info?.hasUpdate == true -> Button(
                            onClick = { startWanxiangUpdate(info) },
                            // 语法模型下载进行中禁用（与 startWanxiangUpdate 的守门同口径）
                            enabled = !grammarDownloading && !grammarPending,
                            modifier = Modifier
                                .height(32.dp)
                                .onSizeChanged {
                                    updateButtonWidth = with(density) { it.width.toDp() }
                                },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.schema_update_start),
                                fontSize = rowSubFontSize,
                            )
                        }

                        else -> Button(
                            onClick = { checkWanxiangUpdate() },
                            modifier = Modifier
                                .height(32.dp)
                                .onSizeChanged {
                                    updateButtonWidth = with(density) { it.width.toDp() }
                                },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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

            SectionHeader(stringResource(R.string.enabled_schemas))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                ReorderableSchemaList(enabledSchemas) { schema ->
                    if (enabledSchemas.size > 1) {
                        IconButton(onClick = {
                            availableSchemas.add(schema)
                            enabledSchemas.remove(schema)
                            saveOrder(prefs, enabledSchemas)
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            if (availableSchemas.isNotEmpty()) {
                SectionHeader(stringResource(R.string.available_schemas))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column {
                        availableSchemas.forEach { schema ->
                            SchemaListItem(
                                schema = schema,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        enabledSchemas.add(schema)
                                        availableSchemas.remove(schema)
                                        saveOrder(prefs, enabledSchemas)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderableSchemaList(
    items: MutableList<SchemaItem>,
    trailingIcon: @Composable (SchemaItem) -> Unit,
) {
    val context = LocalContext.current
    val prefs =
        remember { context.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var itemHeight by remember { mutableFloatStateOf(0f) }

    Column {
        items.forEachIndexed { index, schema ->
            val isDragging = dragIndex == index
            val dragScale by animateFloatAsState(
                targetValue = if (isDragging) 1.03f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "dragScale",
            )

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                    .scale(dragScale)
                    .graphicsLayer {
                        this.shadowElevation = if (isDragging) 8f else 0f
                    }
                    .onGloballyPositioned {
                        if (itemHeight == 0f) itemHeight = it.size.height.toFloat()
                    }
                    .pointerInput(index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragIndex = index
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                val target =
                                    (dragIndex + (dragOffset / itemHeight).roundToInt()).coerceIn(
                                        0, items.size - 1
                                    )
                                if (target != dragIndex) {
                                    val item = items.removeAt(dragIndex)
                                    items.add(target, item)
                                    saveOrder(prefs, items)
                                }
                                dragIndex = -1
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                        )
                    },
            ) {
                SchemaListItem(
                    schema = schema,
                    isDefault = index == 0,
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = { trailingIcon(schema) },
                )
            }
        }
    }
}

@Composable
private fun SchemaListItem(
    schema: SchemaItem,
    isDefault: Boolean = false,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                schema.name.ifBlank { schema.id },
                fontSize = rowFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (schema.name.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDefault) {
                        TagBadge(
                            stringResource(R.string.tag_default),
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    val tagText = schemaLayoutTag(context, schema.layout)
                    TagBadge(
                        tagText,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                    val punctText =
                        if (schema.punctuation == "FullWidth") stringResource(R.string.tag_punctuation_full)
                        else stringResource(R.string.tag_punctuation_half)
                    TagBadge(
                        punctText,
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        trailingIcon()
    }
}

private fun saveOrder(prefs: SharedPreferences, schemas: List<SchemaItem>) {
    prefs.edit { putString(SchemaManager.KEY_ENABLED_IDS, schemas.joinToString(",") { it.id }) }
}

@Composable
private fun TagBadge(text: String, bg: Color, fg: Color) {
    Text(
        text = text,
        fontSize = rowSubFontSize * 0.9f,
        lineHeight = rowSubFontSize * 0.9f,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

/** 方案更新"本地信息/远程信息"小标题 */
@Composable
private fun UpdateInfoSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = rowSubFontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}

/** 方案更新信息行：左标签，右值 */
@Composable
private fun UpdateInfoRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = rowFontSize,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            fontSize = rowSubFontSize,
            color = valueColor,
            maxLines = 1,
        )
    }
}
