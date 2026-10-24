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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.jobeen.ime.data.manager.SchemeArchive
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

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
    // 第一期切换编排：状态机是转移权威，switchState 是其组合态镜像
    // （状态机本身不是 State，页内变化须经 refreshSwitchState 同步，
    // 否则横幅与忙态不会重组）
    val switchOrchestrator = remember { SchemeSwitchOrchestrator() }
    var switchState by remember { mutableStateOf<SchemeSwitchState>(SchemeSwitchState.Idle) }
    var editMode by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var currentSchemeId by remember { mutableStateOf("") }
    var versionById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sourceById by remember {
        mutableStateOf<Map<String, SchemeSwitchPolicy.SchemeSource>>(emptyMap())
    }
    var localInfo by remember { mutableStateOf<WanxiangUpdateManager.LocalInfo?>(null) }
    val scope = rememberCoroutineScope()
    // ---- 第二期本地导入：SAF 选 zip → 暂存校验 → 校验结果页 → 确认安装 ----
    // importPlan 非空时 Scaffold 内容整体切到校验结果页（见下方内容分支）；
    // importError 非空且无 plan 时切到错误页；importBusy 时切到等待页。
    var importPlan by remember { mutableStateOf<SchemeImportPlan?>(null) }
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
    // ---- 第四期归档 / 恢复逃生舱：归档集列表 / 恢复确认（非一等入口） ----
    // 归档页整体替换列表内容（与下载页同模式）；流程页内无菜单，
    // 切换 / 导入 / 下载在归档页打开期间不可达；归档 / 恢复文件
    // 操作进行中返回禁用（与二期安装 NonCancellable 口径一致）。
    var archivePage by remember { mutableStateOf(false) }
    val archiveEntries = remember { mutableStateListOf<ArchiveListItem>() }
    var archiveWorking by remember { mutableStateOf(false) }
    var archiveError by remember { mutableStateOf<ArchiveErrorKind?>(null) }
    var archiveNameDialog by remember { mutableStateOf(false) }
    var archiveNameInput by remember { mutableStateOf("") }
    var archiveDeleteTarget by remember { mutableStateOf<ArchiveListItem?>(null) }
    var restoreTarget by remember { mutableStateOf<ArchiveListItem?>(null) }
    var restorePlan by remember { mutableStateOf<SchemeArchive.RestorePlan?>(null) }
    var restoreInputs by remember { mutableStateOf<RestoreInputs?>(null) }
    var restoreOverwriteUserdb by remember { mutableStateOf(false) }
    var restoreWorking by remember { mutableStateOf(false) }
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

    LaunchedEffect(Unit) {
        loadSchemas()
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
        saveOrder(prefs, enabledSchemas)
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
     * 离开页面取消本协程：顺序已写回、引擎侧以实际状态为准，重进页面
     * 按实际当前方案重算，不留半吊子。
     */
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
        scope.launch {
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

    // ---- 第二期本地导入流程（SAF 选 zip → 暂存校验 → 确认安装 → 一期切换） ----
    /** 删掉本次导入的暂存文件（暂存目录与临时 zip，按名删，状态未赋值也覆盖）。 */
    fun cleanupImportFiles() {
        importStagingDir?.deleteRecursively()
        importStagingDir = null
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
        val stagingDir = File(context.cacheDir, IMPORT_STAGING_DIR_NAME)
        val outcome = withContext(Dispatchers.IO) {
            buildImportPlan(context, zipFile, stagingDir, packageName)
        }
        when (outcome) {
            is ImportPlanOutcome.Failed -> {
                importError = outcome.error
                cleanupImportFiles()
            }
            is ImportPlanOutcome.Ready -> {
                val available = runCatching {
                    StatFs(DataManager.userDataDir.path).availableBytes
                }.getOrNull() ?: Long.MAX_VALUE
                if (available <
                    outcome.plan.totalBytes + SchemePackageImport.FREE_SPACE_MARGIN_BYTES
                ) {
                    importError = ImportErrorKind.NO_SPACE
                    cleanupImportFiles()
                } else {
                    importPlan = outcome.plan
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
     * 确认安装：落盘 + 登记在 NonCancellable 里做（短本地 IO，页面离开
     * 也不留半安装）；登记写偏好后把新方案放进未启用列表，再走一期
     * switchTo（首个方案移到首位，部署 / 回退编排全复用一期）。
     * 回退时顺序恢复为安装前，新方案退回未启用——文件与登记保留，
     * 卸载按集合清是后续期数的事，本期不删文件。
     * 安装失败留在校验页并可重试（归档首次胜出、落盘幂等，重试安全）。
     */
    fun confirmImport() {
        val plan = importPlan ?: return
        val staging = importStagingDir ?: return
        if (importInstalling) return
        importInstalling = true
        importError = null
        scope.launch {
            try {
                val installed = withContext(Dispatchers.IO + NonCancellable) {
                    val archiveDir = File(
                        DataManager.userDataDir,
                        "${SchemePackageImport.ARCHIVE_DIR_NAME}/" +
                            "${plan.packageName}-${System.currentTimeMillis()}",
                    )
                    SchemePackageImport.applyPlan(
                        staging,
                        DataManager.userDataDir,
                        archiveDir,
                        plan,
                    )
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
                )
                importPrefs.edit {
                    putString(
                        SchemePackageImport.KEY_PACKAGE_REGISTRY,
                        SchemePackageImport.encodeRegistry(registry.withRecord(record)),
                    )
                }
                val newItems = plan.schemes.map { SchemaItem(it.id, it.name, it.layout) }
                newItems.forEach { item ->
                    if (availableSchemas.none { it.id == item.id } &&
                        enabledSchemas.none { it.id == item.id }
                    ) {
                        availableSchemas.add(item)
                    }
                }
                cleanupImportFiles()
                importPlan = null
                importInstalling = false
                // 安装成功后回主列表（看切换横幅），下载交接的来源标记复位。
                downloadPage = false
                importFromDownload = false
                importSource = SchemePackageImport.PackageSource.LOCAL
                val target = newItems.firstOrNull()
                if (target != null) {
                    switchTo(target) { scope.launch { loadSchemas() } }
                } else {
                    loadSchemas()
                }
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

    // ---- 第四期归档 / 恢复逃生舱（列表 / 新建归档 / 恢复确认 / 删除） ----
    /** 归档区根目录（user 层下，与二期同 ID 替换归档同区）。 */
    fun archiveRootDir(): File =
        File(DataManager.userDataDir, SchemePackageImport.ARCHIVE_DIR_NAME)

    /** 归档默认名：方案集 + 当前日期时间（用户可在对话框里改）。 */
    fun defaultArchiveName(): String =
        "方案集 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))

    /** 归档时间展示：清单里的 createdAtMs 转日期时间。 */
    fun formatArchiveTime(createdAtMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(createdAtMs))

    /** 刷新归档集列表：只认带四期清单的目录（二期替换归档无清单，不混进列表）。 */
    fun refreshArchiveEntries() {
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                val root = archiveRootDir()
                if (!root.isDirectory) {
                    emptyList()
                } else {
                    root.listFiles()
                        ?.filter { it.isDirectory }
                        ?.mapNotNull { dir ->
                            val text = runCatching {
                                File(dir, SchemeArchive.MANIFEST_FILE_NAME).readText()
                            }.getOrNull()
                            SchemeArchive.decodeManifest(text)?.let { manifest ->
                                ArchiveListItem(
                                    dirName = dir.name,
                                    manifest = manifest,
                                    timeText = formatArchiveTime(manifest.createdAtMs),
                                )
                            }
                        }
                        ?.sortedByDescending { it.manifest.createdAtMs }
                        .orEmpty()
                }
            }
            archiveEntries.clear()
            archiveEntries.addAll(entries)
        }
    }

    /**
     * 新建归档：当前 user 层方案集快照进归档区（NonCancellable 拷文件，
     * 失败删未完成目录后可重试；同名目录已存在时拒绝覆盖）。
     * 归档方案 id 取用户来源方案行，登记为空时回落登记里的方案 id。
     */
    fun createArchive() {
        if (archiveWorking) return
        archiveWorking = true
        archiveError = null
        val name = archiveNameInput.trim().ifEmpty { defaultArchiveName() }
        val userSchemeIds = (enabledSchemas + availableSchemas)
            .filter { sourceById[it.id] == SchemeSwitchPolicy.SchemeSource.USER }
            .map { it.id }
            .ifEmpty {
                SchemePackageImport.decodeRegistry(
                    importPrefs.getString(SchemePackageImport.KEY_PACKAGE_REGISTRY, null),
                ).records.flatMap { it.schemeIds }
            }
        scope.launch {
            try {
                val plan = withContext(Dispatchers.IO) {
                    SchemeArchive.planArchive(
                        name,
                        System.currentTimeMillis(),
                        SchemePackageImport.listRelativeFiles(DataManager.userDataDir),
                        userSchemeIds,
                    )
                }
                if (plan == null) {
                    archiveError = ArchiveErrorKind.EMPTY
                    return@launch
                }
                val outcome = withContext(Dispatchers.IO + NonCancellable) {
                    val targetDir = File(archiveRootDir(), plan.dirName)
                    if (targetDir.exists()) return@withContext ArchiveErrorKind.EXISTS
                    try {
                        targetDir.mkdirs()
                        val userCanonical = DataManager.userDataDir.canonicalPath
                        for (rel in plan.files + plan.userdbFiles) {
                            val src = File(DataManager.userDataDir, rel).canonicalFile
                            if (!src.path.startsWith(userCanonical + File.separator)) continue
                            if (!src.isFile) continue
                            val dest = File(targetDir, rel)
                            dest.parentFile?.mkdirs()
                            src.copyTo(dest, overwrite = false)
                        }
                        File(targetDir, SchemeArchive.MANIFEST_FILE_NAME).writeText(
                            SchemeArchive.encodeManifest(
                                SchemeArchive.ArchiveManifest(
                                    plan.name,
                                    plan.createdAtMs,
                                    plan.schemeIds,
                                ),
                            ),
                        )
                        File(targetDir, SchemeArchive.REGISTRY_FILE_NAME).writeText(
                            importPrefs.getString(
                                SchemePackageImport.KEY_PACKAGE_REGISTRY,
                                null,
                            ).orEmpty(),
                        )
                        null
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        targetDir.deleteRecursively()
                        ArchiveErrorKind.FAILED
                    }
                }
                if (outcome != null) {
                    archiveError = outcome
                } else {
                    refreshArchiveEntries()
                }
            } finally {
                archiveWorking = false
            }
        }
    }

    /** 删除归档集：只删归档目录，二次确认在对话框里做，不动当前方案。 */
    fun deleteArchive(item: ArchiveListItem) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    File(archiveRootDir(), item.dirName).deleteRecursively()
                }.getOrDefault(false)
            }
            if (!ok) {
                archiveError = ArchiveErrorKind.FAILED
            } else {
                refreshArchiveEntries()
            }
        }
    }

    /**
     * 打开恢复确认页：以归档目录实测文件为准生成规划（清单只提供
     * 名 / 时间 / 方案 id）。归档内无方案文件时视为损坏目录，不进确认页。
     */
    fun openRestoreConfirm(item: ArchiveListItem) {
        archiveError = null
        scope.launch {
            val inputs = withContext(Dispatchers.IO) {
                val archiveDir = File(archiveRootDir(), item.dirName)
                val (files, userdb) = SchemeArchive.splitArchiveListing(
                    SchemePackageImport.listRelativeFiles(archiveDir),
                )
                val registry = SchemePackageImport.decodeRegistry(
                    importPrefs.getString(SchemePackageImport.KEY_PACKAGE_REGISTRY, null),
                )
                RestoreInputs(
                    archiveFiles = files,
                    archiveUserdb = userdb,
                    currentUserFiles = SchemePackageImport.listRelativeFiles(
                        DataManager.userDataDir,
                    ),
                    currentRegistryFiles = registry.records.flatMap { it.files }.toSet(),
                    schemeIds = item.manifest.schemeIds,
                )
            }
            val plan = SchemeArchive.planRestore(
                archiveFiles = inputs.archiveFiles,
                archiveUserdb = inputs.archiveUserdb,
                currentUserFiles = inputs.currentUserFiles,
                currentRegistryFiles = inputs.currentRegistryFiles,
                schemeIds = inputs.schemeIds,
                overwriteUserdb = false,
            )
            if (plan.restoreFiles.isEmpty()) {
                archiveError = ArchiveErrorKind.FAILED
                return@launch
            }
            restoreInputs = inputs
            restoreTarget = item
            restorePlan = plan
            restoreOverwriteUserdb = false
        }
    }

    /**
     * 确认恢复：文件操作在 NonCancellable 里做（清理登记外文件 →
     * 拷回归档集 → userdb 按确认页策略 → 登记原样写回），再走一期
     * 部署编排。调用 switchTo 时已启用顺序仍是恢复前顺序，因此
     * 一期回退恢复的顺序与上一方案正是恢复前状态（见验收报告注记）。
     * 恢复集首个方案已是当前方案时 switchTo 会拒绝（目标即当前），
     * 此时改走 reload + 确认路径，失败则留在确认页明示。
     */
    fun confirmRestore() {
        val target = restoreTarget ?: return
        val plan = restorePlan ?: return
        if (restoreWorking) return
        restoreWorking = true
        archiveError = null
        val preRestoreCurrent = currentSchemeId
        val targetId = plan.newOrder.firstOrNull()
        if (targetId == null) {
            restoreWorking = false
            archiveError = ArchiveErrorKind.FAILED
            return
        }
        scope.launch {
            try {
                val ioOk = withContext(Dispatchers.IO + NonCancellable) {
                    runCatching {
                        val userDir = DataManager.userDataDir
                        val userCanonical = userDir.canonicalPath
                        val archiveDir = File(archiveRootDir(), target.dirName)
                        val archiveCanonical = archiveDir.canonicalPath
                        for (rel in plan.cleanFiles) {
                            val file = File(userDir, rel).canonicalFile
                            if (file.path.startsWith(userCanonical + File.separator) &&
                                file.isFile
                            ) {
                                file.delete()
                            }
                        }
                        fun copyBack(rel: String) {
                            val src = File(archiveDir, rel).canonicalFile
                            val dest = File(userDir, rel).canonicalFile
                            if (!src.path.startsWith(archiveCanonical + File.separator)) return
                            if (!dest.path.startsWith(userCanonical + File.separator)) return
                            if (!src.isFile) return
                            dest.parentFile?.mkdirs()
                            src.copyTo(dest, overwrite = true)
                        }
                        for (rel in plan.restoreFiles) copyBack(rel)
                        for (rel in plan.userdbRestoreMissing + plan.userdbOverwrite) {
                            copyBack(rel)
                        }
                        val registryFile =
                            File(archiveDir, SchemeArchive.REGISTRY_FILE_NAME)
                        if (registryFile.isFile) {
                            importPrefs.edit {
                                putString(
                                    SchemePackageImport.KEY_PACKAGE_REGISTRY,
                                    registryFile.readText(),
                                )
                            }
                        }
                    }.isSuccess
                }
                if (!ioOk) {
                    archiveError = ArchiveErrorKind.FAILED
                    return@launch
                }
                val placeholders = plan.newOrder.map { SchemaItem(it, it) }
                placeholders.forEach { item ->
                    if (availableSchemas.none { it.id == item.id } &&
                        enabledSchemas.none { it.id == item.id }
                    ) {
                        availableSchemas.add(item)
                    }
                }
                if (targetId == preRestoreCurrent) {
                    EngineFactory.current()?.reload()
                    EngineFactory.current()?.selectSchema(targetId)
                    if (verifyCurrent(targetId, 180_000)) {
                        applyOrder(plan.newOrder)
                        archivePage = false
                        restoreTarget = null
                        restorePlan = null
                        restoreInputs = null
                        loadSchemas()
                    } else {
                        archiveError = ArchiveErrorKind.RESTORE_DEPLOY_FAILED
                    }
                    return@launch
                }
                switchTo(SchemaItem(targetId, targetId)) {
                    scope.launch {
                        // 成功（空闲态）才把顺序定为恢复集；回退 / 失败态
                        // 下编排器已把顺序恢复为恢复前，主列表横幅明示。
                        if (switchState is SchemeSwitchState.Idle) {
                            applyOrder(plan.newOrder)
                        }
                        loadSchemas()
                    }
                }
                // 切换横幅在主列表展示：恢复确认页即刻让出，回主列表。
                archivePage = false
                restoreTarget = null
                restorePlan = null
                restoreInputs = null
            } finally {
                restoreWorking = false
            }
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
            // 第四期归档页标题为归档/恢复，确认页返回回列表、列表返回回主列表。
            val inImportFlow = importPlan != null || importError != null || importBusy
            val inDownloadFlow = downloadPage || downloadFormOpen ||
                downloadRunning || downloadError != null
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                importPlan != null -> R.string.schema_import_result_title
                                inImportFlow ->
                                    if (importFromDownload) R.string.schema_download
                                    else R.string.schema_import
                                archivePage -> R.string.schema_archive
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
                                restorePlan != null -> {
                                    restoreTarget = null
                                    restorePlan = null
                                    restoreInputs = null
                                    restoreOverwriteUserdb = false
                                    archiveError = null
                                }
                                archivePage -> archivePage = false
                                downloadFormOpen -> {
                                    downloadFormOpen = false
                                    downloadFormError = null
                                }
                                // 下载进行中返回即取消（尚未安装，取消安全）。
                                downloadRunning -> cancelDownload()
                                downloadError != null -> downloadError = null
                                downloadPage -> downloadPage = false
                                else -> {
                                    saveOrder(prefs, enabledSchemas)
                                    onBack()
                                }
                            }
                        },
                        enabled = !importInstalling && !importBusy &&
                            !archiveWorking && !restoreWorking,
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
                    if (inImportFlow || inDownloadFlow || archivePage) {
                        // 导入 / 下载 / 归档流程页不给菜单与编辑入口，
                        // 防流程中途切走状态。
                    } else if (editMode) {
                        TextButton(onClick = { editMode = false }) {
                            Text(stringResource(R.string.schema_edit_done))
                        }
                    } else {
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
                            // 第四期新增「归档/恢复」：低频逃生舱入口，不是
                            // 常规切换机制；切换 / 部署 / 导入 / 下载进行中禁用。
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schema_archive)) },
                                enabled = !switchOrchestrator.isBusy() && !importBusy &&
                                    !downloadRunning,
                                onClick = {
                                    menuExpanded = false
                                    archivePage = true
                                    refreshArchiveEntries()
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
        floatingActionButton = {
            // 只在下载列表页给 + 新增（校验 / 安装交接页不给，防流程中途切走）。
            if (downloadPage && importPlan == null && !importBusy && importError == null &&
                !downloadFormOpen && !downloadRunning && downloadError == null
            ) {
                FloatingActionButton(onClick = { openDownloadForm(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
            }
        },
    ) { padding ->
        // 导入流程页整体替换列表内容（校验页 / 等待页 / 错误页）。
        if (importPlan != null) {
            ImportValidationPage(
                plan = importPlan!!,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = downloadFormName,
                    onValueChange = { downloadFormName = it },
                    label = { Text(stringResource(R.string.schema_download_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = downloadFormUrl,
                    onValueChange = { downloadFormUrl = it },
                    label = { Text(stringResource(R.string.schema_download_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.schema_download_header),
                    fontSize = rowFontSize,
                    fontWeight = FontWeight.Medium,
                )
                downloadFormHeaders.forEachIndexed { index, header ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = header.first,
                            onValueChange = { value ->
                                downloadFormHeaders[index] = value to header.second
                            },
                            label = {
                                Text(stringResource(R.string.schema_download_header_name))
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = header.second,
                            onValueChange = { value ->
                                downloadFormHeaders[index] = header.first to value
                            },
                            label = {
                                Text(stringResource(R.string.schema_download_header_value))
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { downloadFormHeaders.removeAt(index) },
                            enabled = downloadFormHeaders.size > 1,
                        ) {
                            Text(stringResource(R.string.schema_download_header_remove))
                        }
                    }
                }
                TextButton(onClick = { downloadFormHeaders.add("" to "") }) {
                    Text(stringResource(R.string.schema_download_add_header))
                }
                if (downloadFormError != null) {
                    Text(
                        downloadFormErrorText(downloadFormError!!),
                        fontSize = rowFontSize,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { submitDownloadForm() },
                    modifier = Modifier.fillMaxWidth(),
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
                }
                downloadEntries.forEach { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ExpressiveShapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                entry.name,
                                fontSize = rowFontSize,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.url,
                                fontSize = rowSubFontSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { openDownloadForm(entry) }) {
                                    Text(
                                        stringResource(
                                            R.string.schema_download_row_download,
                                        ),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { deleteDownloadEntry(entry) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.scale(0.8f),
                                        tint = MaterialTheme.colorScheme.onSurface,
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
        // 第四期归档 / 恢复页整体替换列表内容（确认页优先于列表页）。
        if (restorePlan != null && restoreTarget != null) {
            ArchiveRestoreConfirmPage(
                target = restoreTarget!!,
                plan = restorePlan!!,
                overwriteChecked = restoreOverwriteUserdb,
                onOverwriteChange = { checked ->
                    restoreOverwriteUserdb = checked
                    // 勾选变化即按已扫描输入纯算重规划，不再扫盘。
                    val inputs = restoreInputs
                    if (inputs != null) {
                        restorePlan = SchemeArchive.planRestore(
                            archiveFiles = inputs.archiveFiles,
                            archiveUserdb = inputs.archiveUserdb,
                            currentUserFiles = inputs.currentUserFiles,
                            currentRegistryFiles = inputs.currentRegistryFiles,
                            schemeIds = inputs.schemeIds,
                            overwriteUserdb = checked,
                        )
                    }
                },
                working = restoreWorking,
                error = archiveError,
                onConfirm = { confirmRestore() },
                onCancel = {
                    restoreTarget = null
                    restorePlan = null
                    restoreInputs = null
                    restoreOverwriteUserdb = false
                    archiveError = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            )
            return@Scaffold
        }
        if (archivePage) {
            ArchiveListPage(
                entries = archiveEntries,
                working = archiveWorking,
                error = archiveError,
                onCreateClick = {
                    archiveNameInput = defaultArchiveName()
                    archiveNameDialog = true
                },
                onRestoreClick = { openRestoreConfirm(it) },
                onDeleteClick = { archiveDeleteTarget = it },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            )
            if (archiveNameDialog) {
                AlertDialog(
                    onDismissRequest = { archiveNameDialog = false },
                    title = { Text(stringResource(R.string.schema_archive_create)) },
                    text = {
                        OutlinedTextField(
                            value = archiveNameInput,
                            onValueChange = { archiveNameInput = it },
                            label = {
                                Text(stringResource(R.string.schema_archive_name_label))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                archiveNameDialog = false
                                createArchive()
                            },
                            enabled = !archiveWorking,
                        ) {
                            Text(stringResource(R.string.schema_archive_name_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { archiveNameDialog = false }) {
                            Text(stringResource(R.string.schema_import_cancel))
                        }
                    },
                )
            }
            if (archiveDeleteTarget != null) {
                AlertDialog(
                    onDismissRequest = { archiveDeleteTarget = null },
                    title = { Text(stringResource(R.string.schema_archive_delete_title)) },
                    text = { Text(stringResource(R.string.schema_archive_delete_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val item = archiveDeleteTarget
                                archiveDeleteTarget = null
                                if (item != null) deleteArchive(item)
                            },
                        ) {
                            Text(stringResource(R.string.schema_archive_delete_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { archiveDeleteTarget = null }) {
                            Text(stringResource(R.string.schema_import_cancel))
                        }
                    },
                )
            }
            return@Scaffold
        }
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
            // 第一期行元数据：版本 / 来源 / 变体（行与卡片共用）
            val allRowItems = enabledSchemas + availableSchemas
            val sourceBuiltinLabel = stringResource(R.string.schema_source_builtin)
            val sourceUserLabel = stringResource(R.string.schema_source_user)
            val sourceUrlLabel = stringResource(R.string.schema_source_url)
            fun sourceLabelFor(item: SchemaItem): String? =
                if (item.id in urlSourceIds) {
                    sourceUrlLabel
                } else {
                    when (sourceById[item.id]) {
                        SchemeSwitchPolicy.SchemeSource.USER -> sourceUserLabel
                        SchemeSwitchPolicy.SchemeSource.BUILTIN -> sourceBuiltinLabel
                        null -> null
                    }
                }
            fun rowMetaFor(item: SchemaItem): SchemaRowMeta = SchemaRowMeta(
                version = versionById[item.id].orEmpty(),
                sourceLabel = sourceLabelFor(item),
                variantLabel = WanxiangUpdateManager.variantNameOf(item.id, item.name),
            )
            // 忙态派生自组合态镜像（不要直接读状态机的非 State 字段作组合判断）
            val switchBusy = switchState is SchemeSwitchState.Switching ||
                switchState is SchemeSwitchState.Deploying ||
                switchState is SchemeSwitchState.RollingBack
            // 顶部当前方案卡（v2 原型）：安静卡片，无撞色、无芯片、
            // 无回兜底按钮；名 + 灰字副行（变体 · 键盘 · 版本 · 来源，纯文本）。
            val currentItem = allRowItems.firstOrNull { it.id == currentSchemeId }
                ?: enabledSchemas.firstOrNull()
            if (currentItem != null) {
                val meta = rowMetaFor(currentItem)
                val subParts = listOfNotNull(
                    meta.variantLabel,
                    schemaLayoutTag(context, currentItem.layout),
                    meta.version.ifBlank { null },
                    meta.sourceLabel,
                )
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
                            stringResource(R.string.schema_current_title),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            currentItem.name.ifBlank { currentItem.id },
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (subParts.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                subParts.joinToString(" · "),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
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
            if (editMode) {
                Text(
                    stringResource(R.string.schema_edit_hint),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 已启用列表（v2 原型）：一张分组卡片，行只留名 + 灰字 id，
            // 无芯片、无默认标记；行点按切换。编辑模式仍走排序列表
            // （无芯片、无删除——删除会把方案送进本页不再显示的未启用区，
            // 禁用入口随管理页阶段一并补回）。
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                if (editMode) {
                    ReorderableSchemaList(
                        items = enabledSchemas,
                        // 编辑模式下行点选不切换（防排序时误触）
                        onRowClick = null,
                        trailingIcon = {},
                    )
                } else {
                    Column {
                        enabledSchemas.forEachIndexed { index, schema ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {}
                                    .clickable(enabled = !switchBusy) { switchTo(schema) }
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

/** 行副信息：版本（schema/version）、来源标签、变体标签（可空）。 */
private data class SchemaRowMeta(
    val version: String = "",
    val sourceLabel: String? = null,
    val variantLabel: String? = null,
)

@Composable
private fun ReorderableSchemaList(
    items: MutableList<SchemaItem>,
    onRowClick: ((SchemaItem) -> Unit)?,
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
                    onClick = onRowClick?.let { click -> { click(schema) } },
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
 * 由临时 zip 构造安装规划（IO 线程调用）：解压 → 登记读回 → 现存文件
 * 盘点 → 规划。失败时暂存目录已由 extractZip 清掉或为空，调用方
 * 再调 cleanupImportFiles 兜底。包名由调用方从 SAF 显示名净化后传入
 * （临时 zip 文件名固定，不能作包名）。
 */
private fun buildImportPlan(
    context: Context,
    zipFile: File,
    stagingDir: File,
    packageName: String,
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
    val existingUser = SchemePackageImport.listRelativeFiles(DataManager.userDataDir)
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
 * 导入校验结果页：包内方案清单、同 ID 替换警告（含 Pro 多口味与内置
 * 覆盖两种口径）、共享文件决策、跳过文件，最后确认安装 / 取消。
 * 安装进行中按钮禁用、显示进度；安装失败留在本页并可重试。
 */
@Composable
private fun ImportValidationPage(
    plan: SchemeImportPlan,
    installing: Boolean,
    error: ImportErrorKind?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.schema_import_package) + "：" + plan.packageName,
            fontSize = rowFontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            plan.packageVersion ?: stringResource(R.string.schema_import_version_unknown),
            fontSize = rowSubFontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SectionHeader(stringResource(R.string.schema_import_schemes_title))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ExpressiveShapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                plan.schemes.forEach { scheme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                scheme.name,
                                fontSize = rowFontSize,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                scheme.id,
                                fontSize = rowSubFontSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TagBadge(
                            schemaLayoutTag(context, scheme.layout),
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        scheme.version?.let {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                it,
                                fontSize = rowSubFontSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        if (plan.replacements.isNotEmpty()) {
            SectionHeader(stringResource(R.string.schema_import_replace_title))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    plan.replacements.forEach { replacement ->
                        Text(
                            replacement.schemeId +
                                (replacement.previousPackage?.let { "（$it）" } ?: ""),
                            fontSize = rowFontSize,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                replacement.overridesBuiltin ->
                                    stringResource(R.string.schema_import_replace_builtin_note)
                                replacement.schemeId == "wanxiang_pro" ->
                                    stringResource(R.string.schema_import_replace_pro_note) +
                                        "；" + stringResource(R.string.schema_import_replace_note)
                                else ->
                                    stringResource(R.string.schema_import_replace_note)
                            },
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        if (plan.sharedDecisions.isNotEmpty()) {
            SectionHeader(stringResource(R.string.schema_import_shared_title))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    plan.sharedDecisions.forEach { decision ->
                        Text(
                            decision.relPath,
                            fontSize = rowFontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                decision.overwrite ->
                                    stringResource(R.string.schema_import_shared_overwrite) +
                                        "（${decision.existingVersion} → ${decision.incomingVersion}）"
                                decision.unknownVersion ->
                                    stringResource(R.string.schema_import_shared_unknown)
                                else ->
                                    stringResource(R.string.schema_import_shared_keep) +
                                        "（${decision.existingVersion} / ${decision.incomingVersion}）"
                            },
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        if (plan.skippedFiles.isNotEmpty()) {
            SectionHeader(stringResource(R.string.schema_import_skipped_title))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    plan.skippedFiles.forEach { skipped ->
                        Text(
                            skipped.relPath,
                            fontSize = rowFontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (skipped.reason) {
                                SchemePackageImport.SkipReason.TRAVERSAL ->
                                    stringResource(R.string.schema_import_skip_traversal)
                                SchemePackageImport.SkipReason.PROTECTED ->
                                    stringResource(R.string.schema_import_skip_protected)
                                SchemePackageImport.SkipReason.USER_CUSTOM ->
                                    stringResource(R.string.schema_import_skip_custom)
                                SchemePackageImport.SkipReason.EXISTS_KEPT ->
                                    stringResource(R.string.schema_import_skip_exists)
                                SchemePackageImport.SkipReason.SHARED_OLDER ->
                                    stringResource(R.string.schema_import_skip_shared_older)
                                SchemePackageImport.SkipReason.SHARED_UNKNOWN_VERSION ->
                                    stringResource(R.string.schema_import_skip_shared_unknown)
                            },
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        if (plan.overwrittenExisting.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.schema_import_overwrite_note, plan.overwrittenExisting.size),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                importErrorText(error),
                fontSize = rowFontSize,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (installing) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.schema_import_installing),
                fontSize = rowSubFontSize,
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

/** 归档集列表行：目录名 + 清单 + 预格式化时间（列表按时间倒序）。 */
private data class ArchiveListItem(
    val dirName: String,
    val manifest: SchemeArchive.ArchiveManifest,
    val timeText: String,
)

/** 恢复确认页的扫描输入：勾选变化时纯算重规划，不再扫盘。 */
private data class RestoreInputs(
    val archiveFiles: List<String>,
    val archiveUserdb: List<String>,
    val currentUserFiles: Set<String>,
    val currentRegistryFiles: Set<String>,
    val schemeIds: List<String>,
)

/** 归档 / 恢复错误口径（界面文案据此映射，不直接拼字符串）。 */
private enum class ArchiveErrorKind {
    EMPTY,
    EXISTS,
    FAILED,
    RESTORE_DEPLOY_FAILED,
}

@Composable
private fun archiveErrorText(error: ArchiveErrorKind): String = when (error) {
    ArchiveErrorKind.EMPTY -> stringResource(R.string.schema_archive_error_empty)
    ArchiveErrorKind.EXISTS -> stringResource(R.string.schema_archive_error_exists)
    ArchiveErrorKind.FAILED -> stringResource(R.string.schema_archive_error_failed)
    ArchiveErrorKind.RESTORE_DEPLOY_FAILED ->
        stringResource(R.string.schema_archive_error_restore_deploy)
}

/**
 * 第四期归档集列表页：名 + 时间 + 方案 id 串 + 恢复 / 删除操作。
 * 归档是低频逃生舱：新建归档只快照当前 user 层方案集，不碰内置包。
 */
@Composable
private fun ArchiveListPage(
    entries: List<ArchiveListItem>,
    working: Boolean,
    error: ArchiveErrorKind?,
    onCreateClick: () -> Unit,
    onRestoreClick: (ArchiveListItem) -> Unit,
    onDeleteClick: (ArchiveListItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onCreateClick,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.schema_archive_create))
        }
        if (working) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.schema_archive_creating),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                archiveErrorText(error),
                fontSize = rowFontSize,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.schema_archive_empty),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entries.forEach { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        entry.manifest.name,
                        fontSize = rowFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.timeText,
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.manifest.schemeIds.joinToString(", "),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onRestoreClick(entry) },
                            enabled = !working,
                        ) {
                            Text(stringResource(R.string.schema_archive_restore))
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { onDeleteClick(entry) },
                            enabled = !working,
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.scale(0.8f),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 第四期恢复确认页：写明将被替换的文件数、清理数、userdb 处理方式
 * 与回退路径；userdb 默认不覆盖现存，仅勾选时覆盖。确认后文件操作
 * 在 NonCancellable 里做，操作中返回与按钮禁用。
 */
@Composable
private fun ArchiveRestoreConfirmPage(
    target: ArchiveListItem,
    plan: SchemeArchive.RestorePlan,
    overwriteChecked: Boolean,
    onOverwriteChange: (Boolean) -> Unit,
    working: Boolean,
    error: ArchiveErrorKind?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            target.manifest.name,
            fontSize = rowFontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            target.timeText,
            fontSize = rowSubFontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SectionHeader(stringResource(R.string.schema_archive_restore_title))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ExpressiveShapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    stringResource(
                        R.string.schema_archive_restore_files,
                        plan.restoreFiles.size,
                    ),
                    fontSize = rowFontSize,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.schema_archive_restore_replace,
                        plan.replaceExisting.size,
                    ),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (plan.cleanFiles.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.schema_archive_restore_clean,
                            plan.cleanFiles.size,
                        ),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (plan.userdbRestoreMissing.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.schema_archive_restore_userdb_missing,
                            plan.userdbRestoreMissing.size,
                        ),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (plan.userdbSkippedExisting.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.schema_archive_restore_userdb_skip,
                            plan.userdbSkippedExisting.size,
                        ),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.schema_archive_restore_rollback),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = overwriteChecked,
                onCheckedChange = onOverwriteChange,
                enabled = !working,
            )
            Text(
                stringResource(R.string.schema_archive_restore_userdb_overwrite),
                fontSize = rowFontSize,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                archiveErrorText(error),
                fontSize = rowFontSize,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (working) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.schema_archive_restoring),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onConfirm,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.schema_archive_restore))
        }
        TextButton(
            onClick = onCancel,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.schema_import_cancel))
        }
        Spacer(Modifier.height(16.dp))
    }
}
