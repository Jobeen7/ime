package com.jobeen.ime.ui.screen

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.R
import com.jobeen.ime.base.ngram.DownloadResult
import com.jobeen.ime.base.ngram.GramModelDownloader
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.base.update.WanxiangUpdateManager
import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.data.manager.DirSwitchMonitor
import com.jobeen.ime.data.manager.DirSwitchPhase
import com.jobeen.ime.data.manager.DirSwitchResumePolicy
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemeDownload
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.SchemePackageImport.PlanResult
import com.jobeen.ime.data.manager.SchemePackageImport.SchemeImportPlan
import com.jobeen.ime.data.manager.SchemeSwitchOrchestrator
import com.jobeen.ime.data.manager.SchemeSwitchPolicy
import com.jobeen.ime.data.manager.SchemeSwitchState
import com.jobeen.ime.data.manager.SwitchVerification
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.rime.core.SchemaItem
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsScreen(onBack: () -> Unit, openManage: Boolean = false) {
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
    // 第一期切换编排：状态机是转移权威，switchState 是其组合态镜像
    // （状态机本身不是 State，页内变化须经 refreshSwitchState 同步，
    // 否则横幅与忙态不会重组）
    val switchOrchestrator = remember { SchemeSwitchOrchestrator() }
    var switchState by remember { mutableStateOf<SchemeSwitchState>(SchemeSwitchState.Idle) }
    var editMode by remember { mutableStateOf(openManage) }
    var menuExpanded by remember { mutableStateOf(false) }
    var currentSchemeId by remember { mutableStateOf("") }
    // 多目录模型：当前目录名（引擎 user 目录 = 根 / 此名）；目录切换、
    // 导入新目录后随之更新，管理页与启用表一律按此目录读写。
    var currentDir by remember { mutableStateOf(SchemeDirs.currentDirName(context)) }
    var dirBrowser by remember { mutableStateOf(false) }
    var dirDetail by remember { mutableStateOf<String?>(null) }
    var dirSwitching by remember { mutableStateOf(false) }
    var dirSwitchError by remember { mutableStateOf<String?>(null) }
    // stranded 修复：切换状态改读进程级 Monitor（组合销毁不丢）；
    // 空表状态：引擎表空时不再静默留白，改显部署中 + 自动重载。
    val monitorStatus by DirSwitchMonitor.status.collectAsStateWithLifecycle()
    var schemasEmpty by remember { mutableStateOf(false) }
    var manageDeploying by remember { mutableStateOf(false) }
    var manageDeployError by remember { mutableStateOf<String?>(null) }
    var versionById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sourceById by remember {
        mutableStateOf<Map<String, SchemeSwitchPolicy.SchemeSource>>(emptyMap())
    }
    var localInfo by remember { mutableStateOf<WanxiangUpdateManager.LocalInfo?>(null) }
    val scope = rememberCoroutineScope()
    // ---- 第二期本地导入：SAF 选 zip → 暂存校验 → 校验结果页 → 确认安装 ----
    // importPlan 非空时 Scaffold 内容整体切到校验结果页（见下方内容分支）；
    // importError 非空且无 plan 时切到错误页；importBusy 时切到等待页。
    // 多目录模型下导入落盘目标是独立新目录（importTargetDir），不再合并
    // 进当前目录；校验页只确认包名、方案数与同名目录替换一行。
    var importPlan by remember { mutableStateOf<SchemeImportPlan?>(null) }
    var importTargetDir by remember { mutableStateOf<File?>(null) }
    var importStagingDir by remember { mutableStateOf<File?>(null) }
    var importError by remember { mutableStateOf<ImportErrorKind?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importInstalling by remember { mutableStateOf(false) }
    val importPrefs = remember {
        context.getSharedPreferences(SchemePackageImport.PREFS_NAME, Context.MODE_PRIVATE)
    }
    // ---- 第三期远程下载：条目列表 / 新增表单 / 下载进度 / 下载错误 ----
    // 下载条目只存方案名 + URL（SchemeDownload 偏好）；Header 每次下载时
    // 输入、不持久化，表单里的 Header 只用于紧随其后的那次下载。
    val downloadPrefs = remember {
        context.getSharedPreferences(SchemeDownload.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val downloadEntries = remember { mutableStateListOf<SchemeDownload.DownloadEntry>() }
    var downloadPage by remember { mutableStateOf(false) }
    var downloadFormOpen by remember { mutableStateOf(false) }
    var downloadFormName by remember { mutableStateOf("") }
    var downloadFormUrl by remember { mutableStateOf("") }
    val downloadFormHeaders = remember { mutableStateListOf<Pair<String, String>>() }
    var downloadFormError by remember { mutableStateOf<DownloadFormError?>(null) }
    var downloadRunning by remember { mutableStateOf(false) }
    var downloadProgressBytes by remember { mutableLongStateOf(0L) }
    var downloadProgressTotal by remember { mutableLongStateOf(-1L) }
    var downloadProgressName by remember { mutableStateOf("") }
    var downloadError by remember { mutableStateOf<DownloadErrorState?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadCall by remember { mutableStateOf<Call?>(null) }
    // 下载交接的校验 / 安装沿用导入流程：importSource 决定登记来源，
    // importFromDownload 决定失败 / 取消后回下载页还是主列表。
    var importSource by remember {
        mutableStateOf<SchemePackageImport.PackageSource>(
            SchemePackageImport.PackageSource.LOCAL,
        )
    }
    var importFromDownload by remember { mutableStateOf(false) }
    // URL 来源方案 id 集（登记里 source=URL 的方案，列表来源处显示 URL）。
    var urlSourceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 整包下载专用 client（WanxiangUpdateManager.downloadClient 同形态：
    // 连接 15s / 读取 120s / 无总时长上限；https→http 降级禁跟随）。
    val downloadClient = remember {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
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

    // 异步加载输入方案，避免阻塞主线程（schemasList 内部走引擎线程）。
    // 抽成独立挂起函数：导入安装 / 部署完成后也要重算（引擎侧部署后
    // 方案表才含新方案，合成占位项需被真实项替换）。
    suspend fun loadSchemas() {
        val allSchemas = EngineFactory.current()?.schemasList() ?: emptyList()
        // 空表（部署进行中 / stranded）不再静默：记下空表态，让正文外
        // 显部署中 + 自动重载，而不是 loaded 恒 false 永久空白。
        schemasEmpty = allSchemas.isEmpty()
        if (allSchemas.isNotEmpty()) {
            val allItems =
                allSchemas.map { SchemaItem(it.id, it.name, it.layout, it.punctuation, it.kind) }
            // 已启用顺序按当前目录分键读（切回旧目录时顺序原样恢复）。
            val enabledIds = SchemaManager.readEnabledIds(prefs, currentDir)
            val byId = allItems.associateBy { it.id }
            val seen = mutableSetOf<String>()
            enabledSchemas.clear()
            enabledSchemas.addAll(enabledIds.mapNotNull { byId[it]?.also { seen.add(it.id) } })
            availableSchemas.clear()
            availableSchemas.addAll(allItems.filter { it.id !in seen })
            if (enabledSchemas.isEmpty() && allItems.isNotEmpty()) {
                // 目录启用表为空（新目录首跑 / 目录被删后重建）：以引擎部署表
                // 为准兜底并写回，不让方案页在引擎有方案时显示空列表。
                enabledSchemas.addAll(allItems)
                availableSchemas.clear()
                SchemaManager.saveEnabledIds(prefs, currentDir, allItems.map { it.id })
            }
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
                    saveOrder(prefs, currentDir, enabledSchemas)
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
            currentSchemeId = currentId ?: ""
            // 每行版本（schema/version；万象族显示 LTS，包版本不在
            // schema 内，不编造）与来源（user 层有方案文件即用户，
            // 否则内置；两层都没有时回落内置，见编排器注释）
            versionById = runCatching {
                (EngineFactory.current() as? IRimeJob)?.awaitJob(emptyMap<String, String>()) {
                    allItems.associate { item ->
                        item.id to runCatching {
                            RimeConfig.openSchema(item.id).use {
                                it.getString("schema/version").orEmpty()
                            }
                        }.getOrDefault("")
                    }
                } ?: emptyMap()
            }.getOrDefault(emptyMap())
            sourceById = withContext(Dispatchers.IO) {
                allItems.associate { item ->
                    val inUser = File(DataManager.userDataDir, "${item.id}.schema.yaml").exists()
                    val inShared =
                        File(DataManager.sharedDataDir, "${item.id}.schema.yaml").exists()
                    item.id to SchemeSwitchPolicy.classifySource(inUser, inShared)
                }
            }
            // URL 来源方案 id 集（登记里 source=URL 的包所含方案，行来源处
            // 显示 URL 而非用户，与本地导入区分；登记缺失时自然为空集）。
            urlSourceIds = SchemePackageImport.decodeRegistry(
                importPrefs.getString(SchemePackageImport.KEY_PACKAGE_REGISTRY, null),
            ).records.filter { it.source == SchemePackageImport.PackageSource.URL }
                .flatMap { it.schemeIds }.toSet()
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

    // ---- 第一期方案切换：点选切换 / 部署 / 回退（状态机见编排器） ----
    fun refreshSwitchState() {
        switchState = switchOrchestrator.state
    }

    /** 把完整顺序写回偏好并同步两列（调用方只传完整顺序，不丢项）。 */
    fun applyOrder(newOrder: List<String>) {
        val byId = (enabledSchemas + availableSchemas).associateBy { it.id }
        val newEnabled = newOrder.mapNotNull { byId[it] }
        val enabledIds = newEnabled.map { it.id }.toSet()
        enabledSchemas.clear()
        enabledSchemas.addAll(newEnabled)
        availableSchemas.removeAll { it.id in enabledIds }
        saveOrder(prefs, currentDir, enabledSchemas)
    }

    /** 读引擎当前方案 id；读失败返回 null（调用方按未验证处理）。 */
    suspend fun readCurrentId(): String? =
        runCatching {
            (EngineFactory.current() as? IRimeJob)?.awaitJob<String?>(null) {
                currentSchema().schemaId
            }
        }.getOrNull()

    /** 轮询引擎当前方案，最多 timeoutMs；超时返回 false（不抛）。 */
    suspend fun verifyCurrent(targetId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (readCurrentId() == targetId) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(1000)
        }
    }

    /**
     * 点选切换。先走偏好顺序 + 引擎直选（常见路径不重启引擎、打字不中断）；
     * 10 秒内确认不到目标才进部署：reload 后再直选并以 180 秒轮询确认；
     * 部署仍失败则回退：恢复切换前顺序 + reload + 重选上一方案并确认。
     * 切换 / 部署 / 回退进行中拒绝新的切换（维护期守卫）。
     * 切换跑应用级作用域，离开页面不取消（此前页面作用域取消导致
     * stranded）；状态机镜像在重进后按实际重算。
     */
    /**
     * 重进续切（stranded 恢复）：只把引擎重同步到指针目录，不改指针。
     * 判定见 DirSwitchResumePolicy：引擎表空 + 磁盘有方案 + 无切换在跑。
     * 幂等：中途取消不留新状态，下次进页重新判定。
     */
    suspend fun resumeIfStranded() {
        val dir = SchemeDirs.currentDirName(context)
        val diskIds = withContext(Dispatchers.IO) {
            runCatching {
                SchemeDirs.schemesInDir(File(DataManager.schemeDirsRoot, dir))
            }.getOrDefault(emptyList())
        }
        val engineEmpty = (EngineFactory.current()?.schemasList() ?: emptyList()).isEmpty()
        val switching = DirSwitchMonitor.status.value.phase == DirSwitchPhase.SWITCHING
        if (!DirSwitchResumePolicy.shouldResume(engineEmpty, diskIds.isNotEmpty(), switching)) return
        val enabled = SchemaManager.readEnabledIds(prefs, dir).ifEmpty { diskIds }
        SchemaManager.saveEnabledIds(prefs, dir, enabled)
        EngineFactory.current()?.reload()
        if (enabled.isNotEmpty()) {
            EngineFactory.current()?.selectSchema(enabled[0])
            // 校验只定成败显示，不抛：成败最终以 loadSchemas 重读的引擎表为准。
            verifyCurrent(enabled[0], 180_000)
        }
    }

    /** 空表轮询重载：2s 一次、最多 5 分钟，直到方案表就绪。 */
    suspend fun pollUntilLoaded() {
        var waitedMs = 0L
        while (!loaded && waitedMs < 300_000L) {
            delay(2000L)
            waitedMs += 2000L
            if (!loaded) loadSchemas()
        }
    }

    // 进页加载 + 部署中自动重载：空表时轮询重载，部署跑完自动出列表；
    // stranded 时先续切一次（仅一次，避免中途重启部署），再继续轮询。
    // 用户中途离开即取消本轮询，下次进页重新判定——续切本身幂等。
    LaunchedEffect(Unit) {
        loadSchemas()
        var resumed = false
        var waitedMs = 0L
        while (!loaded && waitedMs < 300_000L) {
            delay(2000L)
            waitedMs += 2000L
            if (!loaded && !resumed &&
                DirSwitchMonitor.status.value.phase != DirSwitchPhase.SWITCHING
            ) {
                resumed = true
                resumeIfStranded()
            }
            if (!loaded) loadSchemas()
        }
    }

    /**
     * 点选切换（见上方注释）。[onDone] 在切换协程所有终点调用
     * （直选成功 / 部署成功 / 回退完成 / 防御性提前返回），供导入流程
     * 在切换结束后刷新方案表；一期原有调用方不传，行为不变。
     */
    fun switchTo(target: SchemaItem, onDone: (() -> Unit)? = null) {
        if (switchOrchestrator.isBusy()) return
        val plan = switchOrchestrator.beginSwitch(
            currentSchemeId,
            target.id,
            enabledSchemas.map { it.id },
        ) ?: return
        refreshSwitchState()
        applyOrder(plan.newOrder)
        EngineFactory.current()?.selectSchema(target.id)
        // 应用级作用域：点选切换的部署 / 回退路径不再随页面销毁取消。
        appScope.launch {
            val ok = verifyCurrent(target.id, 10_000)
            val result = switchOrchestrator.resolveSwitch(ok)
            refreshSwitchState()
            if (result == SwitchVerification.SUCCESS) {
                currentSchemeId = target.id
                onDone?.invoke()
                return@launch
            }
            // 仅 Deploying 态才真正部署（编排器约定：非 Switching 态
            // 的 resolveSwitch 不转移状态，返回值不得单独作为部署依据）
            if (switchState !is SchemeSwitchState.Deploying) {
                currentSchemeId = readCurrentId() ?: currentSchemeId
                onDone?.invoke()
                return@launch
            }
            EngineFactory.current()?.reload()
            EngineFactory.current()?.selectSchema(target.id)
            if (switchOrchestrator.resolveDeploy(verifyCurrent(target.id, 180_000))) {
                refreshSwitchState()
                currentSchemeId = target.id
                onDone?.invoke()
                return@launch
            }
            refreshSwitchState()
            val rollback = switchOrchestrator.beginRollback() ?: run {
                onDone?.invoke()
                return@launch
            }
            refreshSwitchState()
            applyOrder(rollback.restoredOrder)
            EngineFactory.current()?.reload()
            EngineFactory.current()?.selectSchema(rollback.previousId)
            switchOrchestrator.resolveRollback(verifyCurrent(rollback.previousId, 180_000))
            refreshSwitchState()
            currentSchemeId = readCurrentId() ?: rollback.previousId
            onDone?.invoke()
        }
    }

    /**
     * 多目录切换：指针 → 启用表 → 引擎重部署 → 选首位方案 → 轮询确认。
     * 目标启用表：分目录键优先，缺失时用目录方案表顺序；空目录（无方案）
     * 时不直选，改为等引擎部署出内置表（Lite 兜底）且当前方案非空。
     * 任一步失败自动回滚上一目录（指针 + 启用表 + 引擎重部署 + 重选上一方案），
     * 并在方案页留一条可关闭的失败提示。切换进行中拒绝新的切换与导入。
     * 切换跑应用级作用域（appScope）：离开页面不取消；失败与取消一律
     * 回滚（取消在 NonCancellable 内做）——此前页面作用域 + 取消跳过
     * 回滚，正是 Base 导入 stranded 的根因。状态同时写进程级
     * DirSwitchMonitor，重进页面能读到进行中 / 失败。
     */
    fun switchDirectory(targetDir: String) {
        if (dirSwitching || switchOrchestrator.isBusy() ||
            DirSwitchMonitor.status.value.phase == DirSwitchPhase.SWITCHING ||
            targetDir == currentDir
        ) return
        val prevDir = currentDir
        val prevOrder = enabledSchemas.map { it.id }
        val prevCurrent = currentSchemeId
        dirSwitching = true
        dirSwitchError = null
        DirSwitchMonitor.begin(targetDir)
        appScope.launch {
            var succeeded = false
            try {
                var targetEnabled = SchemaManager.readEnabledIds(prefs, targetDir)
                if (targetEnabled.isEmpty()) {
                    targetEnabled = SchemeDirs.schemesInDir(
                        File(DataManager.schemeDirsRoot, targetDir),
                    )
                }
                SchemeDirs.setCurrentDirName(context, targetDir)
                currentDir = targetDir
                SchemaManager.saveEnabledIds(prefs, targetDir, targetEnabled)
                EngineFactory.current()?.reload()
                if (targetEnabled.isNotEmpty()) {
                    EngineFactory.current()?.selectSchema(targetEnabled[0])
                    if (!verifyCurrent(targetEnabled[0], 180_000)) {
                        throw IllegalStateException("switch verify timeout")
                    }
                } else {
                    // 空目录：等引擎部署完成且当前方案非空（内置 Lite 兜底）。
                    val deadline = System.currentTimeMillis() + 60_000
                    var ready = false
                    while (System.currentTimeMillis() < deadline) {
                        if (!readCurrentId().isNullOrEmpty()) {
                            ready = true
                            break
                        }
                        delay(1000)
                    }
                    if (!ready) throw IllegalStateException("empty dir deploy timeout")
                }
                succeeded = true
                DirSwitchMonitor.done()
            } catch (e: Exception) {
                // 失败与取消一律回滚（NonCancellable 内做，取消后仍能执行）；
                // 回滚本身尽力而为，不掩盖原错；失败提示写 Monitor 供重进读取。
                withContext(NonCancellable) {
                    runCatching {
                        SchemeDirs.setCurrentDirName(context, prevDir)
                        currentDir = prevDir
                        SchemaManager.saveEnabledIds(prefs, prevDir, prevOrder)
                        EngineFactory.current()?.reload()
                        if (prevCurrent.isNotEmpty()) {
                            EngineFactory.current()?.selectSchema(prevCurrent)
                        }
                    }
                }
                val msg = context.getString(R.string.schema_dir_switch_failed)
                DirSwitchMonitor.failed(msg)
                dirSwitchError = msg
            }
            dirSwitching = false
            if (succeeded) {
                dirBrowser = false
                dirDetail = null
            }
            loadSchemas()
            if (!loaded) {
                // 部署仍在跑：页内轮询重载（离开页面即取消，重进由进页循环接手）。
                scope.launch { pollUntilLoaded() }
            }
        }
    }

    // ---- 第二期本地导入流程（SAF 选 zip → 暂存校验 → 确认安装 → 一期切换） ----
    /** 删掉本次导入的暂存文件（暂存目录与临时 zip，按名删，状态未赋值也覆盖）。 */
    fun cleanupImportFiles() {
        importStagingDir?.deleteRecursively()
        importStagingDir = null
        importTargetDir = null
        File(context.cacheDir, IMPORT_STAGING_DIR_NAME).deleteRecursively()
        File(context.cacheDir, IMPORT_TMP_ZIP_NAME).delete()
    }

    /** 取消导入：关校验页并清理暂存（安装进行中不可取消）。 */
    fun cancelImport() {
        if (importInstalling) return
        importPlan = null
        importError = null
        cleanupImportFiles()
        // 下载交接的取消回下载页；SAF 导入的取消回主列表。
        if (importFromDownload) downloadPage = true
        importFromDownload = false
        importSource = SchemePackageImport.PackageSource.LOCAL
    }

    /** 清导入 / 下载交接的校验错误：下载交接的错误回下载页。 */
    fun clearImportError() {
        importError = null
        cleanupImportFiles()
        if (importFromDownload) downloadPage = true
        importFromDownload = false
    }

    /**
     * zip 已就绪后的公共尾巴：暂存校验 → 空间检查 → 校验结果页或错误页。
     * SAF 导入与下载交接共用（下载不另起管线）；fromDownload 决定登记
     * 来源与失败 / 取消后的回落页。调用方负责 importBusy 的置位与复位。
     */
    suspend fun validateZipFile(zipFile: File, packageName: String, fromDownload: Boolean) {
        importSource = if (fromDownload) {
            SchemePackageImport.PackageSource.URL
        } else {
            SchemePackageImport.PackageSource.LOCAL
        }
        importFromDownload = fromDownload
        // 多目录模型：落盘目标是包名对应的独立目录（初始目录与 build 名受保护，
        // 见 SchemeDirs.importDirName）；同名目录存在时确认页只提示替换一行。
        val targetDir = File(
            DataManager.schemeDirsRoot,
            SchemeDirs.importDirName(packageName),
        )
        val stagingDir = File(context.cacheDir, IMPORT_STAGING_DIR_NAME)
        val outcome = withContext(Dispatchers.IO) {
            buildImportPlan(context, zipFile, stagingDir, packageName, targetDir)
        }
        when (outcome) {
            is ImportPlanOutcome.Failed -> {
                importError = outcome.error
                cleanupImportFiles()
            }
            is ImportPlanOutcome.Ready -> {
                val available = runCatching {
                    StatFs(DataManager.schemeDirsRoot.path).availableBytes
                }.getOrNull() ?: Long.MAX_VALUE
                if (available <
                    outcome.plan.totalBytes + SchemePackageImport.FREE_SPACE_MARGIN_BYTES
                ) {
                    importError = ImportErrorKind.NO_SPACE
                    cleanupImportFiles()
                } else {
                    importPlan = outcome.plan
                    importTargetDir = targetDir
                    importStagingDir = stagingDir
                }
            }
        }
    }

    /**
     * SAF 选包后的校验：复制到临时 zip → 解到暂存目录 → 生成安装规划。
     * 剩余空间在规划出来后、安装前检查（解压后总体积 + 部署余量）。
     * 校验失败只置错误态、不动 user 目录；离开页面取消本协程时暂存
     * 文件残留 cacheDir，由系统清理（cacheDir 本就可随时清）。
     */
    fun runImportPick(uri: Uri) {
        if (importBusy || switchOrchestrator.isBusy()) return
        importBusy = true
        importError = null
        scope.launch {
            try {
                val displayName = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) cursor.getString(idx) else null
                            } else {
                                null
                            }
                        }
                    }.getOrNull()
                } ?: "scheme-package.zip"
                val tmpZip = File(context.cacheDir, IMPORT_TMP_ZIP_NAME)
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmpZip.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("openInputStream null")
                    }.getOrNull()
                }
                if (copied == null) {
                    importError = ImportErrorKind.COPY_FAILED
                    return@launch
                }
                validateZipFile(
                    tmpZip,
                    SchemePackageImport.sanitizePackageName(displayName),
                    fromDownload = false,
                )
            } finally {
                importBusy = false
            }
        }
    }


    /**
     * 确认安装（多目录模型）：同名目录先删后装（确认页已提示替换一行）；
     * 落盘到独立目录 + 写该目录方案表 + 登记（含目录名），再切到新目录
     * （指针 → 启用表 → 引擎重部署 → 首位方案，失败自动回滚上一目录）。
     * 旧单目录合并链就此取代：不再往当前目录合并、不再写当前目录方案表。
     * 安装失败留在校验页并可重试（落盘幂等、登记 upsert，重试安全）。
     */
    fun confirmImport() {
        val plan = importPlan ?: return
        val staging = importStagingDir ?: return
        val targetDir = importTargetDir ?: return
        if (importInstalling) return
        importInstalling = true
        importError = null
        scope.launch {
            try {
                val dirName = targetDir.name
                val installed = withContext(Dispatchers.IO + NonCancellable) {
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    val done = SchemePackageImport.applyPlan(
                        staging,
                        targetDir,
                        plan,
                    )
                    // 规划非空却零落盘 = 源路径对不上（如包装目录未处理）：
                    // 不写方案表、不登记、不切换，直接报安装失败留在页内重试，
                    // 杜绝“正在安装…→回列表、无报错、无方案”的静默失败。
                    if (done.isEmpty() && plan.installFiles.isNotEmpty()) {
                        throw IllegalStateException("scheme import installed nothing")
                    }
                    val tableIds = plan.defaultCustomSchemaIds.ifEmpty {
                        plan.schemes.map { it.id }
                    }
                    SchemeDirs.writeDefaultCustomSchemaList(targetDir, tableIds)
                    done
                }
                val registry = SchemePackageImport.decodeRegistry(
                    importPrefs.getString(SchemePackageImport.KEY_PACKAGE_REGISTRY, null),
                )
                val record = SchemePackageImport.SchemePackageRecord(
                    packageName = plan.packageName,
                    version = plan.packageVersion,
                    schemeIds = plan.schemes.map { it.id },
                    files = installed,
                    source = importSource,
                    dir = dirName,
                )
                importPrefs.edit {
                    putString(
                        SchemePackageImport.KEY_PACKAGE_REGISTRY,
                        SchemePackageImport.encodeRegistry(registry.withRecord(record)),
                    )
                }
                cleanupImportFiles()
                importPlan = null
                importInstalling = false
                // 安装成功后切到新目录（看切换结果），下载交接的来源标记复位。
                downloadPage = false
                importFromDownload = false
                importSource = SchemePackageImport.PackageSource.LOCAL
                switchDirectory(dirName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                importError = ImportErrorKind.INSTALL_FAILED
                importInstalling = false
            }
        }
    }

    // ---- 第三期远程下载：手动 URL → cacheDir 暂存 → 二期校验 / 安装 ----
    /** 下载条目读回（菜单打开下载页时刷新一次，之后改动即时写回）。 */
    fun loadDownloadEntries() {
        val entries = SchemeDownload.decodeDownloadEntries(
            downloadPrefs.getString(SchemeDownload.KEY_DOWNLOAD_ENTRIES, null),
        )
        downloadEntries.clear()
        downloadEntries.addAll(entries)
    }

    fun saveDownloadEntries() {
        downloadPrefs.edit {
            putString(
                SchemeDownload.KEY_DOWNLOAD_ENTRIES,
                SchemeDownload.encodeDownloadEntries(downloadEntries.toList()),
            )
        }
    }

    /** 打开下载表单：新增时空表单，行末下载时按条目预填（Header 永不预填）。 */
    fun openDownloadForm(prefill: SchemeDownload.DownloadEntry?) {
        downloadFormName = prefill?.name.orEmpty()
        downloadFormUrl = prefill?.url.orEmpty()
        downloadFormHeaders.clear()
        downloadFormHeaders.add("" to "")
        downloadFormError = null
        downloadFormOpen = true
    }

    /**
     * OkHttp 流式下载到目标文件（调用方在 IO 线程调）：声明长度超限
     * 直接拒，chunked 无长度时靠累计字节数兜底；声明长度与实际不符
     * 按不完整处理。取消由调用方以 Call.cancel() 中断读流，读流抛
     * 出的异常原样上抛、由调用方按 isActive 区分取消与网络错误。
     * 请求头内容绝不记日志（本函数无任何日志）。
     */
    fun downloadToFile(
        url: String,
        headers: List<Pair<String, String>>,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val builder = Request.Builder().url(url)
        headers.forEach { (headerName, headerValue) ->
            builder.header(headerName, headerValue)
        }
        val call = downloadClient.newCall(builder.build())
        downloadCall = call
        call.execute().use { response ->
            if (!response.isSuccessful) {
                return DownloadOutcome.Failed(
                    SchemeDownload.errorKindForHttp(response.code),
                    response.code,
                )
            }
            val body = response.body
                ?: return DownloadOutcome.Failed(
                    SchemeDownload.DownloadErrorKind.NETWORK,
                    0,
                )
            val total = body.contentLength()
            if (total > SchemePackageImport.MAX_ZIP_BYTES) {
                return DownloadOutcome.Failed(
                    SchemeDownload.DownloadErrorKind.TOO_LARGE,
                    0,
                )
            }
            var downloaded = 0L
            var reported = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded > SchemePackageImport.MAX_ZIP_BYTES) {
                            return DownloadOutcome.Failed(
                                SchemeDownload.DownloadErrorKind.TOO_LARGE,
                                0,
                            )
                        }
                        // 进度节流：256KB 一报 + 完成时一报，IO 线程直接写
                        // 快照态（线程安全），避免每 32KB 触发一次重组。
                        if (downloaded - reported >= 256 * 1024 ||
                            (total > 0 && downloaded >= total)
                        ) {
                            onProgress(downloaded, total)
                            reported = downloaded
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
            if (total > 0 && target.length() != total) {
                return DownloadOutcome.Failed(
                    SchemeDownload.DownloadErrorKind.INCOMPLETE,
                    0,
                )
            }
            return DownloadOutcome.Success
        }
    }

    /**
     * 发起下载：下载到导入流程共用的固定临时 zip（与 SAF 导入互斥，
     * 守门同口径），成功后走 validateZipFile 交接二期校验 / 安装；
     * 失败按下载口径明示并删暂存。离开页面取消协程时暂存残留
     * cacheDir，由系统清理（与二期同口径）。
     */
    fun startDownload(
        entryName: String,
        url: String,
        headers: List<Pair<String, String>>,
    ) {
        if (downloadRunning || importBusy || switchOrchestrator.isBusy()) return
        downloadRunning = true
        downloadError = null
        downloadProgressBytes = 0L
        downloadProgressTotal = -1L
        downloadProgressName = entryName
        val job = scope.launch {
            try {
                val tmpZip = File(context.cacheDir, IMPORT_TMP_ZIP_NAME)
                val outcome = withContext(Dispatchers.IO) {
                    downloadToFile(url, headers, tmpZip) { bytes, total ->
                        downloadProgressBytes = bytes
                        downloadProgressTotal = total
                    }
                }
                when (outcome) {
                    is DownloadOutcome.Success -> {
                        importBusy = true
                        try {
                            validateZipFile(
                                tmpZip,
                                SchemePackageImport.sanitizePackageName(entryName),
                                fromDownload = true,
                            )
                        } finally {
                            importBusy = false
                        }
                    }
                    is DownloadOutcome.Failed -> {
                        tmpZip.delete()
                        File(context.cacheDir, IMPORT_STAGING_DIR_NAME).deleteRecursively()
                        downloadError = DownloadErrorState(outcome.kind, outcome.httpCode)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                File(context.cacheDir, IMPORT_TMP_ZIP_NAME).delete()
                // 取消后 isActive 为 false：不报网络错。
                if (isActive) {
                    downloadError = DownloadErrorState(
                        SchemeDownload.DownloadErrorKind.NETWORK,
                        0,
                    )
                }
            } finally {
                downloadRunning = false
                downloadJob = null
                downloadCall = null
            }
        }
        downloadJob = job
    }

    /** 取消下载：中断 OkHttp 读流并取消协程，暂存由协程收尾清理。 */
    fun cancelDownload() {
        downloadCall?.cancel()
        downloadJob?.cancel()
    }

    /**
     * 表单提交：名 / URL / Header 校验通过后按名 upsert 条目（只存名 +
     * URL），随后立即用本次输入的 Header 发起下载——Header 不写偏好，
     * 本次下载用完即弃；行末再次下载时重新输入。
     */
    fun submitDownloadForm() {
        val name = downloadFormName.trim()
        if (name.isEmpty()) {
            downloadFormError = DownloadFormError.EMPTY_NAME
            return
        }
        val urlResult = SchemeDownload.validateDownloadUrl(downloadFormUrl)
        if (urlResult !is SchemeDownload.UrlValidation.Valid) {
            downloadFormError = DownloadFormError.BAD_URL
            return
        }
        val headersResult = SchemeDownload.buildHeaders(downloadFormHeaders.toList())
        if (headersResult !is SchemeDownload.HeadersResult.Ok) {
            downloadFormError = DownloadFormError.BAD_HEADER
            return
        }
        downloadFormError = null
        val entry = SchemeDownload.DownloadEntry(name, urlResult.url)
        val updated = SchemeDownload.upsertEntry(downloadEntries.toList(), entry)
        downloadEntries.clear()
        downloadEntries.addAll(updated)
        saveDownloadEntries()
        downloadFormOpen = false
        startDownload(entry.name, entry.url, headersResult.headers)
    }

    /** 删除下载条目（只删已存的名 + URL，不动已装方案与登记）。 */
    fun deleteDownloadEntry(entry: SchemeDownload.DownloadEntry) {
        downloadEntries.remove(entry)
        saveDownloadEntries()
    }


    // SAF 启动器（流程函数之后声明：本地函数不可前向引用）。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) runImportPick(uri)
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
            // 导入流程中顶栏跟随流程态：校验页标题为校验结果、返回即取消；
            // 等待 / 错误页标题为导入方案、返回即清错误；其余为一期原顶栏。
            // 校验页与等待页的返回在安装进行中禁用（落盘在 NonCancellable
            // 里跑，关页不中断安装，关了也无处回写状态）。
            // 下载交接的校验 / 错误页标题跟随下载方案（与导入区分）。
            val inImportFlow = importPlan != null || importError != null || importBusy
            val inDownloadFlow = downloadPage || downloadFormOpen ||
                downloadRunning || downloadError != null
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                importPlan != null -> R.string.schema_import
                                dirBrowser -> R.string.schema_dir_switch
                                inImportFlow ->
                                    if (importFromDownload) R.string.schema_download
                                    else R.string.schema_import
                                inDownloadFlow -> R.string.schema_download
                                else -> R.string.schema_settings
                            },
                        ),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                importPlan != null -> cancelImport()
                                importError != null -> clearImportError()
                                downloadFormOpen -> {
                                    downloadFormOpen = false
                                    downloadFormError = null
                                }
                                // 下载进行中返回即取消（尚未安装，取消安全）。
                                downloadRunning -> cancelDownload()
                                downloadError != null -> downloadError = null
                                downloadPage -> downloadPage = false
                                // 目录浏览：详情先回列表，列表再关浏览页。
                                dirDetail != null -> dirDetail = null
                                dirBrowser -> dirBrowser = false
                                // 管理页返回即放弃草稿（保存 pill 才是提交口）。
                                editMode -> editMode = false
                                else -> {
                                    saveOrder(prefs, currentDir, enabledSchemas)
                                    onBack()
                                }
                            }
                        },
                        enabled = !importInstalling && !importBusy && !dirSwitching,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    // 下载列表页右上角 ＋：新增下载条目（表单页 / 进行中 /
                    // 错误页不给，防流程中途切走）。
                    if (downloadPage && importPlan == null && !importBusy &&
                        importError == null && !downloadFormOpen &&
                        !downloadRunning && downloadError == null
                    ) {
                        IconButton(onClick = { openDownloadForm(null) }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.schema_download_add),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else if (inImportFlow || inDownloadFlow || dirBrowser) {
                        // 导入 / 下载流程页与目录浏览页不给菜单与编辑入口，
                        // 防流程中途切走状态。
                    } else if (!editMode) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.schema_menu_more),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            // 第三期新增「下载方案」：打开已保存下载条目列表。
                            // 切换 / 部署 / 导入校验 / 下载进行中禁用。
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schema_download)) },
                                enabled = !switchOrchestrator.isBusy() && !importBusy &&
                                    !downloadRunning,
                                onClick = {
                                    menuExpanded = false
                                    loadDownloadEntries()
                                    downloadPage = true
                                },
                            )
                            // 第二期新增「导入方案」：SAF 选 zip，走暂存校验流程。
                            // 切换 / 部署进行中禁用（维护期守卫与一期同口径）。
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schema_import)) },
                                enabled = !switchOrchestrator.isBusy() && !importBusy &&
                                    !downloadRunning,
                                onClick = {
                                    menuExpanded = false
                                    importLauncher.launch(
                                        arrayOf(
                                            "application/zip",
                                            "application/x-zip-compressed",
                                        ),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schema_list_edit)) },
                                onClick = {
                                    menuExpanded = false
                                    editMode = true
                                },
                            )
                            // 多目录新增「方案目录切换」：浏览根下各方案目录，
                            // 点“使用此文件夹”整套切换并重新部署。
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schema_dir_switch)) },
                                enabled = !switchOrchestrator.isBusy() && !importBusy &&
                                    !downloadRunning,
                                onClick = {
                                    menuExpanded = false
                                    dirDetail = null
                                    dirSwitchError = null
                                    dirBrowser = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        // 导入流程页整体替换列表内容（校验页 / 等待页 / 错误页）。
        if (importPlan != null) {
            ImportValidationPage(
                plan = importPlan!!,
                targetDirExists = importTargetDir?.exists() == true,
                installing = importInstalling,
                error = importError,
                onConfirm = { confirmImport() },
                onCancel = { cancelImport() },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            )
            return@Scaffold
        }
        if (importBusy) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.schema_import_validating),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            return@Scaffold
        }
        if (importError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    importErrorText(importError!!),
                    fontSize = rowFontSize,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        clearImportError()
                    },
                ) {
                    Text(stringResource(R.string.schema_banner_close))
                }
            }
            return@Scaffold
        }
        // 第三期下载页整体替换列表内容（表单 / 进度 / 错误 / 条目列表）。
        // 导入交接分支在前：下载成功后的校验 / 安装仍走二期页面。
        if (downloadFormOpen) {
            // 新增表单：分组卡片形态——标签卡片 + 卡内输入框，
            // HTTP 请求头区 + 添加 Header、深色保存钮。保存即保存条目
            // 并立即用本次 Header 下载（Phase 3 逻辑不变）。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        InCardTextField(
                            label = stringResource(R.string.schema_download_name),
                            value = downloadFormName,
                            onValueChange = { downloadFormName = it },
                        )
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(8.dp))
                        InCardTextField(
                            label = stringResource(R.string.schema_download_url),
                            value = downloadFormUrl,
                            onValueChange = { downloadFormUrl = it },
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.schema_download_header),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { downloadFormHeaders.add("" to "") },
                            ) {
                                Text(stringResource(R.string.schema_download_add_header))
                            }
                        }
                        downloadFormHeaders.forEachIndexed { index, header ->
                            if (index > 0) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant
                                        .copy(alpha = 0.5f),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    InCardTextField(
                                        label = stringResource(
                                            R.string.schema_download_header_name,
                                        ),
                                        value = header.first,
                                        onValueChange = { value ->
                                            downloadFormHeaders[index] = value to header.second
                                        },
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    InCardTextField(
                                        label = stringResource(
                                            R.string.schema_download_header_value,
                                        ),
                                        value = header.second,
                                        onValueChange = { value ->
                                            downloadFormHeaders[index] =
                                                header.first to value
                                        },
                                    )
                                }
                                IconButton(
                                    onClick = { downloadFormHeaders.removeAt(index) },
                                    enabled = downloadFormHeaders.size > 1,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(
                                            R.string.schema_download_header_remove,
                                        ),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (downloadFormError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        downloadFormErrorText(downloadFormError!!),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { submitDownloadForm() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ),
                ) {
                    Text(stringResource(R.string.schema_download_save))
                }
                TextButton(
                    onClick = {
                        downloadFormOpen = false
                        downloadFormError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.schema_download_cancel))
                }
                Spacer(Modifier.height(16.dp))
            }
            return@Scaffold
        }
        if (downloadRunning) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    downloadProgressName,
                    fontSize = rowFontSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.schema_download_downloading),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (downloadProgressTotal > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (downloadProgressBytes.toFloat() / downloadProgressTotal.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatSize(downloadProgressBytes) + " / " +
                            formatSize(downloadProgressTotal),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatSize(downloadProgressBytes),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { cancelDownload() }) {
                    Text(stringResource(R.string.schema_download_cancel_download))
                }
            }
            return@Scaffold
        }
        if (downloadError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    downloadErrorText(downloadError!!),
                    fontSize = rowFontSize,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { downloadError = null }) {
                    Text(stringResource(R.string.schema_banner_close))
                }
            }
            return@Scaffold
        }
        if (downloadPage) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                if (downloadEntries.isEmpty()) {
                    Text(
                        stringResource(R.string.schema_download_empty),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 下载列表：一张分组卡片，行 = 名 + 中间截断 URL +
                    // 蓝色下载圆钮；行点按与圆钮同义（打开预填表单补 Header
                    // 后下载——Header 从不持久化，故重下载必经表单）。
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column {
                            downloadEntries.forEachIndexed { index, entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openDownloadForm(entry) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.name,
                                            fontSize = 16.sp,
                                            lineHeight = 21.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            entry.url,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.MiddleEllipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .clickable { openDownloadForm(entry) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Download,
                                            contentDescription = stringResource(
                                                R.string.schema_download_row_download,
                                            ),
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                    IconButton(onClick = { deleteDownloadEntry(entry) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.scale(0.8f),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (index < downloadEntries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            return@Scaffold
        }
        // 多目录浏览页：根下目录列表 / 目录详情（方案表）+ 底部使用栏。
        if (dirBrowser) {
            SchemeDirBrowserContent(
                currentDir = currentDir,
                detailDir = dirDetail,
                onOpenDetail = { dirDetail = it },
                onBackToList = { dirDetail = null },
                switching = dirSwitching ||
                    monitorStatus.phase == DirSwitchPhase.SWITCHING,
                onUseDir = { switchDirectory(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            )
            return@Scaffold
        }
        // 空表不再永久空白：失败提示放门外（空白态也能看到），
        // 切换中 / 部署中显进度 + 自动重载进行中，另给手动重试。
        if (!loaded) {
            val switchErrorText = dirSwitchError
                ?: monitorStatus.takeIf { it.phase == DirSwitchPhase.FAILED }
                    ?.errorMessage?.takeIf { it.isNotEmpty() }
            val switchingNow = monitorStatus.phase == DirSwitchPhase.SWITCHING
            val diskEmpty = remember(currentDir) {
                runCatching {
                    SchemeDirs.schemesInDir(File(DataManager.schemeDirsRoot, currentDir)).isEmpty()
                }.getOrDefault(false)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                if (switchErrorText != null) {
                    DirSwitchErrorCard(
                        message = switchErrorText,
                        onClose = {
                            dirSwitchError = null
                            DirSwitchMonitor.clear()
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (switchingNow) {
                    Text(
                        stringResource(R.string.schema_switching),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (!diskEmpty) {
                    Text(
                        stringResource(R.string.schema_deploying),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        scope.launch {
                            loadSchemas()
                            if (!loaded) {
                                resumeIfStranded()
                                loadSchemas()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.schema_switch_retry))
                    }
                } else {
                    Text(
                        stringResource(R.string.schema_dir_empty_schemes),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // 行元数据：切换失败 / 回退横幅按 id 找名字用。
            val allRowItems = enabledSchemas + availableSchemas
            // 忙态派生自组合态镜像（不要直接读状态机的非 State 字段作组合判断）
            val switchBusy = switchState is SchemeSwitchState.Switching ||
                switchState is SchemeSwitchState.Deploying ||
                switchState is SchemeSwitchState.RollingBack
            // 切换 / 部署 / 回退横幅：Idle 时不占位（视觉收敛，逻辑不动）
            when (val s = switchState) {
                is SchemeSwitchState.Switching -> {
                    Text(
                        stringResource(R.string.schema_switching),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SchemeSwitchState.Deploying -> {
                    Column {
                        Text(
                            stringResource(R.string.schema_deploying),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is SchemeSwitchState.RollingBack -> {
                    Column {
                        Text(
                            stringResource(R.string.schema_rolling_back),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is SchemeSwitchState.Failed -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.schema_switch_failed) + "：" + s.targetId,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Row {
                                val targetItem =
                                    allRowItems.firstOrNull { it.id == s.targetId }
                                if (targetItem != null) {
                                    TextButton(onClick = { switchTo(targetItem) }) {
                                        Text(stringResource(R.string.schema_switch_retry))
                                    }
                                }
                                TextButton(onClick = {
                                    switchOrchestrator.dismiss()
                                    refreshSwitchState()
                                }) {
                                    Text(stringResource(R.string.schema_banner_close))
                                }
                            }
                        }
                    }
                }
                is SchemeSwitchState.RolledBack -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            val prevName = allRowItems.firstOrNull { it.id == s.previousId }
                                ?.let { it.name.ifBlank { it.id } } ?: s.previousId
                            Text(
                                stringResource(R.string.schema_rolled_back) + "：" + prevName,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = {
                                switchOrchestrator.dismiss()
                                refreshSwitchState()
                            }) {
                                Text(stringResource(R.string.schema_banner_close))
                            }
                        }
                    }
                }
                else -> {}
            }
            // 目录切换失败提示：回滚已完成，留一条可关闭的说明。
            // （同一卡片亦用于 !loaded 空白态，见门外分支。）
            if (dirSwitchError != null) {
                Spacer(Modifier.height(8.dp))
                DirSwitchErrorCard(
                    message = dirSwitchError!!,
                    onClose = { dirSwitchError = null },
                )
            }
            if (editMode) {
                // 管理页：已选卡（勾选 + 拖拽合一）+ 全部方案区；草稿只在
                // 保存时提交，返回放弃。保存 = 写启用表与目录方案表后走
                // 可见的部署进度框，部署失败自动恢复原顺序并明示。
                SchemaManageContent(
                    allItems = enabledSchemas + availableSchemas,
                    initialEnabled = enabledSchemas.toList(),
                    onSave = { newEnabled ->
                        val prevEnabled = enabledSchemas.toList()
                        val prevYaml = SchemeDirs.schemesInDir(DataManager.userDataDir)
                        val all = enabledSchemas + availableSchemas
                        val enabledIds = newEnabled.map { it.id }.toSet()
                        val newAvailable = all.filter { it.id !in enabledIds }
                        enabledSchemas.clear()
                        enabledSchemas.addAll(newEnabled)
                        availableSchemas.clear()
                        availableSchemas.addAll(newAvailable)
                        SchemaManager.saveEnabledIds(
                            prefs,
                            currentDir,
                            newEnabled.map { it.id },
                        )
                        SchemeDirs.writeDefaultCustomSchemaList(
                            DataManager.userDataDir,
                            newEnabled.map { it.id } + newAvailable.map { it.id },
                        )
                        manageDeploying = true
                        manageDeployError = null
                        scope.launch {
                            try {
                                EngineFactory.current()?.reload()
                                val first = newEnabled.firstOrNull()
                                if (first != null) {
                                    EngineFactory.current()?.selectSchema(first.id)
                                    if (!verifyCurrent(first.id, 180_000)) {
                                        throw IllegalStateException("manage deploy timeout")
                                    }
                                }
                                manageDeploying = false
                                editMode = false
                                loadSchemas()
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                runCatching {
                                    SchemaManager.saveEnabledIds(
                                        prefs,
                                        currentDir,
                                        prevEnabled.map { it.id },
                                    )
                                    SchemeDirs.writeDefaultCustomSchemaList(
                                        DataManager.userDataDir,
                                        prevYaml,
                                    )
                                    EngineFactory.current()?.reload()
                                    prevEnabled.firstOrNull()?.let {
                                        EngineFactory.current()?.selectSchema(it.id)
                                    }
                                }
                                manageDeploying = false
                                manageDeployError = context.getString(
                                    R.string.schema_manage_deploy_failed,
                                )
                                scope.launch { loadSchemas() }
                            }
                        }
                    },
                )
            } else {
                // 已启用列表：一张分组卡片，行只留名 + 灰字 id，无芯片、
                // 无默认标记；行点按切换。当前方案由列表首行高亮表达
                // （首行底色 + 无障碍状态描述，不另立当前卡、不加标签）；
                // loadSchemas 已把当前方案移到首位。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column {
                        enabledSchemas.forEachIndexed { index, schema ->
                            val isCurrentRow = index == 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {
                                        if (isCurrentRow) {
                                            stateDescription = "当前方案"
                                        }
                                    }
                                    .clickable(enabled = !switchBusy) { switchTo(schema) }
                                    .background(
                                        if (isCurrentRow) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        schema.name.ifBlank { schema.id },
                                        fontSize = 16.sp,
                                        lineHeight = 21.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isCurrentRow) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (schema.name.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            schema.id,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = if (isCurrentRow) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                    .copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (index < enabledSchemas.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.schema_enabled_only_footnote),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        }
    }

    // 管理页保存的部署进度框：部署中不可关闭；失败时明示并可关闭
    // （顺序已自动恢复，见 onSave 回滚）。
    if (manageDeploying) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.schema_manage_save)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.schema_manage_deploying),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }
    if (manageDeployError != null) {
        AlertDialog(
            onDismissRequest = { manageDeployError = null },
            title = { Text(stringResource(R.string.schema_manage_save)) },
            text = { Text(manageDeployError!!) },
            confirmButton = {
                TextButton(onClick = { manageDeployError = null }) {
                    Text(stringResource(R.string.schema_banner_close))
                }
            },
        )
    }
}

/** 分组卡片内的输入框：上方小字标签 + 无框输入行（表单卡片语言）。 */
@Composable
private fun InCardTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(
                fontSize = 16.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 方案列表管理页内容（平铺逻辑）：已选卡 = 勾选 + 拖拽合一（行 = 蓝勾选圈
 * + 名 + 拖拽柄，长按拖动排序，点圈取消勾选）；全部方案区 = 未选方案，
 * 点圈即回已选末尾。草稿只活在本页，保存才提交；返回放弃。
 * 当前方案不锁：取消勾选当前方案后首位剩余方案接任当前；草稿为空时
 * 保存禁用（至少保留一个方案，不可出现无当前方案状态）。
 */
@Composable
private fun SchemaManageContent(
    allItems: List<SchemaItem>,
    initialEnabled: List<SchemaItem>,
    onSave: (List<SchemaItem>) -> Unit,
) {
    // 草稿按 initialEnabled 重建：RIME 页直达管理页时，列表可能在
    // 首次合成后才加载完；无 key 的 remember 会把空列表锁成草稿。
    // （initialEnabled 只在加载/保存时变，编辑中途不会变，故不丢用户编辑。）
    val draftEnabled = remember(initialEnabled) {
        mutableStateListOf<SchemaItem>().apply { addAll(initialEnabled) }
    }
    val draftIds = draftEnabled.map { it.id }.toSet()
    val draftAvailable = allItems.filter { it.id !in draftIds }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.schema_manage_selected),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSave(draftEnabled.toList()) },
                enabled = draftEnabled.isNotEmpty(),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.schema_manage_save), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            ManageEnabledList(
                items = draftEnabled,
                onUncheck = { item -> draftEnabled.removeAll { it.id == item.id } },
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.schema_manage_all),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column {
                draftAvailable.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { draftEnabled.add(item) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .border(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name.ifBlank { item.id },
                                fontSize = 16.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.name.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    item.id,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (index < draftAvailable.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.schema_manage_footnote),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 已选卡的拖拽列表：行 = 蓝勾选圈（点按取消勾选）+ 名 + 拖拽柄
 * （长按拖动排序）。拖拽机制与旧 ReorderableSchemaList 同款
 * （长按后跟手位移、落点按行高取整、拖动时微放大 + 阴影）。
 */
@Composable
private fun ManageEnabledList(
    items: MutableList<SchemaItem>,
    onUncheck: (SchemaItem) -> Unit,
) {
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
                                if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                                val target = (dragIndex + (dragOffset / itemHeight).roundToInt())
                                    .coerceIn(0, items.size - 1)
                                if (target != dragIndex) {
                                    val item = items.removeAt(dragIndex)
                                    items.add(target, item)
                                }
                                dragIndex = -1
                            },
                            onDrag = { change, amount ->
                                if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                                change.consume()
                                dragOffset += amount.y
                            },
                        )
                    },
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { onUncheck(schema) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                schema.name.ifBlank { schema.id },
                                fontSize = 16.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (schema.name.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    schema.id,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
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
                    }
                    if (index < items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemaListItem(
    schema: SchemaItem,
    onClick: (() -> Unit)? = null,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                schema.name.ifBlank { schema.id },
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (schema.name.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    schema.id,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingIcon()
    }
}

private fun saveOrder(prefs: SharedPreferences, dir: String, schemas: List<SchemaItem>) {
    SchemaManager.saveEnabledIds(prefs, dir, schemas.map { it.id })
}

// ---- 第二期本地导入：校验结果页与规划构造（界面层，规划逻辑见 SchemePackageImport） ----

/** 导入错误种类（界面文案据此映射，规划层只给原因枚举）。 */
private enum class ImportErrorKind {
    COPY_FAILED,
    NOT_ZIP,
    NO_SCHEMA,
    TOO_LARGE,
    NO_SPACE,
    VALIDATE_FAILED,
    INSTALL_FAILED,
}

/** 下载阶段结果：成功即已落到临时 zip；失败带口径与 HTTP 状态码。 */
private sealed interface DownloadOutcome {
    data object Success : DownloadOutcome
    data class Failed(
        val kind: SchemeDownload.DownloadErrorKind,
        val httpCode: Int,
    ) : DownloadOutcome
}

/** 下载错误页状态（校验 / 安装阶段失败仍走 ImportErrorKind）。 */
private data class DownloadErrorState(
    val kind: SchemeDownload.DownloadErrorKind,
    val httpCode: Int,
)

/** 下载表单校验错误（界面据此映射文案，不直接拼字符串进状态）。 */
private enum class DownloadFormError {
    EMPTY_NAME,
    BAD_URL,
    BAD_HEADER,
}

@Composable
private fun downloadFormErrorText(error: DownloadFormError): String = when (error) {
    DownloadFormError.EMPTY_NAME -> stringResource(R.string.schema_download_error_empty_name)
    DownloadFormError.BAD_URL -> stringResource(R.string.schema_download_error_url)
    DownloadFormError.BAD_HEADER -> stringResource(R.string.schema_download_error_header)
}

@Composable
private fun downloadErrorText(error: DownloadErrorState): String = when (error.kind) {
    SchemeDownload.DownloadErrorKind.NETWORK ->
        stringResource(R.string.schema_download_error_network)
    SchemeDownload.DownloadErrorKind.HTTP_STATUS ->
        stringResource(R.string.schema_download_error_http, error.httpCode)
    SchemeDownload.DownloadErrorKind.REDIRECT_DOWNGRADE ->
        stringResource(R.string.schema_download_error_redirect)
    // 体积上限与二期同口径、同文案（zip ≤ 512MB）。
    SchemeDownload.DownloadErrorKind.TOO_LARGE ->
        stringResource(R.string.schema_import_error_too_large)
    SchemeDownload.DownloadErrorKind.INCOMPLETE ->
        stringResource(R.string.schema_download_error_incomplete)
}

/** SAF 选包后复制到的临时 zip 文件名（cacheDir 下，用完即删）。 */
private const val IMPORT_TMP_ZIP_NAME = "scheme-import.zip"

/** zip 解压暂存目录名（cacheDir 下，用完即删）。 */
private const val IMPORT_STAGING_DIR_NAME = "scheme-import-staging"

private sealed interface ImportPlanOutcome {
    data class Ready(val plan: SchemeImportPlan) : ImportPlanOutcome
    data class Failed(val error: ImportErrorKind) : ImportPlanOutcome
}

/**
 * 由临时 zip 构造安装规划（IO 线程调用）：解压 → 登记读回 → 目标目录
 * 现存文件盘点 → 规划。失败时暂存目录已由 extractZip 清掉或为空，调用方
 * 再调 cleanupImportFiles 兜底。包名由调用方从 SAF 显示名净化后传入
 * （临时 zip 文件名固定，不能作包名）。多目录模型下现存文件按目标独立
 * 目录盘点（新目录为空表，替换安装按目录内现存文件算）。
 */
private fun buildImportPlan(
    context: Context,
    zipFile: File,
    stagingDir: File,
    packageName: String,
    targetDir: File,
): ImportPlanOutcome {
    val extracted = when (val result = SchemePackageImport.extractZip(zipFile, stagingDir)) {
        is SchemePackageImport.ExtractResult.Ok -> result.extracted
        is SchemePackageImport.ExtractResult.Failed -> return ImportPlanOutcome.Failed(
            when (result.reason) {
                SchemePackageImport.ExtractFailure.NOT_ZIP -> ImportErrorKind.NOT_ZIP
                SchemePackageImport.ExtractFailure.ZIP_TOO_LARGE,
                SchemePackageImport.ExtractFailure.ENTRY_TOO_LARGE,
                SchemePackageImport.ExtractFailure.TOTAL_TOO_LARGE -> ImportErrorKind.TOO_LARGE
            },
        )
    }
    val staged = SchemePackageImport.stagedPackageOf(stagingDir, extracted.entries)
    val preSkipped = extracted.skippedTraversal.map {
        SchemePackageImport.SkippedFile(it, SchemePackageImport.SkipReason.TRAVERSAL)
    }
    val registry = SchemePackageImport.decodeRegistry(
        context.getSharedPreferences(
            SchemePackageImport.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).getString(SchemePackageImport.KEY_PACKAGE_REGISTRY, null),
    )
    val existingUser = SchemePackageImport.listRelativeFiles(targetDir)
    val existingSharedIds = DataManager.sharedDataDir.listFiles()
        ?.mapNotNull { file ->
            file.name.removeSuffix(".schema.yaml")
                .takeIf { file.name.endsWith(".schema.yaml") }
        }?.toSet().orEmpty()
    return when (
        val result = SchemePackageImport.planInstall(
            packageName = packageName,
            zipBytes = zipFile.length(),
            staged = staged,
            preSkipped = preSkipped,
            existingUserFiles = existingUser,
            existingSharedSchemeIds = existingSharedIds,
            registry = registry,
        )
    ) {
        is PlanResult.Success -> ImportPlanOutcome.Ready(result.plan)
        is PlanResult.Failed -> ImportPlanOutcome.Failed(
            when (result.reason) {
                SchemePackageImport.PlanFailure.NO_SCHEMA -> ImportErrorKind.NO_SCHEMA
                SchemePackageImport.PlanFailure.ZIP_TOO_LARGE,
                SchemePackageImport.PlanFailure.ENTRY_TOO_LARGE,
                SchemePackageImport.PlanFailure.TOTAL_TOO_LARGE -> ImportErrorKind.TOO_LARGE
            },
        )
    }
}

@Composable
private fun importErrorText(error: ImportErrorKind): String = when (error) {
    ImportErrorKind.COPY_FAILED -> stringResource(R.string.schema_import_error_copy)
    ImportErrorKind.NOT_ZIP -> stringResource(R.string.schema_import_error_zip)
    ImportErrorKind.NO_SCHEMA -> stringResource(R.string.schema_import_error_no_schema)
    ImportErrorKind.TOO_LARGE -> stringResource(R.string.schema_import_error_too_large)
    ImportErrorKind.NO_SPACE -> stringResource(R.string.schema_import_error_no_space)
    ImportErrorKind.VALIDATE_FAILED -> stringResource(R.string.schema_import_error_validate)
    ImportErrorKind.INSTALL_FAILED -> stringResource(R.string.schema_import_error_install)
}

/**
 * 导入确认页（轻量）：只给用户真正需要的信息——包名、包内方案数、
 * 同名目录替换一行（存在时）；确认安装 / 取消；安装中进度与失败提示。
 * 文件明细、逐行保留/跳过清单、“校验结果”措辞全部去掉。
 */
@Composable
private fun ImportValidationPage(
    plan: SchemeImportPlan,
    targetDirExists: Boolean,
    installing: Boolean,
    error: ImportErrorKind?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    plan.packageName,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.schema_import_summary_schemes, plan.schemes.size),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (targetDirExists) {
            Spacer(Modifier.height(12.dp))
            ScreenComponent.WarningCard {
                Text(
                    stringResource(R.string.schema_import_replace_dir),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ScreenComponent.warningContentColor(),
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                importErrorText(error),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (installing) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.schema_import_installing),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onConfirm,
            enabled = !installing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.schema_import_confirm))
        }
        TextButton(
            onClick = onCancel,
            enabled = !installing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.schema_import_cancel))
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 方案目录显示名：初始目录显示“默认”，其余显示目录名原样。 */
@Composable
private fun dirDisplayName(name: String): String =
    if (name == SchemeDirs.INITIAL_DIR) {
        stringResource(R.string.schema_dir_default_name)
    } else {
        name
    }

/**
 * 方案目录浏览页（目录切换结构，本页只做目录级操作）：
 * 列表 = 根下各方案目录（名 + 方案数 + 当前标识），点行进详情；
 * 详情 = 面包屑 + 该目录方案表 + 底部栏（当前方案目录 + 使用此文件夹）。
 * 使用 = 整套切换并重新部署（调用方 switchDirectory，失败自动回滚）。
 */
@Composable
private fun SchemeDirBrowserContent(
    currentDir: String,
    detailDir: String?,
    onOpenDetail: (String) -> Unit,
    onBackToList: () -> Unit,
    switching: Boolean,
    onUseDir: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val root = DataManager.schemeDirsRoot
    // 浏览器每次打开都是新组合；切换成功即关页，失败时列表不变，无需动态刷新。
    val dirs = remember { SchemeDirs.listDirs(root) }
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (detailDir == null) {
                if (dirs.isEmpty()) {
                    Text(
                        stringResource(R.string.schema_dir_empty),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column {
                            dirs.forEachIndexed { index, dir ->
                                val name = dir.name
                                val count = SchemeDirs.schemesInDir(dir).size
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenDetail(name) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            dirDisplayName(name),
                                            fontSize = 16.sp,
                                            lineHeight = 21.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.schema_dir_schemes, count),
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (name == currentDir) {
                                        Text(
                                            stringResource(R.string.schema_dir_current_badge),
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.3f),
                                    )
                                }
                                if (index < dirs.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val schemes = SchemeDirs.schemesInDir(File(root, detailDir))
                Text(
                    stringResource(R.string.schema_dir_breadcrumb, dirDisplayName(detailDir)),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (schemes.isEmpty()) {
                    Text(
                        stringResource(R.string.schema_dir_empty_schemes),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column {
                            schemes.forEachIndexed { index, id ->
                                Text(
                                    id,
                                    fontSize = 16.sp,
                                    lineHeight = 21.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                                if (index < schemes.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // 底部栏（固定位置）：当前方案目录 + 使用此文件夹；列表层
        // 无选中目标时按钮置灰，详情层选中非当前目录时可用。
        if (switching) {
            Text(
                stringResource(R.string.schema_dir_switching),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.schema_dir_current, dirDisplayName(currentDir)),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val target = detailDir ?: return@Button
                    onUseDir(target)
                },
                enabled = detailDir != null && detailDir != currentDir && !switching,
            ) {
                Text(stringResource(R.string.schema_dir_use))
            }
        }
    }
}

/**
 * 目录切换失败提示卡：加载态与已加载态共用，失败提示不再被
 * `if (!loaded)` 门挡住（空白页上也能看到）。
 */
@Composable
private fun DirSwitchErrorCard(message: String, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                message,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.schema_banner_close))
            }
        }
    }
}
