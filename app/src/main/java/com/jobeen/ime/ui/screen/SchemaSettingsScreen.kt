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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    val density = LocalDensity.current
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
                val stagingDir = File(context.cacheDir, IMPORT_STAGING_DIR_NAME)
                val outcome = withContext(Dispatchers.IO) {
                    buildImportPlan(
                        context,
                        tmpZip,
                        stagingDir,
                        SchemePackageImport.sanitizePackageName(displayName),
                    )
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
            val inImportFlow = importPlan != null || importError != null || importBusy
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                importPlan != null -> R.string.schema_import_result_title
                                inImportFlow -> R.string.schema_import
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
                                importError != null -> {
                                    importError = null
                                    cleanupImportFiles()
                                }
                                else -> {
                                    saveOrder(prefs, enabledSchemas)
                                    onBack()
                                }
                            }
                        },
                        enabled = !importInstalling && !importBusy,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (inImportFlow) {
                        // 导入流程页不给菜单与编辑入口，防流程中途切走状态。
                    } else if (editMode) {
                        TextButton(onClick = { editMode = false }) {
                            Text(stringResource(R.string.schema_edit_done))
                        }
                    } else {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            // 第二期新增「导入方案」：SAF 选 zip，走暂存校验流程。
                            // 切换 / 部署进行中禁用（维护期守卫与一期同口径）。
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schema_import)) },
                                enabled = !switchOrchestrator.isBusy() && !importBusy,
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
                        importError = null
                        cleanupImportFiles()
                    },
                ) {
                    Text(stringResource(R.string.schema_banner_close))
                }
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
            fun sourceLabelFor(item: SchemaItem): String? =
                when (sourceById[item.id]) {
                    SchemeSwitchPolicy.SchemeSource.USER -> sourceUserLabel
                    SchemeSwitchPolicy.SchemeSource.BUILTIN -> sourceBuiltinLabel
                    null -> null
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
            // 顶部当前方案卡：名 + 变体 + 键盘 + 版本 + 来源；非 Lite 时给回兜底入口
            val currentItem = allRowItems.firstOrNull { it.id == currentSchemeId }
                ?: enabledSchemas.firstOrNull()
            val liteItem =
                allRowItems.firstOrNull { it.id == SchemeSwitchPolicy.BUILTIN_LITE_ID }
            if (currentItem != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            stringResource(R.string.schema_current_title),
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            currentItem.name.ifBlank { currentItem.id },
                            fontSize = rowFontSize,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val meta = rowMetaFor(currentItem)
                            meta.variantLabel?.let {
                                TagBadge(
                                    it,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            TagBadge(
                                schemaLayoutTag(context, currentItem.layout),
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (meta.version.isNotBlank()) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    meta.version,
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                )
                            }
                            meta.sourceLabel?.let {
                                Spacer(Modifier.width(4.dp))
                                TagBadge(
                                    it,
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                        if (liteItem != null && currentItem.id != liteItem.id) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { switchTo(liteItem) },
                                enabled = !switchBusy,
                            ) {
                                Text(stringResource(R.string.schema_back_to_lite))
                            }
                        }
                    }
                }
            }
            // 切换 / 部署 / 回退横幅：Idle 时不占位
            when (val s = switchState) {
                is SchemeSwitchState.Switching -> {
                    Text(
                        stringResource(R.string.schema_switching),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SchemeSwitchState.Deploying -> {
                    Column {
                        Text(
                            stringResource(R.string.schema_deploying),
                            fontSize = rowSubFontSize,
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
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is SchemeSwitchState.Failed -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ExpressiveShapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                stringResource(R.string.schema_switch_failed) + "：" + s.targetId,
                                fontSize = rowSubFontSize,
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
                        shape = ExpressiveShapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            val prevName = allRowItems.firstOrNull { it.id == s.previousId }
                                ?.let { it.name.ifBlank { it.id } } ?: s.previousId
                            Text(
                                stringResource(R.string.schema_rolled_back) + "：" + prevName,
                                fontSize = rowSubFontSize,
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
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                ReorderableSchemaList(
                    items = enabledSchemas,
                    rowMeta = { rowMetaFor(it) },
                    // 编辑模式下行点选不切换（防排序时误触）；忙态下拒绝新切换
                    onRowClick = if (editMode || switchBusy) null else ({ item -> switchTo(item) }),
                    trailingIcon = { schema ->
                        if (enabledSchemas.size > 1 && !switchBusy) {
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
                    },
                )
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
                                rowMeta = rowMetaFor(schema),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            enabledSchemas.add(schema)
                                            availableSchemas.remove(schema)
                                            saveOrder(prefs, enabledSchemas)
                                        },
                                        enabled = !switchBusy,
                                    ) {
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

            // 底部内置 Lite 兜底行：单独成区、标注兜底、不可删；点选即切回
            // Lite（Lite 不在已启用列表时先加入首位，见编排器 moveToTop）
            if (liteItem != null) {
                SectionHeader(stringResource(R.string.schema_builtin_lite_section))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    SchemaListItem(
                        schema = liteItem,
                        rowMeta = rowMetaFor(liteItem),
                        fallbackBadge = stringResource(R.string.schema_fallback_badge),
                        onClick = if (switchBusy) null else ({ switchTo(liteItem) }),
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingIcon = {},
                    )
                }
            }
            // 更新区视觉隔离说明：本区只服务内置 Lite 更新通道，
            // 不更新用户自装方案（行为本身未改，只加一行说明）
            Text(
                stringResource(R.string.schema_update_isolated_note),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
    rowMeta: (SchemaItem) -> SchemaRowMeta,
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
                    isDefault = index == 0,
                    rowMeta = rowMeta(schema),
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
    isDefault: Boolean = false,
    rowMeta: SchemaRowMeta = SchemaRowMeta(),
    fallbackBadge: String? = null,
    onClick: (() -> Unit)? = null,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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
                Text(
                    schema.id,
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 极简副行：变体 / 键盘 / 版本 / 来源（第一期定稿口径；
            // 原全角/半角符号徽标随极简化去掉，符号信息不在行内展示）
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
                fallbackBadge?.let {
                    TagBadge(
                        it,
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                rowMeta.variantLabel?.let {
                    TagBadge(
                        it,
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
                if (rowMeta.version.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        rowMeta.version,
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                rowMeta.sourceLabel?.let {
                    Spacer(Modifier.width(4.dp))
                    TagBadge(
                        it,
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
