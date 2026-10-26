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
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.ui.FabActions
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemeDownload
import com.jobeen.ime.data.manager.ImportFailure
import com.jobeen.ime.data.manager.ImportOutcome
import com.jobeen.ime.data.manager.MAX_IMPORT_ZIP_BYTES
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.SchemeSwitchCoordinator
import com.jobeen.ime.data.manager.SchemeSwitchPolicy
import com.jobeen.ime.data.manager.SchemeEnabled
import com.jobeen.ime.data.manager.discoverSchemes
import com.jobeen.ime.data.manager.effectiveEnabledIds
import com.jobeen.ime.data.manager.importPackage
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.engine.RimeEngine
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
    // 本地信息（打开页面即计算，用于"本地信息"卡片展示）
    var currentSchemaName by remember { mutableStateOf("") }
    var editMode by remember { mutableStateOf(openManage) }
    var menuExpanded by remember { mutableStateOf(false) }
    var currentSchemeId by remember { mutableStateOf("") }
    // 多目录模型：当前目录名（引擎 user 目录 = 根 / 此名）；目录切换、
    // 导入新目录后随之更新，管理页与启用表一律按此目录读写。
    var currentDir by remember { mutableStateOf(SchemeDirs.currentDirName(context)) }
    var dirBrowser by remember { mutableStateOf(false) }
    var dirDetail by remember { mutableStateOf<String?>(null) }
    var dirSwitching by remember { mutableStateOf(false) }
    val deployRunning by EngineDeployState.running.collectAsStateWithLifecycle()
    var schemasEmpty by remember { mutableStateOf(false) }
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
    // ---- 导入：SAF 选 zip / 下载完成 → 直接导入新目录（无确认页） ----
    // importBusy 时 Scaffold 内容切到等待页；失败只 toast，不留错误页。
    // 导入落盘目标是独立新目录（同名合并覆盖）；登记不再写入（只读
    // 旧登记用于列表来源显示）。
    // 迁移失败显示：W1 接口 migrationError 的页内镜像，重试后刷新。
    var migrationErrorText by remember {
        mutableStateOf(SchemeDirs.migrationError(context))
    }
    var importBusy by remember { mutableStateOf(false) }
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

    // 方案列表来自磁盘发现（当前目录根＋shared 根，绝不递归），不再读引擎实时表：
    // 部署中列表不变空；导入安装 / 部署完成后重调即刷新。
    suspend fun loadSchemas() {
        val currentDirFile = DataManager.userDataDir
        val sharedDirFile = DataManager.sharedDataDir
        val disk = discoverSchemes(currentDirFile, sharedDirFile)
        val allItems =
            disk.map { SchemaItem(it.id, it.name, it.layout, it.punctuation, it.kind) }
        schemasEmpty = allItems.isEmpty()
        if (allItems.isNotEmpty()) {
            // 启用顺序按当前目录启用表回落链读（custom → 目录 default.yaml →
            // shared default.yaml → 磁盘全体）；custom 文件不存在时（新目录首跑）
            // 把结果写回 custom＋镜像，之后各写操作同步维护。
            val enabledIds = effectiveEnabledIds(
                currentDirFile, sharedDirFile, disk.map { it.id }
            )
            val byId = allItems.associateBy { it.id }
            val seen = mutableSetOf<String>()
            enabledSchemas.clear()
            enabledSchemas.addAll(enabledIds.mapNotNull { byId[it]?.also { seen.add(it.id) } })
            availableSchemas.clear()
            availableSchemas.addAll(allItems.filter { it.id !in seen })
            val customFile = File(currentDirFile, "default.custom.yaml")
            if (!customFile.exists()) {
                SchemeEnabled.writeEnabledCustomFile(
                    customFile, enabledSchemas.map { it.id }
                )
                SchemeEnabled.writeEnabledMirror(prefs, enabledSchemas.map { it.id })
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
        } else {
            // 磁盘确实无方案：清掉旧列表（磁盘已知为空，不是加载中）。
            enabledSchemas.clear()
            availableSchemas.clear()
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

    // 进页加载：磁盘列表一次读完，不再轮询等待引擎表；
    // 部署中列表不变空，部署完由各入口重调 loadSchemas 刷新。
    LaunchedEffect(Unit) {
        migrationErrorText = SchemeDirs.migrationError(context)
        loadSchemas()
    }

    /**
     * 点选切换：守门→引擎直选→置顶→写回顺序，不部署、不轮询校验。
     * [onDone] 在切换结束时调用，供导入流程在切换结束后刷新方案表。
     */
    fun switchTo(target: SchemaItem, onDone: (() -> Unit)? = null) {
        if (dirSwitching || EngineDeployState.isRunning()) {
            ToastUtil.showToast(context.getString(R.string.fab_deploy_busy))
            return
        }
        val newOrder =
            listOf(target.id) + enabledSchemas.map { it.id }.filter { it != target.id }
        applyOrder(newOrder)
        EngineFactory.current()?.selectSchema(target.id)
        currentSchemeId = target.id
        onDone?.invoke()
    }

    /**
     * 多目录切换：守门→读目标启用表→指针→写 custom＋镜像→deployAndAwait→选首位方案。
     * 失败＝指针＋镜像回滚＋toast（目标 custom 已写可留，下次切过去即用；
     * 不 reload 回滚部署、不自动重试）；切换期间列表保持旧目录磁盘表（不变空）。
     * @param forceReload true 时即使目标就是当前目录也走完整部署（重装后必须重编
     * 新包内容；普通切换无变化时仍早退）。
     * @return true = 已启动切换协程，false = 守门早退（未启动）。
     */
    fun switchDirectory(
        targetDir: String,
        forceReload: Boolean = false,
    ): Boolean {
        // 第二道防线：目录浏览器不得切入 build（部署产物目录，listDirs
        // 已排除、importDirName 已把 "build" 映射为 "build-pkg"）：非法
        // 输入与函数内其他守门早退同口径，静默返回 false，不置错。
        if (targetDir == "build") return false
        if (dirSwitching || (targetDir == currentDir && !forceReload)) return false
        if (EngineDeployState.isRunning()) {
            ToastUtil.showToast(context.getString(R.string.fab_deploy_busy))
            return false
        }
        val prevDir = currentDir
        val prevOrder = enabledSchemas.map { it.id }
        dirSwitching = true
        appScope.launch {
            var succeeded = false
            try {
                val targetDirFile = File(DataManager.schemeDirsRoot, targetDir)
                val targetDisk = discoverSchemes(targetDirFile, DataManager.sharedDataDir)
                val targetEnabled = effectiveEnabledIds(
                    targetDirFile, DataManager.sharedDataDir, targetDisk.map { it.id }
                )
                SchemeDirs.setCurrentDirName(context, targetDir)
                currentDir = targetDir
                if (targetEnabled.isNotEmpty()) {
                    SchemeEnabled.writeEnabledCustomFile(
                        File(targetDirFile, "default.custom.yaml"), targetEnabled
                    )
                }
                SchemeEnabled.writeEnabledMirror(prefs, targetEnabled)
                // 部署超时随目标目录体积缩放（纯函数，见
                // SchemePackageImport.deployTimeoutMs）：小目录与旧 180 秒
                // 一致，大目录放宽、上限 1800 秒。
                val dirTotalBytes = runCatching {
                    targetDirFile.walkTopDown()
                        .filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(0L)
                val deployTimeout = SchemePackageImport.deployTimeoutMs(dirTotalBytes)
                val deployed = (EngineFactory.current() as? RimeEngine)
                    ?.deployAndAwait(deployTimeout) ?: false
                if (!deployed) throw IllegalStateException("switch deploy failed")
                if (targetEnabled.isNotEmpty()) {
                    EngineFactory.current()?.selectSchema(targetEnabled[0])
                }
                succeeded = true
            } catch (e: Exception) {
                // 失败：指针＋镜像回滚；目标 custom 已写可留，下次切过去
                // 即用；不 reload 回滚部署、不自动重试。
                runCatching {
                    SchemeDirs.setCurrentDirName(context, prevDir)
                    currentDir = prevDir
                    SchemeEnabled.writeEnabledMirror(prefs, prevOrder)
                }
                ToastUtil.showToast(context.getString(R.string.schema_dir_switch_failed))
            }
            dirSwitching = false
            if (succeeded) {
                dirBrowser = false
                dirDetail = null
            }
            loadSchemas()
        }
        return true
    }

    // ---- 导入：SAF 选 zip / 下载完成 → 直接导入新目录（无确认页） ----
    // runImportPick（SAF 复制）与 startDownload（下载完成）共用 runImport：
    // 校验→解压→T9 注入→写启用表→切指针→部署→选首位方案；失败只 toast。
    // 导入中 importBusy 置位（等待页 + 顶栏返回禁用），跑完落主列表刷新。
    /** 导入失败种类 → toast 文案（复用既有串；部署失败不称回退，因指针已留新目录）。 */
    fun importFailureString(failure: ImportFailure): Int = when (failure) {
        ImportFailure.NOT_ZIP -> R.string.schema_import_error_zip
        ImportFailure.TOO_LARGE -> R.string.schema_import_error_too_large
        ImportFailure.NO_SCHEMA -> R.string.schema_import_error_no_schema
        ImportFailure.IO_ERROR -> R.string.schema_import_error_io
        ImportFailure.DEPLOY_FAILED -> R.string.schema_import_error_install
    }

    /**
     * SAF / 下载共用的导入飞行（见 SchemeImport.importPackage）。
     * 成功与部署失败都落到主列表并刷新（部署失败时指针已留新目录，
     * 不回滚）；校验类失败只 toast，指针与列表不动。调用方负责
     * importBusy 的置位与复位、暂存 zip 的删除。
     */
    suspend fun runImport(zipFile: File, fallbackName: String) {
        val outcome = importPackage(
            zipFile = zipFile,
            fallbackName = fallbackName,
            schemeDirsRoot = DataManager.schemeDirsRoot,
            sharedDir = DataManager.sharedDataDir,
            writeMirror = { ids -> SchemeEnabled.writeEnabledMirror(prefs, ids) },
            setCurrentDir = { dir ->
                SchemeDirs.setCurrentDirName(context, dir)
                currentDir = dir
            },
            deploy = { timeout ->
                (EngineFactory.current() as? RimeEngine)?.deployAndAwait(timeout) ?: false
            },
            selectSchema = { id ->
                EngineFactory.current()?.selectSchema(id)
            },
        )
        // 落点统一为主列表（下载交接的导入也不留下载页，与旧确认成功同口径）。
        downloadPage = false
        dirBrowser = false
        dirDetail = null
        if (outcome is ImportOutcome.Failed) {
            ToastUtil.showToast(context.getString(importFailureString(outcome.failure)))
        }
        loadSchemas()
    }

    /**
     * SAF 选包后的导入：有界复制到临时 zip → 直接 runImport。
     * 复制 8KB 缓冲、每次写入前累计实际字节、超 256MiB 立即中止；
     * 每循环检查取消，CancellationException 必须重抛（不得被
     * runCatching 吞成 COPY_FAILED）；先写 .part、成功关闭后 rename
     * 为固定名，finally 只删 .part（成功时它已被 rename 走），失败 /
     * 取消不留残留；暂存 zip 由外层 finally 删（runImport 内已读完）。
     * 错误区分：超限 → TOO_LARGE；空间不足（ENOSPC，凭 IOException
     * 信息启发式判定）→ IO_ERROR；其余读取失败 → COPY_FAILED；
     * 用户取消 → 不 toast。启发式判定的局限：异常信息文本依赖平台
     * 与封装层，不含 errno 字样时会把空间不足误判为 COPY_FAILED，
     * 两者都是 toast、仅文案不同，不影响导入安全。
     */
    fun runImportPick(uri: Uri) {
        if (importBusy || EngineDeployState.isRunning() || dirSwitching) return
        importBusy = true
        scope.launch {
            val tmpZip = File(context.cacheDir, IMPORT_TMP_ZIP_NAME)
            val partZip = File(context.cacheDir, "$IMPORT_TMP_ZIP_NAME.part")
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
                                    if (copied > MAX_IMPORT_ZIP_BYTES) {
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
                    ToastUtil.showToast(
                        context.getString(R.string.schema_import_error_too_large)
                    )
                    return@launch
                } catch (e: java.io.IOException) {
                    val msg = e.message.orEmpty()
                    ToastUtil.showToast(
                        context.getString(
                            if (msg.contains("No space left", ignoreCase = true) ||
                                msg.contains("ENOSPC")
                            ) {
                                R.string.schema_import_error_io
                            } else {
                                R.string.schema_import_error_copy
                            }
                        )
                    )
                    return@launch
                } catch (e: Exception) {
                    ToastUtil.showToast(
                        context.getString(R.string.schema_import_error_copy)
                    )
                    return@launch
                } finally {
                    // 只删 .part：成功时它已被 rename 走（删空操作），失败 /
                    // 取消时清掉半复制残留；固定名 tmpZip 由外层 finally 删
                    // （成功路径随后要拿它导入）。
                    partZip.delete()
                }
                runImport(tmpZip, SchemePackageImport.sanitizePackageName(displayName))
            } finally {
                importBusy = false
                tmpZip.delete()
            }
        }
    }
    // ---- 第三期远程下载：手动 URL → cacheDir 暂存 → 直接导入 ----
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
            if (total > MAX_IMPORT_ZIP_BYTES) {
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
                        if (downloaded > MAX_IMPORT_ZIP_BYTES) {
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
     * 守门同口径），成功后直接走 runImport 导入新目录；失败按下载
     * 口径明示并删暂存。下载跑应用级作用域（appScope）：离开页面不
     * 再取消下载；取消仍经 cancelDownload（Call + Job 双中断），暂存
     * 残留 cacheDir 由系统清理。
     * 局限：进度与导入交接状态仍是页内快照态，重进页面看不到进行中
     * 的下载、完成交接写到已销毁的组合态即丢失（下载本身不取消、
     * 临时 zip 仍在缓存），重进需重新下载才可见结果——进程级下载
     * 状态不在本批范围，见汇报残余项。
     */
    fun startDownload(
        entryName: String,
        url: String,
        headers: List<Pair<String, String>>,
    ) {
        if (downloadRunning || importBusy || EngineDeployState.isRunning() || dirSwitching) return
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
                            runImport(
                                tmpZip,
                                SchemePackageImport.sanitizePackageName(entryName),
                            )
                        } finally {
                            importBusy = false
                            tmpZip.delete()
                        }
                    }
                    is DownloadOutcome.Failed -> {
                        tmpZip.delete()
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
            // 导入进行中顶栏跟随流程态：标题为导入方案（下载交接的导入
            // 进行中标题为下载方案）；返回在导入进行中禁用（导入跑完
            // 才落盘收尾，关页不中断它）。其余为原顶栏。
            val inImportFlow = importBusy
            val inDownloadFlow = downloadPage || downloadFormOpen ||
                downloadRunning || downloadError != null
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                inImportFlow ->
                                    if (downloadPage) R.string.schema_download
                                    else R.string.schema_import
                                dirBrowser -> R.string.schema_dir_switch
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
                        enabled = !importBusy && !dirSwitching,
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
                    if (downloadPage && !importBusy &&
                        !downloadFormOpen &&
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
        // 导入等待页整体替换列表内容（无确认页、无错误页，失败只 toast）。
        if (importBusy) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.schema_import_installing),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            return@Scaffold
        }
        // 第三期下载页整体替换列表内容（表单 / 进度 / 错误 / 条目列表）。
        // 导入等待分支在前：下载成功后的导入仍走等待页。
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
                switching = dirSwitching,
                onUseDir = { switchDirectory(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            )
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
            if (schemasEmpty) {
                Text(
                    stringResource(R.string.schema_dir_empty_schemes),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 行忙态：目录切换或部署进行中时禁用行点按。
            val switchBusy = dirSwitching || deployRunning
            if (editMode) {
                // 管理页：已选卡（勾选 + 拖拽合一）+ 全部方案区；草稿只在
                // 保存时提交，返回放弃。保存 = 写启用表（custom＋镜像）后走
                // 部署，部署失败恢复原顺序并明示。
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
                        // 失败恢复原启用表（custom＋镜像）并明示，不 reload
                        // 回滚部署；取消同样先恢复原表再重抛。
                        appScope.launch {
                            try {
                                val dirFile = DataManager.userDataDir
                                withContext(Dispatchers.IO) {
                                    SchemeEnabled.writeEnabledCustomFile(
                                        File(dirFile, "default.custom.yaml"),
                                        newEnabled.map { it.id },
                                    )
                                    SchemeEnabled.writeEnabledMirror(
                                        prefs, newEnabled.map { it.id }
                                    )
                                }
                                val dirTotalBytes = runCatching {
                                    dirFile.walkTopDown()
                                        .filter { it.isFile }.sumOf { it.length() }
                                }.getOrDefault(0L)
                                val deployed = (EngineFactory.current() as? RimeEngine)
                                    ?.deployAndAwait(
                                        SchemePackageImport.deployTimeoutMs(dirTotalBytes)
                                    ) ?: false
                                if (!deployed) {
                                    throw IllegalStateException("manage deploy failed")
                                }
                                newEnabled.firstOrNull()?.let {
                                    EngineFactory.current()?.selectSchema(it.id)
                                }
                                manageDeploying = false
                                editMode = false
                                loadSchemas()
                            } catch (e: Exception) {
                                withContext(NonCancellable) {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            SchemeEnabled.writeEnabledCustomFile(
                                                File(
                                                    DataManager.userDataDir,
                                                    "default.custom.yaml",
                                                ),
                                                prevEnabled.map { it.id },
                                            )
                                            SchemeEnabled.writeEnabledMirror(
                                                prefs, prevEnabled.map { it.id }
                                            )
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
    val ids = schemas.map { it.id }
    SchemeEnabled.writeEnabledCustomFile(
        File(File(DataManager.schemeDirsRoot, dir), "default.custom.yaml"),
        ids
    )
    SchemeEnabled.writeEnabledMirror(prefs, ids)
}

// ---- 远程下载：结果与错误类型（界面层） ----

/** 下载阶段结果：成功即已落到临时 zip；失败带口径与 HTTP 状态码。 */
private sealed interface DownloadOutcome {
    data object Success : DownloadOutcome
    data class Failed(
        val kind: SchemeDownload.DownloadErrorKind,
        val httpCode: Int,
    ) : DownloadOutcome
}

/** 下载错误页状态。 */
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
    // 体积上限与导入同口径、同文案（zip ≤ 256MiB）。
    SchemeDownload.DownloadErrorKind.TOO_LARGE ->
        stringResource(R.string.schema_import_error_too_large)
    SchemeDownload.DownloadErrorKind.INCOMPLETE ->
        stringResource(R.string.schema_download_error_incomplete)
}

/** SAF 选包后复制到的临时 zip 文件名（cacheDir 下，用完即删）。 */
private const val IMPORT_TMP_ZIP_NAME = "scheme-import.zip"

/** 有界复制中超限的内部信号（与 IOException 分开映射为 TOO_LARGE）。 */
private class ZipTooLargeException : Exception()

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


