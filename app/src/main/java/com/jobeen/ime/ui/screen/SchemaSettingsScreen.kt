package com.jobeen.ime.ui.screen

import android.content.Context
import android.content.Intent
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
import com.jobeen.ime.R
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.base.update.WanxiangUpdateManager
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.ui.FabActions
import com.jobeen.ime.ui.UserDictActivity
import com.jobeen.ime.data.manager.DirSwitchMonitor
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.DirSwitchPhase
import com.jobeen.ime.data.manager.DirSwitchResumePolicy
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemeDownload
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.SchemePackageImport.PlanResult
import com.jobeen.ime.data.manager.SchemePackageImport.SchemeImportPlan
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.data.manager.SchemeSwitchCoordinator
import com.jobeen.ime.data.manager.SchemeSwitchOperationKind
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
import kotlinx.coroutines.ensureActive
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
    // 本地信息（打开页面即计算，用于"本地信息"卡片展示）
    var currentSchemaName by remember { mutableStateOf("") }
    // 切换编排：状态机仍是转移权威，但实例与 StateFlow 镜像一律归
    // 应用级协调器（SchemeSwitchCoordinator）所有；页面只订阅，不再
    // remember 自己的编排器——重进即看到运行 / 失败 / 回退态，点选
    // 切换、目录切换、导入安装三路互斥也由协调器守门。
    val switchState by SchemeSwitchCoordinator.state.collectAsStateWithLifecycle()
    // 目录同步删除传播暂停标志：仅存在时在方案区显示“确认清理”入口。
    var syncPause by remember {
        mutableStateOf(RimeSyncPrefs.deletePropagationPause(context))
    }
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
    val deployRunning by EngineDeployState.running.collectAsStateWithLifecycle()
    var schemasEmpty by remember { mutableStateOf(false) }
    // 当前目录盘上是否空方案：只在副作用里读盘（LaunchedEffect(currentDir)），
    // 不在组合期读盘；目录切换时 currentDir 变化触发重算。初始 false
    // 为乐观值：首帧空目录会短暂按非空分支渲染，随后副作用纠正。
    var diskEmpty by remember { mutableStateOf(false) }
    var manageDeploying by remember { mutableStateOf(false) }
    var manageDeployError by remember { mutableStateOf<String?>(null) }
    var versionById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 每行版本缓存：loadSchemas 每轮询都重算（引擎任务 + 逐方案 openSchema）
    // 开销大；缓存键 = 当前目录 + 方案 id 序列，键不变时直接复用缓存，
    // 键变化（切目录 / 方案集变化）时失效重算。缓存随组合销毁而丢，
    // 重进页面重新计算，不会把更新后的版本长期钉在旧值上。
    var versionCacheKey by remember { mutableStateOf<String?>(null) }
    var versionCache by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sourceById by remember {
        mutableStateOf<Map<String, SchemeSwitchPolicy.SchemeSource>>(emptyMap())
    }
    val scope = rememberCoroutineScope()
    // ---- 第二期本地导入：SAF 选 zip → 暂存校验 → 校验结果页 → 确认安装 ----
    // importPlan 非空时 Scaffold 内容整体切到校验结果页（见下方内容分支）；
    // importError 非空且无 plan 时切到错误页；importBusy 时切到等待页。
    // 多目录模型下导入落盘目标是独立新目录（importTargetDir），不再合并
    // 进当前目录；校验页只确认包名、方案数与同名目录替换一行。
    var importPlan by remember { mutableStateOf<SchemeImportPlan?>(null) }
    var importTargetDir by remember { mutableStateOf<File?>(null) }
    var importStagingDir by remember { mutableStateOf<File?>(null) }
    // 事务安装用：extractZip 的原始相对路径 + 字节数（installTransaction
    // 逐项核对大小用，禁止用 staging 文件大小现算代替；校验成功时由
    // validateZipFile 随规划一起保留，取消 / 失败时随之清空）。
    var importStagedEntries by remember {
        mutableStateOf<List<SchemePackageImport.StagedEntry>>(emptyList())
    }
    // 确认页“将被保留”摘要：旧目录现存的受保护文件（inheritProtected
    // 口径，default.custom.yaml 除外——事务随后会用新方案表重写它，
    // 其旧内容并不保留，列入会误导）。
    var importKeptFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    // 迁移失败显示：W1 接口 migrationError 的页内镜像，重试后刷新。
    var migrationErrorText by remember {
        mutableStateOf(SchemeDirs.migrationError(context))
    }
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
    // 整包下载专用 client：连接 15s / 读取 120s；callTimeout 300s
    // 封顶整条调用（含重定向）：须大于单次 read 超时口径，300 秒取整；
    // https→http 降级禁跟随。慢速涓流超过 300s 即按超时失败，不再无限拖长。
    val downloadClient = remember {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }
    // 回调内不可用 stringResource，提前在组合阶段解析
    val msgDirSwitchFailed = stringResource(R.string.schema_dir_switch_failed)
    val msgManageDeployFailed = stringResource(R.string.schema_manage_deploy_failed)

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
            // 否则内置；两层都没有时回落内置，见编排器注释）。
            // 版本计算走缓存（见 versionCacheKey 处注释）：轮询重调
            // loadSchemas 时键不变直接复用，不再每次都跑引擎任务。
            val versionKey = "$currentDir|${allItems.joinToString(",") { it.id }}"
            versionById = if (versionKey == versionCacheKey) {
                versionCache
            } else {
                runCatching {
                    (EngineFactory.current() as? IRimeJob)?.awaitJob(emptyMap<String, String>()) {
                        allItems.associate { item ->
                            item.id to runCatching {
                                RimeConfig.openSchema(item.id).use {
                                    it.getString("schema/version").orEmpty()
                                }
                            }.getOrDefault("")
                        }
                    } ?: emptyMap()
                }.getOrDefault(emptyMap()).also {
                    versionCacheKey = versionKey
                    versionCache = it
                }
            }
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
            loaded = true
        }
    }



    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---- 第一期方案切换：点选切换 / 部署 / 回退（状态机见协调器） ----

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
     * 判定见 DirSwitchResumePolicy：引擎表空 + 磁盘有方案 + 无切换在跑
     * + 无部署在跑。部署在跑时表空是正常态（作业队列被部署作业占住），
     * 此时 reload 会把进行中的全量编译推倒重来，必须排除——页内重试
     * 与进页轮询都走这里，单点守门。
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
        if (!DirSwitchResumePolicy.shouldResume(
                engineEmpty,
                diskIds.isNotEmpty(),
                switching,
                EngineDeployState.isRunning(),
            )
        ) return
        val enabled = SchemaManager.readEnabledIds(prefs, dir).ifEmpty { diskIds }
        SchemaManager.saveEnabledIds(prefs, dir, enabled)
        // 续切只写偏好时引擎仍按旧 custom 表部署，空表续切等于白跑：
        // 先把启用表同步进 custom 文件，再 reload（非空才写）。
        if (enabled.isNotEmpty()) {
            SchemeDirs.writeDefaultCustomSchemaList(
                File(DataManager.schemeDirsRoot, dir),
                enabled,
            )
        }
        EngineFactory.current()?.reload()
        if (enabled.isNotEmpty()) {
            EngineFactory.current()?.selectSchema(enabled[0])
            // 校验只定成败显示，不抛：成败最终以 loadSchemas 重读的引擎表为准。
            verifyCurrent(enabled[0], 180_000)
        }
    }

    /** 空表轮询重载：2s 一次、最多 timeoutMs（默认 5 分钟），直到方案表就绪。 */
    suspend fun pollUntilLoaded(timeoutMs: Long = 300_000L) {
        var waitedMs = 0L
        while (!loaded && waitedMs < timeoutMs) {
            delay(2000L)
            waitedMs += 2000L
            if (!loaded) loadSchemas()
        }
    }

    // 进页加载 + 部署中自动重载：空表时轮询重载，部署跑完自动出列表；
    // stranded 时先续切一次（仅一次，避免中途重启部署），再继续轮询。
    // 用户中途离开即取消本轮询，下次进页重新判定——续切本身幂等。
    // 注意：本 effect 不再自写重载轮询（与 pollUntilLoaded 是同一等待
    // 目标的重复实现，已删）；只保留原循环里 pollUntilLoaded 没有的
    // 续切门：等 phase 离开 SWITCHING 后续切一次，再把剩余预算交给
    // pollUntilLoaded 重载——总预算仍是 5 分钟，不因拆分而延长。
    LaunchedEffect(Unit) {
        migrationErrorText = SchemeDirs.migrationError(context)
        // 进页刷新删除传播暂停标志：FAB 同步可能在本页之外暂停跟删，
        // 重进时方案区才应出现“确认清理”入口。
        syncPause = RimeSyncPrefs.deletePropagationPause(context)
        loadSchemas()
        if (!loaded) {
            var gateWaitedMs = 0L
            while (DirSwitchMonitor.status.value.phase == DirSwitchPhase.SWITCHING &&
                gateWaitedMs < 300_000L
            ) {
                delay(2000L)
                gateWaitedMs += 2000L
            }
            if (!loaded &&
                DirSwitchMonitor.status.value.phase != DirSwitchPhase.SWITCHING
            ) {
                resumeIfStranded()
            }
            pollUntilLoaded(timeoutMs = (300_000L - gateWaitedMs).coerceAtLeast(0L))
        }
    }

    // 空目录判定读盘移到副作用：目录名变化时在 IO 上重算，不在组合期读盘。
    LaunchedEffect(currentDir) {
        diskEmpty = withContext(Dispatchers.IO) {
            runCatching {
                SchemeDirs.schemesInDir(File(DataManager.schemeDirsRoot, currentDir)).isEmpty()
            }.getOrDefault(false)
        }
    }

    /**
     * 部署失败后的回退尾巴（正常路径与取消路径共用）：恢复切换前顺序
     * + reload + 重选上一方案，整段跑在 NonCancellable 内，取消不得
     * 打断；回退确认的布尔结果必须分支使用——成功时明示已回退到
     * 上一方案并显示上一方案为当前，失败时保留可见错误（Failed 态
     * 横幅）并显示 readCurrentId 实测的实际当前方案，实测不到时不
     * 再无条件 fallback 到上一方案误导。
     */
    suspend fun rollbackAfterDeployFailure() {
        val rollback = SchemeSwitchCoordinator.beginRollback() ?: return
        withContext(NonCancellable) {
            applyOrder(rollback.restoredOrder)
            EngineFactory.current()?.reload()
            EngineFactory.current()?.selectSchema(rollback.previousId)
            val rolledBack = SchemeSwitchCoordinator.resolveRollback(
                verifyCurrent(rollback.previousId, 180_000),
            )
            if (rolledBack) {
                // verifyCurrent 已实测上一方案为当前，才可明示回退成功。
                currentSchemeId = rollback.previousId
            } else {
                // 回退失败：Failed 态（rollback_failed）保留可见错误；
                // 当前方案只认实测值，实测不到时保持原显示，不谎称上一方案。
                readCurrentId()?.let { currentSchemeId = it }
            }
        }
    }

    /**
     * 点选切换（见上方注释）。[onDone] 在切换协程所有终点调用
     * （直选成功 / 部署成功 / 回退完成 / 防御性提前返回），供导入流程
     * 在切换结束后刷新方案表；一期原有调用方不传，行为不变。
     */
    fun switchTo(target: SchemaItem, onDone: (() -> Unit)? = null) {
        // 操作锁先行：点选切换全程持有协调器操作锁，目录切换与导入
        // 安装在此期间拒绝；锁抢不到直接返回（不排队、不改状态）。
        val operationId = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.POINT_SWITCH,
            target.id,
            currentDir,
        ) ?: return
        val plan = SchemeSwitchCoordinator.beginSwitch(
            currentSchemeId,
            target.id,
            enabledSchemas.map { it.id },
        ) ?: run {
            SchemeSwitchCoordinator.endOperation(operationId)
            return
        }
        applyOrder(plan.newOrder)
        EngineFactory.current()?.selectSchema(target.id)
        // 应用级作用域：点选切换的部署 / 回退路径不再随页面销毁取消。
        appScope.launch {
            try {
                val ok = verifyCurrent(target.id, 10_000)
                val result = SchemeSwitchCoordinator.resolveSwitch(ok)
                if (result == SwitchVerification.SUCCESS) {
                    currentSchemeId = target.id
                    onDone?.invoke()
                    return@launch
                }
                // 仅 Deploying 态才真正部署（编排器约定：非 Switching 态
                // 的 resolveSwitch 不转移状态，返回值不得单独作为部署依据）
                if (SchemeSwitchCoordinator.state.value !is SchemeSwitchState.Deploying) {
                    currentSchemeId = readCurrentId() ?: currentSchemeId
                    onDone?.invoke()
                    return@launch
                }
                EngineFactory.current()?.reload()
                EngineFactory.current()?.selectSchema(target.id)
                if (SchemeSwitchCoordinator.resolveDeploy(verifyCurrent(target.id, 180_000))) {
                    currentSchemeId = target.id
                    onDone?.invoke()
                    return@launch
                }
                rollbackAfterDeployFailure()
                onDone?.invoke()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消也不得跳过回滚：部署确认中途被取消时，把部署记为
                // 失败并在 NonCancellable 内走完回退，再按正常终点回调。
                // （应用级作用域只在进程消亡时取消，此分支是兜底。）
                withContext(NonCancellable) {
                    val st = SchemeSwitchCoordinator.state.value
                    if (st is SchemeSwitchState.Deploying) {
                        SchemeSwitchCoordinator.resolveDeploy(false)
                    }
                    if (SchemeSwitchCoordinator.state.value is SchemeSwitchState.Failed) {
                        rollbackAfterDeployFailure()
                    }
                }
                onDone?.invoke()
            } finally {
                SchemeSwitchCoordinator.endOperation(operationId)
            }
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
     * @param forceReload true 时即使目标就是当前目录也走完整部署（重装
     * 后必须重编新包内容；普通切换无变化时仍早退）。
     * @param onBeforeRollback 失败时在指针 / 顺序回滚之前调用（确认导入
     * 传入 rollbackTransaction：先把新包目录内容恢复为旧版，再走指针
     * 回滚；组合后终态是指针指上一目录 + 目标目录内容为旧版，不出现
     * “指针已回滚但目录内容还是新包”的中间态）。
     * @param onSwitchDone 切换协程结束时调用（成功与失败都调），供确认
     * 导入在成功时清理备份；早退（未启动）时不调，调用方以返回值为准。
     * @param existingOperationId 导入安装移交的操作锁 id：非空时目录切换
     * 接管该锁直至切换协程结束（不另抢锁、不重复守门）；为空时按正常
     * 入口抢协调器操作锁，抢不到即守门早退。早退时不接管、不释放。
     * @return true = 已启动切换协程，false = 守门早退（未启动）。
     */
    fun switchDirectory(
        targetDir: String,
        forceReload: Boolean = false,
        onBeforeRollback: (() -> Unit)? = null,
        onSwitchDone: ((Boolean) -> Unit)? = null,
        existingOperationId: String? = null,
    ): Boolean {
        // 互斥守门统一读协调器：点选切换忙、目录切换进行中、导入安装
        // 持有锁，三者任一即拒绝——不再留两套忙态。移交情形下锁已由
        // 导入方持有，跳过协调器守门（只保留页内 dirSwitching 与同目录
        // 早退），切换协程结束时由本函数释放移交来的锁。
        // 第二道防线：目录浏览器不得切入 build（部署产物目录，listDirs
        // 已排除、importDirName 已把 "build" 映射为 "build-pkg"）：非法
        // 输入与函数内其他守门早退同口径，静默返回 false，不置错。
        if (targetDir == "build") return false
        if (dirSwitching ||
            (existingOperationId == null && SchemeSwitchCoordinator.isBusy()) ||
            (targetDir == currentDir && !forceReload)
        ) return false
        val operationId = existingOperationId ?: SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.DIR_SWITCH,
            targetDir,
            targetDir,
        ) ?: return false
        val prevDir = currentDir
        val prevOrder = enabledSchemas.map { it.id }
        val prevCurrent = currentSchemeId
        dirSwitching = true
        dirSwitchError = null
        DirSwitchMonitor.begin(targetDir)
        appScope.launch {
            try {
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
                // 部署读的是合并配置（default.yaml + default.custom.yaml）：
                // 只写偏好不写 custom 文件时，引擎会按旧表部署，首位方案
                // 对不上、校验超时后回滚，页内遂长显部署中。部署前把本次
                // 启用表同步进 custom 文件（非空才写，空目录走内置兜底）。
                if (targetEnabled.isNotEmpty()) {
                    SchemeDirs.writeDefaultCustomSchemaList(
                        File(DataManager.schemeDirsRoot, targetDir),
                        targetEnabled,
                    )
                }
                // 部署校验超时随目标目录体积缩放（纯函数，见
                // SchemePackageImport.deployTimeoutMs）：小目录与旧 180 秒
                // 一致，大目录放宽、上限 1800 秒；空目录走内置兜底分支，
                // 不用本超时。
                val dirTotalBytes = runCatching {
                    File(DataManager.schemeDirsRoot, targetDir).walkTopDown()
                        .filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(0L)
                val deployTimeout = SchemePackageImport.deployTimeoutMs(dirTotalBytes)
                EngineFactory.current()?.reload()
                if (targetEnabled.isNotEmpty()) {
                    EngineFactory.current()?.selectSchema(targetEnabled[0])
                    if (!verifyCurrent(targetEnabled[0], deployTimeout)) {
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
                // 顺序：先跑调用方的 onBeforeRollback（确认导入时是
                // rollbackTransaction，把新包目录内容恢复为旧版），再走下面
                // 的指针 / 顺序回滚——终态是指针指上一目录 + 目标目录内容
                // 为旧版，不出现“指针已回滚但目录内容还是新包”的中间态。
                runCatching { onBeforeRollback?.invoke() }
                withContext(NonCancellable) {
                    runCatching {
                        SchemeDirs.setCurrentDirName(context, prevDir)
                        currentDir = prevDir
                        SchemaManager.saveEnabledIds(prefs, prevDir, prevOrder)
                        // 回滚同样先同步 custom 文件，否则回滚部署仍按错表编。
                        if (prevOrder.isNotEmpty()) {
                            SchemeDirs.writeDefaultCustomSchemaList(
                                File(DataManager.schemeDirsRoot, prevDir),
                                prevOrder,
                            )
                        }
                        EngineFactory.current()?.reload()
                        if (prevCurrent.isNotEmpty()) {
                            EngineFactory.current()?.selectSchema(prevCurrent)
                        }
                    }
                }
                val msg = msgDirSwitchFailed
                DirSwitchMonitor.failed(msg)
                dirSwitchError = msg
            }
            dirSwitching = false
            if (succeeded) {
                dirBrowser = false
                dirDetail = null
            }
            runCatching { onSwitchDone?.invoke(succeeded) }
            loadSchemas()
            if (!loaded) {
                // 部署仍在跑：页内轮询重载（离开页面即取消，重进由进页循环接手）。
                scope.launch { pollUntilLoaded() }
            }
            } finally {
                SchemeSwitchCoordinator.endOperation(operationId)
            }
        }
        return true
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
        importStagedEntries = emptyList()
        importKeptFiles = emptyList()
        cleanupImportFiles()
        // 下载交接的取消回下载页；SAF 导入的取消回主列表。
        if (importFromDownload) downloadPage = true
        importFromDownload = false
        importSource = SchemePackageImport.PackageSource.LOCAL
    }

    /** 清导入 / 下载交接的校验错误：下载交接的错误回下载页。 */
    fun clearImportError() {
        importError = null
        importStagedEntries = emptyList()
        importKeptFiles = emptyList()
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
        // 解压前预检（快速失败）：按 ZipFile 中央目录声明量估算解压总量，
        // 在解压前即报 NO_SPACE，不等解压完才报；解压后按实际总体积 +
        // 余量的精确终检仍保留（见下方 Ready 分支）。
        val precheckOk = withContext(Dispatchers.IO) {
            precheckExtractSpace(zipFile, context.cacheDir)
        }
        if (!precheckOk) {
            importError = ImportErrorKind.NO_SPACE
            importStagedEntries = emptyList()
            importKeptFiles = emptyList()
            cleanupImportFiles()
            return
        }
        val outcome = withContext(Dispatchers.IO) {
            buildImportPlan(context, zipFile, stagingDir, packageName, targetDir)
        }
        when (outcome) {
            is ImportPlanOutcome.Failed -> {
                importError = outcome.error
                importStagedEntries = emptyList()
                importKeptFiles = emptyList()
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
                    importStagedEntries = emptyList()
                    importKeptFiles = emptyList()
                    cleanupImportFiles()
                } else {
                    importPlan = outcome.plan
                    importTargetDir = targetDir
                    importStagingDir = stagingDir
                    importStagedEntries = outcome.stagedEntries
                    importKeptFiles = withContext(Dispatchers.IO) {
                        protectedKeptFiles(targetDir)
                    }
                }
            }
        }
    }

    /**
     * SAF 选包后的校验：有界复制到临时 zip → magic 校验 → 解到暂存目录
     * → 生成安装规划。复制 8KB 缓冲、每次写入前累计实际字节、超
     * MAX_ZIP_BYTES 立即中止；每循环检查取消，CancellationException
     * 必须重抛（不得被 runCatching 吞成 COPY_FAILED）；先写 .part、
     * 成功关闭后 rename 为固定名，finally 只删 .part（成功时它已被
     * rename 走），失败 / 取消不留残留。剩余空间在规划出来后、安装
     * 前检查（解压后总体积 + 部署余量）；校验失败只置错误态、不动
     * user 目录。
     * 错误区分：超限 → TOO_LARGE；读取失败 → COPY_FAILED；空间不足
     * （ENOSPC，凭 IOException 信息启发式判定）→ IO_ERROR；用户取消
     * → 不置 importError。启发式判定的局限：异常信息文本依赖平台与
     * 封装层，不含 errno 字样时会把空间不足误判为 COPY_FAILED，两者
     * 都是错误页、仅文案不同，不影响安装安全。
     */
    fun runImportPick(uri: Uri) {
        if (importBusy || SchemeSwitchCoordinator.isBusy()) return
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
                val partZip = File(context.cacheDir, "$IMPORT_TMP_ZIP_NAME.part")
                try {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("openInputStream null")
                        input.use { src ->
                            partZip.outputStream().use { output ->
                                val buf = ByteArray(8 * 1024)
                                var copied = 0L
                                while (true) {
                                    ensureActive()
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    copied += n
                                    if (copied > SchemePackageImport.MAX_ZIP_BYTES) {
                                        throw ZipTooLargeException()
                                    }
                                    output.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    // 成功关闭后 rename 为固定名（同目录 rename，失败即复制失败）。
                    if (tmpZip.exists()) tmpZip.delete()
                    if (!partZip.renameTo(tmpZip)) {
                        throw IllegalStateException("rename part to tmp failed")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ZipTooLargeException) {
                    importError = ImportErrorKind.TOO_LARGE
                    return@launch
                } catch (e: java.io.IOException) {
                    val msg = e.message.orEmpty()
                    importError = if (msg.contains("No space left", ignoreCase = true) ||
                        msg.contains("ENOSPC")
                    ) {
                        ImportErrorKind.IO_ERROR
                    } else {
                        ImportErrorKind.COPY_FAILED
                    }
                    return@launch
                } catch (e: Exception) {
                    importError = ImportErrorKind.COPY_FAILED
                    return@launch
                } finally {
                    // 只删 .part：成功时它已被 rename 走（删空操作），失败 /
                    // 取消时清掉半复制残留；固定名 tmpZip 绝不在此删（成功
                    // 路径随后要拿它校验）。
                    partZip.delete()
                }
                // magic 校验：非 zip 在此拦（NOT_ZIP）；真 zip（含 .cskin）
                // 过 magic 后走正常校验流程，无方案时由规划报 NO_SCHEMA。
                val magicOk = withContext(Dispatchers.IO) {
                    runCatching {
                        tmpZip.inputStream().use { input ->
                            val header = ByteArray(4)
                            var read = 0
                            while (read < 4) {
                                val n = input.read(header, read, 4 - read)
                                if (n < 0) break
                                read += n
                            }
                            SchemePackageImport.hasZipMagic(header.copyOf(read))
                        }
                    }.getOrDefault(false)
                }
                if (!magicOk) {
                    importError = ImportErrorKind.NOT_ZIP
                    importStagedEntries = emptyList()
                    importKeptFiles = emptyList()
                    cleanupImportFiles()
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
     * 确认安装（多目录模型）：事务化安装到独立目录 + 登记（含目录名），
     * 再切到新目录（指针 → 启用表 → 引擎重部署 → 首位方案，失败自动
     * 回滚上一目录）。旧单目录合并链就此取代：不再往当前目录合并、
     * 不再写当前目录方案表；事务已写新目录方案表，成功后不再调
     * writeDefaultCustomSchemaList。
     * 顺序与引擎：
     * - 入口先调 recoverIncompleteTransactions（方案目录根），清掉上次
     *   崩溃留下的中间态；importInstalling 守门保证单 flight（仅在无
     *   安装进行时调 recover，见事务注释）。
     * - 引擎没有可安全调用的只停不停启入口：finalize 会销毁引擎（关
     *   actions/jobs 通道、取消作用域），此后 reload 无法复活它；
     *   Rime.shutdown 只在 deploy() 内经 rime-main 调用，屏侧直调会
     *   绕过生命周期与调度器。本批文件范围（RimeEngine 仅 setRunning /
     *   join 点，IEngine / RimeApi 不在范围）加不出停机入口，故安装期
     *   不做 native 停机，只以 EngineDeployState 占位：挡住 FAB 与页面
     *   部署入口在安装期另起部署（它们读 isRunning 守门）；安装目标
     *   是独立新目录，引擎仍跑在旧目录上，文件级互斥由事务候选目录 +
     *   原子换位保证。残余风险见汇报（目标恰为当前目录时换位与运行
     *   中引擎共用同一目录）。
     * - 成功：写登记、把新目录启用 id 设为新方案表顺序（重装即整包
     *   替换，旧顺序对新包无意义），再 switchDirectory(forceReload=true)。
     *   switch 成功 → onSwitchDone 清备份；switch 失败 → 先
     *   onBeforeRollback 恢复目录内容，再走 switch 已有的指针 / 顺序
     *   回滚（见 switchDirectory 注释），终态一致。
     * - 安装失败：失败原因与重启动作的顺序是 reload 派发在先、失败态
     *   置位在后——reload 是异步派发，置位不等它完成；引擎为空时直接
     *   复位标志，绝不把引擎留在停机态 / 标志留在置位态。SWAP_FAILED
     *   时先在失败处理内调 recover 恢复到完整旧版（与事务注释“重试
     *   前 recover”一致，只是提前到失败处理内做，不等用户下次确认），
     *   再 reload、再置失败态。失败映射：PREVALIDATION →
     *   INVALID_PACKAGE，其余（COPY_FAILED、VERIFICATION_FAILED、
     *   NOTHING_INSTALLED、SWAP_FAILED）→ INSTALL_FAILED。
     * 安装失败留在校验页并可重试（登记 upsert，重试安全）。
     */
    fun confirmImport() {
        val plan = importPlan ?: return
        val staging = importStagingDir ?: return
        val targetDir = importTargetDir ?: return
        val stagedEntries = importStagedEntries
        if (importInstalling) return
        // 切换进行中不启动安装：否则安装成功后的 switch 早退、备份无人
        // 清理、新目录无人激活；安装开始前拦截，不动任何文件。
        if (dirSwitching ||
            DirSwitchMonitor.status.value.phase == DirSwitchPhase.SWITCHING ||
            SchemeSwitchCoordinator.isBusy()
        ) {
            importError = ImportErrorKind.INSTALL_FAILED
            return
        }
        // 导入安装同样持有协调器操作锁：安装全程拒绝点选切换与目录
        // 切换；安装成功后把锁移交给 switchDirectory（见下），切换协程
        // 结束时释放，中间不留另一入口插队的窗口。
        val operationId = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.IMPORT_INSTALL,
            targetDir.name,
            targetDir.name,
        ) ?: run {
            importError = ImportErrorKind.INSTALL_FAILED
            return
        }
        importInstalling = true
        importError = null
        scope.launch {
            var transferred = false
            try {
                val dirName = targetDir.name
                val schemeIds = plan.schemes.map { it.id }
                // 恢复 + 事务安装（NonCancellable：换位中途取消不得留半态）。
                val transaction = withContext(Dispatchers.IO + NonCancellable) {
                    SchemePackageImport.recoverIncompleteTransactions(
                        DataManager.schemeDirsRoot,
                    )
                    // 安装期占位标志（见上）：挡并发部署；成功时 switch 的
                    // reload 作业 finally 复位，失败时下文 reload / 直复位。
                    EngineDeployState.setRunning(true)
                    when (
                        val result = SchemePackageImport.installTransaction(
                            stagingDir = staging,
                            targetDir = targetDir,
                            plan = plan,
                            stagedEntries = stagedEntries,
                            schemaList = schemeIds,
                        )
                    ) {
                        is SchemePackageImport.InstallTransactionResult.Success ->
                            result.transaction
                        is SchemePackageImport.InstallTransactionResult.Failed ->
                            throw InstallFailedException(result.failure)
                    }
                }
                val registry = SchemePackageImport.decodeRegistry(
                    importPrefs.getString(SchemePackageImport.KEY_PACKAGE_REGISTRY, null),
                )
                val record = SchemePackageImport.SchemePackageRecord(
                    packageName = plan.packageName,
                    version = plan.packageVersion,
                    schemeIds = schemeIds,
                    files = transaction.installedFiles,
                    source = importSource,
                    dir = dirName,
                )
                importPrefs.edit {
                    putString(
                        SchemePackageImport.KEY_PACKAGE_REGISTRY,
                        SchemePackageImport.encodeRegistry(registry.withRecord(record)),
                    )
                }
                // 新目录启用 id = 新方案表顺序（见上）；switchDirectory
                // 会先读分目录键，旧顺序不覆盖会让切换用错表。
                SchemaManager.saveEnabledIds(prefs, dirName, schemeIds)
                val backupDir = transaction.backupDir
                cleanupImportFiles()
                importPlan = null
                importStagedEntries = emptyList()
                importKeptFiles = emptyList()
                importInstalling = false
                // 安装成功后切到新目录（看切换结果），下载交接的来源标记复位。
                downloadPage = false
                importFromDownload = false
                importSource = SchemePackageImport.PackageSource.LOCAL
                val started = switchDirectory(
                    dirName,
                    forceReload = true,
                    onBeforeRollback = {
                        SchemePackageImport.rollbackTransaction(backupDir, targetDir)
                    },
                    onSwitchDone = { ok ->
                        if (ok) SchemePackageImport.cleanupBackup(backupDir)
                    },
                    existingOperationId = operationId,
                )
                transferred = started
                if (!started) {
                    // 守门竞态：安装已换位、切换未启动。标志由本处直复位
                    // （无 reload 作业会复位它）；备份与日志保留，新目录
                    // 内容完整，用户重新确认导入即可重装或手动切换。
                    EngineDeployState.setRunning(false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    importInstalling = false
                    throw e
                }
                val failure = (e as? InstallFailedException)?.failure
                // 失败先重启引擎、再置失败态（顺序见上）：安装失败时引擎
                // 仍在旧目录上，reload 重部署旧目录；SWAP_FAILED 先恢复
                // 到完整旧版再 reload；引擎为空则直复位标志。
                if (failure == SchemePackageImport.TransactionFailure.SWAP_FAILED) {
                    // NonCancellable：恢复中途取消会把失败处理本身打断、
                    // importInstalling 滞留 true，恢复必须跑完。
                    withContext(Dispatchers.IO + NonCancellable) {
                        SchemePackageImport.recoverIncompleteTransactions(
                            DataManager.schemeDirsRoot,
                        )
                    }
                }
                val engine = EngineFactory.current()
                if (engine != null) {
                    engine.reload()
                } else {
                    EngineDeployState.setRunning(false)
                }
                importError = if (failure ==
                    SchemePackageImport.TransactionFailure.PREVALIDATION
                ) {
                    ImportErrorKind.INVALID_PACKAGE
                } else {
                    ImportErrorKind.INSTALL_FAILED
                }
                importInstalling = false
            } finally {
                // 未移交时释放导入操作锁；已移交切换时由切换协程结束时
                // 释放（本处不重复释放，endOperation 凭 id 校验亦会挡下）。
                if (!transferred) {
                    SchemeSwitchCoordinator.endOperation(operationId)
                }
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
     * 失败按下载口径明示并删暂存。下载跑应用级作用域（appScope，与
     * 协调器同生命周期）：离开页面不再取消下载；取消仍经 cancelDownload
     * （Call + Job 双中断），暂存残留 cacheDir 由系统清理（与二期同口径）。
     * 局限：进度与校验交接状态仍是页内快照态，重进页面看不到进行中
     * 的下载、完成交接写到已销毁的组合态即丢失（下载本身不取消、
     * 临时 zip 仍在缓存），重进需重新下载才可见结果——进程级下载
     * 状态不在本批范围，见汇报残余项。
     */
    fun startDownload(
        entryName: String,
        url: String,
        headers: List<Pair<String, String>>,
    ) {
        if (downloadRunning || importBusy || SchemeSwitchCoordinator.isBusy()) return
        downloadRunning = true
        downloadError = null
        downloadProgressBytes = 0L
        downloadProgressTotal = -1L
        downloadProgressName = entryName
        val job = appScope.launch {
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
                                enabled = !SchemeSwitchCoordinator.isBusy() && !importBusy &&
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
                                enabled = !SchemeSwitchCoordinator.isBusy() && !importBusy &&
                                    !downloadRunning,
                                onClick = {
                                    menuExpanded = false
                                    // MIME 放宽到 */*：部分文件管理器给 zip
                                    // 标别的类型会拦选；非 zip 由复制后的
                                    // magic 校验拦（NOT_ZIP），.cskin 本身
                                    // 是 zip、过 magic 后走正常校验流程。
                                    importLauncher.launch(arrayOf("*/*"))
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
                                enabled = !SchemeSwitchCoordinator.isBusy() && !importBusy &&
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
                keptFiles = importKeptFiles,
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
            // diskEmpty 已是状态值（读盘在 LaunchedEffect(currentDir) 里做），
            // 此处不再组合期读盘。
            val diskEmptyNow = diskEmpty
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
                } else if (!diskEmptyNow) {
                    // 重试：先重读引擎表，仍空则走续切（续切内已先同步
                    // custom 文件再部署），再重读一次。
                    val onRetry: () -> Unit = {
                        scope.launch {
                            loadSchemas()
                            if (!loaded) {
                                resumeIfStranded()
                                loadSchemas()
                            }
                        }
                    }
                    // 无部署在跑却仍空表 = 部署已结束的 stranded 态：不再
                    // 转无限进度条，改明示 + 重试；部署在跑时才显部署中。
                    if (dirSwitching || deployRunning) {
                        Text(
                            stringResource(R.string.schema_deploying),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.schema_switch_retry))
                        }
                    } else {
                        Text(
                            stringResource(R.string.schema_deploy_stranded),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.schema_switch_retry))
                        }
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
            // 迁移失败卡（W1 接口）：非 null 时显示原因 + 重试（重跑
            // ensureMigrated 后刷新显示；成功时偏好里的原因已被清除，
            // 卡片随之消失）。最小 UI，不另起页面。
            val migrationError = migrationErrorText
            if (migrationError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.schema_dir_migration_failed),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            migrationError,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    SchemeDirs.ensureMigrated(
                                        DataManager.schemeDirsRoot,
                                        context,
                                    )
                                }
                                migrationErrorText = SchemeDirs.migrationError(context)
                            }
                        }) {
                            Text(stringResource(R.string.schema_switch_retry))
                        }
                    }
                }
            }
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
                                    SchemeSwitchCoordinator.dismiss()
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
                                SchemeSwitchCoordinator.dismiss()
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
            // 目录同步删除传播暂停确认入口：仅暂停标志存在时出现。
            // 点击先清除标志，再以 forceDeletePropagation=true 在应用级
            // 作用域重跑一次同步，结果经 FabActions 同样 Toast + 待显示
            // 通知；连点由 FabActions 的进程级同步守门挡下（本卡不另设
            // 本地禁用态，点后卡片即消失）。
            if (syncPause != null) {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.schema_sync_pause_title),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.schema_sync_pause_desc,
                                syncPause!!.dir,
                            ),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (!syncPause!!.reason.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                syncPause!!.reason!!,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = {
                            RimeSyncPrefs.clearDeletePropagationPaused(context)
                            syncPause = null
                            FabActions.sync(
                                context,
                                appScope,
                                sessionProvider = {
                                    RimeDaemon.createSession("schema-sync-confirm")
                                },
                                onNotConfigured = {
                                    context.startActivity(
                                        Intent(context, UserDictActivity::class.java),
                                    )
                                },
                                forceDeletePropagation = true,
                            )
                        }) {
                            Text(stringResource(R.string.schema_sync_pause_confirm))
                        }
                    }
                }
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
                        val all = enabledSchemas + availableSchemas
                        val enabledIds = newEnabled.map { it.id }.toSet()
                        val newAvailable = all.filter { it.id !in enabledIds }
                        // 内存态同步更新（主线程、无磁盘 IO）；读盘与写盘
                        // 一律移到下面应用级协程的 IO 上下文，完成后回主线
                        // 程刷新状态（appScope 即 MainScope）。
                        enabledSchemas.clear()
                        enabledSchemas.addAll(newEnabled)
                        availableSchemas.clear()
                        availableSchemas.addAll(newAvailable)
                        manageDeploying = true
                        manageDeployError = null
                        // 保存部署跑应用级作用域：离开页面不再取消部署；
                        // 取消（与失败）一律先在 NonCancellable 内回滚原
                        // 顺序再处理——此前取消在回滚之前重抛，部署失败的
                        // 新顺序就此滞留，是本处要修的位置。
                        // 顺序保证：回滚用的旧盘上表（prevYaml）必须在新表
                        // 写盘前读出，故读盘与写盘同在协程内按序跑在 IO 上。
                        appScope.launch {
                            val prevYaml = withContext(Dispatchers.IO) {
                                SchemeDirs.schemesInDir(DataManager.userDataDir)
                            }
                            try {
                                withContext(Dispatchers.IO) {
                                    SchemaManager.saveEnabledIds(
                                        prefs,
                                        currentDir,
                                        newEnabled.map { it.id },
                                    )
                                    SchemeDirs.writeDefaultCustomSchemaList(
                                        DataManager.userDataDir,
                                        newEnabled.map { it.id } + newAvailable.map { it.id },
                                    )
                                }
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
                                withContext(NonCancellable) {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            SchemaManager.saveEnabledIds(
                                                prefs,
                                                currentDir,
                                                prevEnabled.map { it.id },
                                            )
                                            SchemeDirs.writeDefaultCustomSchemaList(
                                                DataManager.userDataDir,
                                                prevYaml,
                                            )
                                        }
                                        EngineFactory.current()?.reload()
                                        prevEnabled.firstOrNull()?.let {
                                            EngineFactory.current()?.selectSchema(it.id)
                                        }
                                    }
                                }
                                manageDeploying = false
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                manageDeployError = msgManageDeployFailed
                                appScope.launch { loadSchemas() }
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
 * 方案列表管理页内容：已选卡 = 勾选 + 拖拽合一（行 = 蓝勾选圈
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
    INVALID_PACKAGE,
    IO_ERROR,
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

/** 有界复制中超限的内部信号（与 IOException 分开映射为 TOO_LARGE）。 */
private class ZipTooLargeException : Exception()

/** 事务安装失败的内部信号（携带 TransactionFailure 供错误映射用）。 */
private class InstallFailedException(
    val failure: SchemePackageImport.TransactionFailure,
) : Exception()

/** zip 解压暂存目录名（cacheDir 下，用完即删）。 */
private const val IMPORT_STAGING_DIR_NAME = "scheme-import-staging"

private sealed interface ImportPlanOutcome {
    data class Ready(
        val plan: SchemeImportPlan,
        /** extractZip 的原始条目（相对路径 + 字节数），供事务安装核对用。 */
        val stagedEntries: List<SchemePackageImport.StagedEntry>,
    ) : ImportPlanOutcome

    data class Failed(val error: ImportErrorKind) : ImportPlanOutcome
}

/**
 * 旧目录受保护文件盘点（确认页“将被保留”摘要与 inheritProtected 同口径，
 * 但 default.custom.yaml 除外：事务随后会用新方案表重写它）。
 */
private fun protectedKeptFiles(dir: File): List<String> =
    SchemePackageImport.listRelativeFiles(dir).filter { rel ->
        if (rel == "default.custom.yaml") return@filter false
        val name = rel.substringAfterLast('/')
        rel.contains(".userdb") || rel.endsWith(".custom.yaml") ||
            rel == "user.yaml" || rel == "installation.yaml"
    }.sorted()

/**
 * 解压前空间预检（IO 线程调用）：按 ZipFile 中央目录声明量估算解压总量，
 * 与暂存所在分区（cacheDir）的可用空间比较；估算量 + 部署余量超过可用
 * 空间时返回 false（调用方按既有 NO_SPACE 口径报错），否则返回 true。
 * 分区口径：预检必须用 cacheDir 所在分区（暂存解压落在这里），不许用
 * schemeDirsRoot 的分区（那是最终落盘分区，解压后终检才用它）。
 * 估算局限：声明 size 为 -1 的条目（data-descriptor）按压缩大小计入，
 * 压缩大小 ≤ 解压大小，故估算量是下限——预检只保证“估算都装不下时
 * 一定失败”，不保证“预检通过就一定够”（终检仍按实际总体积把关）。
 * 估算失败（zip 打不开、StatFs 读失败）时返回 true 放行，让解压阶段
 * 的精确错误（NOT_ZIP / IO_ERROR / TOTAL_TOO_LARGE）说了算，不以
 * 含糊的预检误报 NO_SPACE。
 */
private fun precheckExtractSpace(zipFile: File, cacheDir: File): Boolean {
    val estimated = runCatching {
        java.util.zip.ZipFile(zipFile).use { zf ->
            var total = 0L
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val declared = if (entry.size >= 0L) {
                    entry.size
                } else {
                    entry.compressedSize.coerceAtLeast(0L)
                }
                total = if (Long.MAX_VALUE - total < declared) Long.MAX_VALUE else total + declared
            }
            total
        }
    }.getOrNull() ?: return true
    val available = runCatching {
        StatFs(cacheDir.path).availableBytes
    }.getOrNull() ?: return true
    val needed = if (Long.MAX_VALUE - estimated < SchemePackageImport.FREE_SPACE_MARGIN_BYTES) {
        Long.MAX_VALUE
    } else {
        estimated + SchemePackageImport.FREE_SPACE_MARGIN_BYTES
    }
    return available >= needed
}

/**
 * 由临时 zip 构造安装规划（IO 线程调用）：解压 → 登记读回 → 目标目录
 * 现存文件盘点 → 规划。失败时暂存目录已由 extractZip 清掉或为空，调用方
 * 再调 cleanupImportFiles 兜底。包名由调用方从 SAF 显示名净化后传入
 * （临时 zip 文件名固定，不能作包名）。多目录模型下现存文件按目标独立
 * 目录盘点（新目录为空表，替换安装按目录内现存文件算）；登记查找按
 * 目标目录名过滤（见 planInstall 的 dir 参数）。
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
                SchemePackageImport.ExtractFailure.TOO_MANY_ENTRIES,
                SchemePackageImport.ExtractFailure.ENTRY_PATH_TOO_LONG,
                SchemePackageImport.ExtractFailure.ENTRY_PATH_TOO_DEEP,
                SchemePackageImport.ExtractFailure.DUPLICATE_ENTRY,
                SchemePackageImport.ExtractFailure.ENTRY_CONFLICT -> ImportErrorKind.INVALID_PACKAGE
                SchemePackageImport.ExtractFailure.IO_ERROR -> ImportErrorKind.IO_ERROR
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
            dir = targetDir.name,
        )
    ) {
        is PlanResult.Success -> ImportPlanOutcome.Ready(result.plan, extracted.entries)
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
    ImportErrorKind.INVALID_PACKAGE -> stringResource(R.string.schema_import_error_invalid_package)
    ImportErrorKind.IO_ERROR -> stringResource(R.string.schema_import_error_io)
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
    keptFiles: List<String>,
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
        // 将被替换：规划落盘文件数 + 方案 id 列表（旧目录不存在时也显示）；
        // 将被保留：旧目录现存的受保护文件（旧目录不存在时不显示）。
        // 保持简化设计，不恢复文件明细。
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
                Text(
                    stringResource(
                        R.string.schema_import_summary_replace,
                        plan.installFiles.size,
                    ),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                plan.schemes.forEach { scheme ->
                    Text(
                        scheme.id,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (targetDirExists && keptFiles.isNotEmpty()) {
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
                    Text(
                        stringResource(R.string.schema_import_summary_kept),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    keptFiles.forEach { name ->
                        Text(
                            name,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
 * 方案目录浏览页（本页只做目录级操作）：
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
        // 底部栏：当前方案目录 + 使用此文件夹；列表层
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
