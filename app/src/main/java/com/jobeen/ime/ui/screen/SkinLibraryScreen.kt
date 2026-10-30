package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jobeen.ime.data.ThemeStore
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.data.keyboard.theme.ThemeCode
import com.jobeen.ime.data.keyboard.theme.ThemeImport
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.admin.SkinLibraryAdmin
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.export.JimeExporter
import com.jobeen.ime.skin.library.SkinCardMenuSupport
import com.jobeen.ime.skin.library.SkinColorMode
import com.jobeen.ime.skin.library.SkinLibraryPrefs
import com.jobeen.ime.skin.library.SkinMiniPreview
import com.jobeen.ime.skin.library.SkinShareResult
import com.jobeen.ime.skin.library.genericPreviewRows
import com.jobeen.ime.skin.library.miniPreviewPalette
import com.jobeen.ime.skin.library.shareSkin
import com.jobeen.ime.skin.model.Degradation
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.SkinPackages
import com.jobeen.ime.skin.qr.SkinQrImporter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.jobeen.ime.skin.runtime.SkinApplier
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyTransaction
import com.jobeen.ime.skin.store.LastGoodPointer
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LibraryUiState(
    val index: LibraryIndex = LibraryIndex(),
    val active: ActiveRecord? = null,
    val lastGood: LastGoodPointer? = null,
    val quotaBytes: Long = 0,
    val activeDegradations: List<Degradation> = emptyList(),
    /** 布局显示名（索引条目不带 name，从当前代 layout.json 的 meta.name 读）。 */
    val layoutNames: Map<String, String> = emptyMap(),
    /** 生效皮肤绑定的布局当前不可解析（已删除/无效）——回退状态的常驻提示面（B3 §4.6）。 */
    val activeLayoutMissing: Boolean = false,
)

/** 长按菜单“查看错误日志”对话框的数据：皮肤显示名＋过滤后的错误行。 */
private data class SkinLogView(
    val name: String,
    val lines: List<String>,
)

/** 长按菜单“详情”对话框的数据：索引条目＋生效态＋快照布局摘要。 */
private data class SkinDetails(
    val entry: SkinIndexEntry,
    val isActive: Boolean,
    val isLastGood: Boolean,
    val layoutParsed: Boolean,
    val layoutRows: Int?,
    val layoutKeys: Int?,
)

private suspend fun loadLibraryState(): LibraryUiState = withContext(Dispatchers.IO) {
    val store = SkinLibrary.store
    val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        ?: LibraryIndex()
    val active = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
    val lastGood = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
    LibraryUiState(
        index = index,
        active = active,
        lastGood = lastGood,
        quotaBytes = SkinLibrary.layout.quotaBytes(),
        activeDegradations = SkinSnapshotProvider.peek()
            ?.takeIf { it.ref.skinId == active?.skinId }
            ?.degradations?.toList() ?: emptyList(),
        layoutNames = index.layouts.mapNotNull { entry ->
            val name = runCatching {
                val tree = com.jobeen.ime.skin.model.SkinJson.parseObject(
                    File(
                        SkinLibrary.layout.fileFromRelative(entry.dir), "layout.json",
                    ).readText(),
                )
                ((tree?.get("meta") as? kotlinx.serialization.json.JsonObject)
                    ?.get("name") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            }.getOrNull()
            name?.let { entry.id to it }
        }.toMap(),
        activeLayoutMissing = SkinSnapshotProvider.peek()
            ?.takeIf { it.ref.skinId == active?.skinId }
            ?.layoutResolution in setOf(
                com.jobeen.ime.skin.model.LayoutResolution.MISSING,
                com.jobeen.ime.skin.model.LayoutResolution.INVALID,
            ),
    )
}

/**
 * 皮肤库管理页（详设 §1.2 旅程 1/2、§3.2/§3.4、§4.4）：
 * 纯卡片页：顶栏＋卡片网格＋顶栏 ⋮ 菜单；选中态即当前生效。
 * 皮肤卡（快照迷你预览＋名称＋⋮菜单）与内置主题卡（预览＋名称，
 * 点按即切）同页双列展示；应用、删除、导入（含冲突转副本）、
 * 导出 .jime、配色二维码集中于此。
 *
 * 第三组改版：皮肤区改为深色双列卡片网格（每卡＝快照迷你预览＋
 * 名称＋⋮菜单，选中态绿框＋对勾——用户 21:18 纠正，不用蓝框）；
 * 顶栏 ⋮ 为全局动作（导入/扫码导入/分享/导出/批量删除/颜色模式/
 * 开发模式开关/重置内置），卡片 ⋮ 为单皮肤动作（应用/导出/分享/
 * 分享二维码/配色码/删除）；长按菜单（2026-10-28）：皮肤卡长按
 * 弹出单卡操作菜单（详情/分享/导出/重新加载/查看错误日志/删除），
 * 与卡片 ⋮ 菜单并存；多选模式下长按无动作；内置主题卡不挂长按菜单；
 * 皮肤卡预览：绑定了自定义布局的画其真实行/键结构，未绑定的
 * 画通用配色示意行；内置主题卡预览一律通用示意行（只呈现配色）。
 * 纯卡片化（2026-10-28）：当前生效区块、库占用行、手动回退两钮、
 * 预览布局切换行、布局区、皮肤标题行＋行内批量删除链接、旧主题
 * 另存按钮、布局不可用提示均已删；手动回退入口去掉，应用失败时
 * 的自动安全回退不动；库占用限额仍在导入时执行，只是不显示；
 * 主题设置页脱钩（库页菜单无此项），内置主题改由本页卡片切换。
 * 扫码导入按前缀分流：v3 皮肤码走皮肤导入，旧主题码（IMEKBTHEME:）
 * 走共享旧主题导入（ThemeImport），脱钩后库页扫码为旧主题码唯一入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinLibraryScreen(
    onBack: () -> Unit,
    onOpenDev: () -> Unit = {},
    onColorModeChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(LibraryUiState()) }
    var busy by remember { mutableStateOf(false) }
    var dialogText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrCodeText by remember { mutableStateOf<String?>(null) }
    var deleteSkinId by remember { mutableStateOf<String?>(null) }
    var skinDetails by remember { mutableStateOf<SkinDetails?>(null) }
    var skinLogView by remember { mutableStateOf<SkinLogView?>(null) }
    var showTopMenu by remember { mutableStateOf(false) }
    var colorMode by remember { mutableStateOf(SkinLibraryPrefs.getColorMode(context)) }
    // 开发模式开关态（顶栏 ⋮ 菜单文案随它切换：开→“停止皮肤开发模式”，
    // 关→“启动皮肤开发模式”）；回页时重读偏好，避免开发页改动后文案滞后。
    var devMode by remember { mutableStateOf(SkinLibraryPrefs.isDevMode(context)) }
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 内置主题卡选中态（主题设置页脱钩后，库页为内置主题唯一切换入口）：
    // 偏好在本地镜像，点卡/扫码导入旧主题时同步更新，回页时重读。
    var themeFollowSystem by remember {
        mutableStateOf(KeyboardManager.Keyboard.getFollowSystem(context))
    }
    var themeId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getThemeId(context))
    }
    var themeLightId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getLightThemeId(context))
    }
    var themeDarkId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getDarkThemeId(context))
    }
    // 库页扫码旧主题满槽时的待覆盖项＋选槽对话框（与主题设置页
    // 既有满槽流程一致，选定后调 ThemeStore.overwrite＋ThemeImport.apply）。
    var pendingLegacyImport by remember { mutableStateOf<KeyboardTheme?>(null) }
    var showLegacyOverwriteDialog by remember { mutableStateOf(false) }
    // 快照迷你预览缓存（键＝id@代次）：快照构建失败只记一次日志，
    // 该卡退化为纯色占位，不阻塞整页（构建在 IO 上逐个进行）。
    val previewCache = remember {
        mutableStateMapOf<String, com.jobeen.ime.skin.library.SkinCardPreview>()
    }
    var previewFailedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 重新加载后强制重建卡片预览：同代次文件原地变化时 id@代次键
    // 不变，须另设 nonce 触发预览重建（否则沿用旧快照配色）。
    var previewNonce by remember { mutableStateOf(0) }
    var pendingExportSkinId by remember { mutableStateOf<String?>(null) }
    var qrShareSkinId by remember { mutableStateOf<String?>(null) }
    var pendingConflictFile by remember { mutableStateOf<File?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        scope.launch { state = loadLibraryState() }
    }

    LaunchedEffect(Unit) { state = loadLibraryState() }
    // 迷你预览：对索引中尚未缓存且未失败过的皮肤构建快照取配色；
    // 背景图不解码（见 SkinMiniPreview 说明），失败只记元数据日志。
    LaunchedEffect(state.index.skins, previewNonce) {
        withContext(Dispatchers.IO) {
            for (entry in state.index.skins) {
                val key = "${entry.id}@${entry.generation}"
                if (previewCache.containsKey(key) || previewFailedKeys.contains(key)) continue
                try {
                    val snapshot = SkinSnapshotFactory.buildFromGenDir(
                        context, SkinLibrary.layout,
                        SkinGenRef(entry.id, entry.hash, entry.generation),
                    )
                    if (snapshot != null) {
                        // 布局感知预览：绑定了自定义布局的皮肤画其真实
                        // 行/键结构；未绑定的画通用示意行（只呈现配色）。
                        previewCache[key] =
                            com.jobeen.ime.skin.library.SkinCardPreview(
                                palette = miniPreviewPalette(snapshot.colorScheme),
                                layoutRows = snapshot.resolvedLayout?.let {
                                    com.jobeen.ime.skin.library.layoutPreviewRows(it.baseLayout)
                                },
                            )
                    } else {
                        previewFailedKeys += key
                        DiagLog.w(
                            "library", "preview_failed",
                            mapOf("skinId" to entry.id, "error" to "snapshot_null"),
                        )
                    }
                } catch (error: Exception) {
                    previewFailedKeys += key
                    DiagLog.w(
                        "library", "preview_failed",
                        mapOf(
                            "skinId" to entry.id,
                            "error" to (error::class.simpleName ?: "Unknown"),
                        ),
                    )
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                devMode = SkinLibraryPrefs.isDevMode(context)
                themeFollowSystem = KeyboardManager.Keyboard.getFollowSystem(context)
                themeId = KeyboardManager.Keyboard.getThemeId(context)
                themeLightId = KeyboardManager.Keyboard.getLightThemeId(context)
                themeDarkId = KeyboardManager.Keyboard.getDarkThemeId(context)
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun runBusy(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } finally {
                busy = false
                state = loadLibraryState()
            }
        }
    }

    val applier = remember { SkinApplier(context) }
    val admin = remember {
        SkinLibraryAdmin(SkinLibrary.layout, SkinLibrary.store)
    }

    // ---- SAF：导入 / 导出 ----
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runBusy {
            val temp = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "skin-import-${System.currentTimeMillis()}.jime")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                file
            }
            val importer = SkinPackages.importer(context)
            val ctx = SkinPackages.importContext(context)
            when (val outcome = importer.importZip(temp, ctx, JimeImporter.ConflictMode.FAIL)) {
                is JimeImporter.Outcome.Imported -> {
                    dialogText = "导入完成" to formatImportReport(outcome.report)
                }
                is JimeImporter.Outcome.ConflictPending -> {
                    pendingConflictFile = temp
                    dialogText = "发现同名皮肤" to
                        "包内皮肤与库中已有版本不同：${outcome.conflicts.joinToString()}。" +
                        "可取消，或以副本（新 id）导入。"
                }
                is JimeImporter.Outcome.Rejected -> {
                    dialogText = "导入被拒绝" to outcome.reason
                }
            }
        }
    }

    // ---- 二维码导入（2026-10-27 新增分流，2026-10-28 按前缀分流）：
    // v3 皮肤码走 SkinQrImporter（非法包走既有导入拒绝 + 日志）；
    // 旧主题短码（IMEKBTHEME:）走共享旧主题导入（ThemeImport，
    // 与主题设置页顶栏同一逻辑），脱钩后库页扫码为旧主题码唯一入口；
    // 其他未知前缀仍走皮肤链路并按既有规则拒绝。
    // 相机权限：已授权直启扫码，未授权先走系统授权（拒绝只
    // 提示 + 记元数据日志，不弹重复权限框）。
    val qrScanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("扫描皮肤二维码")
            setOrientationLocked(false)
            setBeepEnabled(false)
        }
    }
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        if (contents.trim().startsWith(ThemeCode.PREFIX)) {
            val importedTheme = ThemeCode.decode(contents.trim())
            if (importedTheme == null) {
                dialogText = "旧主题二维码无效" to
                    "该二维码不是有效的旧主题配色码。"
                return@rememberLauncherForActivityResult
            }
            runBusy {
                when (ThemeImport.import(importedTheme)) {
                    is ThemeImport.Outcome.Applied -> {
                        ThemeImport.apply(context, importedTheme)
                        themeFollowSystem = false
                        themeId = importedTheme.id
                        dialogText = "已导入旧主题" to
                            if (state.active?.skinId != null) {
                                "已导入“${importedTheme.name}”，并设为当前主题；" +
                                    "当前有皮肤生效，主题在皮肤移除后生效。"
                            } else {
                                "已导入“${importedTheme.name}”，并设为当前主题。"
                            }
                    }
                    is ThemeImport.Outcome.NeedsOverwriteSlot -> {
                        pendingLegacyImport = importedTheme
                        showLegacyOverwriteDialog = true
                    }
                }
            }
            return@rememberLauncherForActivityResult
        }
        runBusy {
            val qrResult = SkinQrImporter.importFromQrText(context, contents)
            when (val outcome = qrResult.outcome) {
                is JimeImporter.Outcome.Imported -> {
                    dialogText = "导入完成" to formatImportReport(outcome.report)
                }
                is JimeImporter.Outcome.ConflictPending -> {
                    pendingConflictFile = qrResult.packageFile
                    dialogText = "发现同名皮肤" to
                        "包内皮肤与库中已有版本不同：${outcome.conflicts.joinToString()}。" +
                        "可取消，或以副本（新 id）导入。"
                }
                is JimeImporter.Outcome.Rejected -> {
                    dialogText = "导入被拒绝" to outcome.reason
                }
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            qrScanLauncher.launch(qrScanOptions)
        } else {
            DiagLog.w("qr", "qr_camera_denied", emptyMap())
            toast("需要相机权限才能扫码导入")
        }
    }
    fun launchQrScan() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            qrScanLauncher.launch(qrScanOptions)
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val skinId = pendingExportSkinId
        pendingExportSkinId = null
        if (uri == null || skinId == null) return@rememberLauncherForActivityResult
        runBusy {
            val result = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    JimeExporter(SkinLibrary.layout, SkinLibrary.store).export(skinId, out)
                }
            }
            toast(
                when (result) {
                    is JimeExporter.Result.Exported -> "已导出"
                    is JimeExporter.Result.Failed -> "导出失败：${result.reason}"
                    null -> "导出失败：无法写入目标文件"
                },
            )
        }
    }

    // 内置主题卡选中态派生（组合作用域内计算，LazyColumn 作用域
    // 内不可调 @Composable，故不在卡片区内算）：有皮肤生效时
    // 无主题卡选中；跟随系统时以当前系统深浅对应的那张为选中。
    val skinActive = state.active?.skinId != null
    val systemDark = isSystemInDarkTheme()
    val effectiveThemeId = if (themeFollowSystem) {
        if (systemDark) themeDarkId else themeLightId
    } else {
        themeId
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("键盘皮肤") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                        ) {
                            // 跟随系统（2026-10-29 批量第 6 项）：仅选默认
                            // 主题时出现；开启后键盘皮肤自动跟随系统深浅。
                            // 自定义皮肤生效时由颜色模式三项接管，此处不出现；
                            // 莫奈色自带动态取色，不需要此项。只翻跟随开关，
                            // 不动浅/深槽位（老用户原有槽位即合并对）。
                            if (!skinActive && effectiveThemeId in setOf(
                                    KeyboardTheme.DARK_DEFAULT_ID,
                                    KeyboardTheme.LIGHT_DEFAULT_ID,
                                )
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (themeFollowSystem) "✓ " else "") +
                                                stringResource(R.string.keyboard_theme_follow_system),
                                        )
                                    },
                                    onClick = {
                                        showTopMenu = false
                                        val follow = !themeFollowSystem
                                        KeyboardManager.Keyboard.setFollowSystem(context, follow)
                                        themeFollowSystem = follow
                                        DiagLog.i(
                                            "library", "builtin_follow_system_changed",
                                            mapOf("follow" to follow.toString()),
                                        )
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("导入皮肤") },
                                onClick = {
                                    showTopMenu = false
                                    importLauncher.launch(
                                        arrayOf("application/octet-stream", "*/*"),
                                    )
                                },
                            )
                            // 扫码导入（集成闭合）：顶栏 ⋮ 项即真实扫码入口，
                            // 与底部重复按钮二选一后只留此项；扫码所得文件
                            // 经 SkinQrImporter 走同一 JimeImporter 校验链路
                            // （清单/sha256/上限全在），不得绕过校验。
                            DropdownMenuItem(
                                text = { Text("扫码导入") },
                                onClick = {
                                    showTopMenu = false
                                    launchQrScan()
                                },
                            )
                            // 分享/导出（顶栏版，作用对象＝当前生效皮肤；无生效
                            // 皮肤时只提示，不崩不空转；与卡片 ⋮ 的同名单项共用
                            // shareSkin / exportLauncher 链路）。
                            DropdownMenuItem(
                                text = { Text("分享皮肤") },
                                onClick = {
                                    showTopMenu = false
                                    val appliedId = state.active?.skinId
                                    if (appliedId == null) {
                                        toast("没有生效中的自定义皮肤")
                                    } else {
                                        runBusy {
                                            when (shareSkin(context, appliedId)) {
                                                is SkinShareResult.Shared -> toast("已打开分享")
                                                is SkinShareResult.Failed -> toast("分享失败")
                                            }
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出皮肤") },
                                onClick = {
                                    showTopMenu = false
                                    val appliedId = state.active?.skinId
                                    val appliedEntry = appliedId?.let { id ->
                                        state.index.skins.firstOrNull { it.id == id }
                                    }
                                    if (appliedId == null || appliedEntry == null) {
                                        toast("没有生效中的自定义皮肤")
                                    } else {
                                        pendingExportSkinId = appliedId
                                        exportLauncher.launch(
                                            "${appliedEntry.name.ifBlank { appliedId }}.jime",
                                        )
                                    }
                                },
                            )
                            // 批量删除（菜单内，顺序在颜色模式之前）：
                            // 只进多选模式，退出/删除见下方多选行。
                            DropdownMenuItem(
                                text = { Text("批量删除") },
                                onClick = {
                                    showTopMenu = false
                                    if (state.index.skins.isEmpty()) {
                                        toast("库中没有皮肤")
                                    } else {
                                        multiSelect = true
                                    }
                                },
                            )
                            // 颜色模式（三项直列，当前项打勾；选择持久化后
                            // 由 Activity 重建使本页主题生效，见行为矩阵）。
                            for (mode in SkinColorMode.entries) {
                                val label = when (mode) {
                                    SkinColorMode.SYSTEM -> "跟随系统"
                                    SkinColorMode.LIGHT -> "浅色"
                                    SkinColorMode.DARK -> "深色"
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (mode == colorMode) "✓ " else "") + label,
                                        )
                                    },
                                    onClick = {
                                        showTopMenu = false
                                        SkinLibraryPrefs.setColorMode(context, mode)
                                        colorMode = mode
                                        DiagLog.i(
                                            "library", "color_mode_changed",
                                            mapOf("mode" to mode.stored),
                                        )
                                        onColorModeChanged()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (devMode) "停止皮肤开发模式" else "启动皮肤开发模式",
                                    )
                                },
                                onClick = {
                                    showTopMenu = false
                                    if (devMode) {
                                        SkinLibraryPrefs.setDevMode(context, false)
                                        devMode = false
                                        DiagLog.i("library", "dev_mode_closed", emptyMap())
                                        toast("已停止皮肤开发模式")
                                    } else {
                                        SkinLibraryPrefs.setDevMode(context, true)
                                        devMode = true
                                        DiagLog.i("library", "dev_mode_opened", emptyMap())
                                        onOpenDev()
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "重置内置键盘",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showTopMenu = false
                                    showResetConfirm = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 内置主题卡（主题设置页脱钩后，库页为内置主题唯一切换入口）：
            // 2026-10-29 批量第 6 项：暗夜＋素白合并为一张“默认”卡
            // （深浅双模式：预览与生效皆随系统深浅取对应预设；点卡即
            // 切跟随开＋浅素白／深暗夜）；落日已删（旧存值由读入口迁移，
            // 不再出卡）；莫奈色保留原行为（点卡即切固定主题）。
            // 选中态与皮肤卡一致（绿框＋对勾）；多选模式下不可点
            // （不参与批量删除）。
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val defaultTheme = KeyboardTheme.byId(
                        if (systemDark) {
                            KeyboardTheme.DARK_DEFAULT_ID
                        } else {
                            KeyboardTheme.LIGHT_DEFAULT_ID
                        },
                    )
                    BuiltinThemeCard(
                        theme = defaultTheme,
                        label = stringResource(R.string.builtin_theme_default),
                        isActive = !skinActive &&
                            effectiveThemeId in setOf(
                                KeyboardTheme.DARK_DEFAULT_ID,
                                KeyboardTheme.LIGHT_DEFAULT_ID,
                            ),
                        enabled = !busy && !multiSelect,
                        onClick = {
                            runBusy {
                                if (state.active?.skinId != null) {
                                    val reverted = applier.revertToDefault()
                                    if (reverted !is ApplyTransaction.Result.RevertedToDefault) {
                                        toast("切换失败：回退内置失败")
                                        return@runBusy
                                    }
                                }
                                KeyboardManager.Keyboard.setFollowSystem(context, true)
                                KeyboardManager.Keyboard.setLightThemeId(
                                    context, KeyboardTheme.LIGHT_DEFAULT_ID,
                                )
                                KeyboardManager.Keyboard.setDarkThemeId(
                                    context, KeyboardTheme.DARK_DEFAULT_ID,
                                )
                                themeFollowSystem = true
                                themeLightId = KeyboardTheme.LIGHT_DEFAULT_ID
                                themeDarkId = KeyboardTheme.DARK_DEFAULT_ID
                                toast("已切换到：${context.getString(R.string.builtin_theme_default)}")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    val monetTheme = KeyboardTheme.byId(MonetPalette.ID)
                    BuiltinThemeCard(
                        theme = monetTheme,
                        isActive = !skinActive && effectiveThemeId == MonetPalette.ID,
                        enabled = !busy && !multiSelect,
                        onClick = {
                            runBusy {
                                if (state.active?.skinId != null) {
                                    val reverted = applier.revertToDefault()
                                    if (reverted !is ApplyTransaction.Result.RevertedToDefault) {
                                        toast("切换失败：回退内置失败")
                                        return@runBusy
                                    }
                                }
                                KeyboardManager.Keyboard.setFollowSystem(context, false)
                                KeyboardManager.Keyboard.setThemeId(context, monetTheme.id)
                                themeFollowSystem = false
                                themeId = monetTheme.id
                                toast("已切换到：${monetTheme.name}")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // 多选行：标题行与行内批量删除链接已删（批量删除只在
            // 顶部菜单内）；多选行自带取消，退出多选不依赖已删的
            // 标题行按钮。空态短提示已删（内置 4 卡常驻，提示与卡片
            // 同屏自相矛盾）。
            item {
                if (multiSelect) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已选 ${selectedIds.size} 个", fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                multiSelect = false
                                selectedIds = emptySet()
                            },
                            enabled = !busy,
                        ) { Text("取消") }
                        Button(
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = !busy && selectedIds.isNotEmpty(),
                        ) { Text("删除所选") }
                    }
                }
            }
            // 深色双列卡片网格（第三组改版）：行内两卡，不足补空位；
            // 行 key 取两卡 id 拼合，分区前缀不可省（草稿 skinId 与皮肤
            // id 同源，撞 key 会整页闪退，见原列表注释）。
            items(
                state.index.skins.chunked(2),
                key = { row -> "skinrow:" + row.joinToString("+") { it.id } },
            ) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (entry in row) {
                        val isActive = state.active?.skinId == entry.id
                        val isLastGood = state.lastGood?.skinId == entry.id
                        // 预览布局切换行已删：卡片一律用绑定布局几何
                        // （绑定自定义布局画其真几何，未绑定画通用示意行）。
                        val cached = previewCache["${entry.id}@${entry.generation}"]
                        SkinLibraryCard(
                            entry = entry,
                            isActive = isActive,
                            isLastGood = isLastGood,
                            checked = isActive || entry.id in selectedIds,
                            multiSelect = multiSelect,
                            preview = cached,
                            enabled = !busy,
                            onCardClick = {
                                if (multiSelect) {
                                    selectedIds = if (entry.id in selectedIds) {
                                        selectedIds - entry.id
                                    } else {
                                        selectedIds + entry.id
                                    }
                                } else if (!isActive) {
                                    runBusy {
                                        val result = applier.applySkin(
                                            SkinGenRef(entry.id, entry.hash, entry.generation),
                                        )
                                        toast(applyResultMessage(result) { id ->
                                            state.index.skins.firstOrNull { it.id == id }
                                                ?.name?.ifBlank { null }
                                        })
                                    }
                                }
                            },
                            onApply = {
                                runBusy {
                                    val result = applier.applySkin(
                                        SkinGenRef(entry.id, entry.hash, entry.generation),
                                    )
                                    toast(applyResultMessage(result) { id ->
                                        state.index.skins.firstOrNull { it.id == id }
                                            ?.name?.ifBlank { null }
                                    })
                                }
                            },
                            onExport = {
                                pendingExportSkinId = entry.id
                                exportLauncher.launch("${entry.name.ifBlank { entry.id }}.jime")
                            },
                            onShare = {
                                runBusy {
                                    when (shareSkin(context, entry.id)) {
                                        is SkinShareResult.Shared -> toast("已打开分享")
                                        is SkinShareResult.Failed -> toast("分享失败")
                                    }
                                }
                            },
                            onShareQr = { qrShareSkinId = entry.id },
                            onColorCode = {
                                runBusy {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        SkinSnapshotFactory.buildFromGenDir(
                                            context, SkinLibrary.layout,
                                            SkinGenRef(entry.id, entry.hash, entry.generation),
                                        )
                                    }
                                    if (snapshot == null) {
                                        toast("该皮肤无法解析，不能生成配色码")
                                    } else {
                                        val theme = KeyboardTheme(
                                            entry.id, entry.name, snapshot.colorScheme,
                                        )
                                        qrCodeText = ThemeCode.encode(theme)
                                        qrBitmap = ThemeCode.toBitmap(qrCodeText!!)
                                    }
                                }
                            },
                            onDelete = { deleteSkinId = entry.id },
                            onDetails = {
                                runBusy {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        SkinSnapshotFactory.buildFromGenDir(
                                            context, SkinLibrary.layout,
                                            SkinGenRef(entry.id, entry.hash, entry.generation),
                                        )
                                    }
                                    val base = snapshot?.resolvedLayout?.baseLayout
                                    skinDetails = SkinDetails(
                                        entry = entry,
                                        isActive = isActive,
                                        isLastGood = isLastGood,
                                        layoutParsed = snapshot != null,
                                        layoutRows = base?.size,
                                        layoutKeys = base?.sumOf { it.size },
                                    )
                                }
                            },
                            onReload = {
                                runBusy {
                                    try {
                                        val ref = SkinGenRef(
                                            entry.id, entry.hash, entry.generation,
                                        )
                                        val result = applier.applySkin(ref)
                                        if (result is ApplyTransaction.Result.Applied ||
                                            result is ApplyTransaction.Result.AppliedPending
                                        ) {
                                            val key = "${entry.id}@${entry.generation}"
                                            previewCache.remove(key)
                                            previewFailedKeys = previewFailedKeys - key
                                            previewNonce++
                                            toast(applyResultMessage(result) { id ->
                                                state.index.skins.firstOrNull { it.id == id }
                                                    ?.name?.ifBlank { null }
                                            })
                                        } else {
                                            DiagLog.e(
                                                "library", "skin_reload_failed",
                                                mapOf(
                                                    "skinId" to entry.id,
                                                    "gen" to entry.generation,
                                                    "stage" to "apply",
                                                    "error" to (result::class.simpleName
                                                        ?: "Unknown"),
                                                ),
                                            )
                                            dialogText = "重新加载失败" to
                                                applyResultMessage(result) { id ->
                                                    state.index.skins.firstOrNull { it.id == id }
                                                        ?.name?.ifBlank { null }
                                                } + "。错误已记入诊断日志，可从该皮肤长按菜单的“查看错误日志”查看。"
                                        }
                                    } catch (error: Exception) {
                                        DiagLog.e(
                                            "library", "skin_reload_failed",
                                            mapOf(
                                                "skinId" to entry.id,
                                                "gen" to entry.generation,
                                                "stage" to "reload",
                                                "error" to (error::class.simpleName ?: "Unknown"),
                                            ),
                                        )
                                        dialogText = "重新加载失败" to
                                            "重新加载时出现异常，已记入诊断日志，可从该皮肤长按菜单的“查看错误日志”查看。"
                                    }
                                }
                            },
                            onViewLog = {
                                runBusy {
                                    val lines = withContext(Dispatchers.IO) {
                                        SkinCardMenuSupport.loadSkinLogLines(
                                            DiagLog.logsDir(), entry.id,
                                        )
                                    }
                                    skinLogView = SkinLogView(
                                        name = entry.name.ifBlank { entry.id },
                                        lines = lines,
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---- 对话框组 ----
    dialogText?.let { (title, body) ->
        SettingsDialog(
            onDismissRequest = { dialogText = null },
            title = title,
            body = { Text(body) },
            actions = listOf(
                SettingsDialogAction(
                    text = "取消",
                    onClick = {
                        dialogText = null
                        pendingConflictFile = null
                    },
                ),
                if (pendingConflictFile != null && title == "发现同名皮肤") {
                    SettingsDialogAction(
                        text = "以副本导入",
                        onClick = {
                            val file = pendingConflictFile!!
                            pendingConflictFile = null
                            dialogText = null
                            runBusy {
                                val importer = SkinPackages.importer(context)
                                val ctx = SkinPackages.importContext(context)
                                when (val outcome = importer.importZip(
                                    file, ctx, JimeImporter.ConflictMode.COPY,
                                )) {
                                    is JimeImporter.Outcome.Imported -> {
                                        dialogText = "导入完成" to formatImportReport(outcome.report)
                                    }
                                    is JimeImporter.Outcome.Rejected ->
                                        dialogText = "导入被拒绝" to outcome.reason
                                    is JimeImporter.Outcome.ConflictPending ->
                                        dialogText = "导入失败" to "副本导入仍遇冲突"
                                }
                            }
                        },
                    )
                } else {
                    SettingsDialogAction(
                        text = "好",
                        onClick = { dialogText = null },
                    )
                },
            ),
        )
    }

    if (qrBitmap != null) {
        SettingsDialog(
            onDismissRequest = {
                qrBitmap = null
                qrCodeText = null
            },
            title = "配色二维码",
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "配色二维码",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "当前分享仅包含配色；改键、背景图、音效、布局与底距" +
                            "请通过完整 .jime 文件导出分享。",
                        fontSize = 13.sp,
                    )
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = "关闭",
                    onClick = {
                        qrBitmap = null
                        qrCodeText = null
                    },
                ),
                SettingsDialogAction(
                    text = "复制短码",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("theme", qrCodeText ?: ""))
                        toast("短码已复制")
                    },
                ),
            ),
        )
    }

    qrShareSkinId?.let { target ->
        QrShareDialog(
            skinId = target,
            onDismiss = { qrShareSkinId = null },
            onShareJime = {
                qrShareSkinId = null
                runBusy {
                    when (shareSkin(context, target)) {
                        is SkinShareResult.Shared -> toast("已打开分享")
                        is SkinShareResult.Failed -> toast("分享失败")
                    }
                }
            },
        )
    }

    deleteSkinId?.let { target ->
        SettingsConfirmDialog(
            onDismissRequest = { deleteSkinId = null },
            title = "删除皮肤？",
            text = if (state.active?.skinId == target) {
                "该皮肤正在生效，删除前会先回退到内置默认主题。删除不可恢复。"
            } else {
                "删除不可恢复。"
            },
            confirmText = "删除",
            onConfirm = {
                deleteSkinId = null
                runBusy {
                    when (val result = admin.deleteSkin(target) {
                        applier.revertToDefault() is
                            com.jobeen.ime.skin.store.ApplyTransaction.Result
                                .RevertedToDefault
                    }) {
                        is SkinLibraryAdmin.Result.Done -> toast("已删除")
                        is SkinLibraryAdmin.Result.Refused ->
                            toast("删除被拒绝：${result.reason}")
                    }
                }
            },
            dismissText = "取消",
        )
    }

    skinDetails?.let { details ->
        SettingsDialog(
            onDismissRequest = { skinDetails = null },
            title = "皮肤详情",
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((label, value) in SkinCardMenuSupport.skinDetailRows(
                        name = details.entry.name,
                        id = details.entry.id,
                        generation = details.entry.generation,
                        sizeBytes = details.entry.sizeBytes,
                        updatedAt = details.entry.updatedAt,
                        isActive = details.isActive,
                        isLastGood = details.isLastGood,
                        layoutParsed = details.layoutParsed,
                        layoutRows = details.layoutRows,
                        layoutKeys = details.layoutKeys,
                    )) {
                        Text("$label：$value", fontSize = 13.sp)
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(text = "好", onClick = { skinDetails = null }),
            ),
        )
    }

    skinLogView?.let { view ->
        SettingsDialog(
            onDismissRequest = { skinLogView = null },
            title = "错误日志",
            scrollable = false,
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "皮肤“${view.name}”的最近错误日志（WARN/ERROR，最多 50 条，新→旧）：",
                        fontSize = 13.sp,
                    )
                    if (view.lines.isEmpty()) {
                        Text(
                            "暂无错误日志。完整日志可通过设置页导出诊断日志查看。",
                            fontSize = 13.sp,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (line in view.lines) {
                                Text(
                                    line,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(text = "好", onClick = { skinLogView = null }),
            ),
        )
    }

    if (showResetConfirm) {
        SettingsConfirmDialog(
            onDismissRequest = { showResetConfirm = false },
            title = "重置内置键盘？",
            text = "仅将当前生效皮肤切回内置默认主题。你的皮肤、布局" +
                "与各项设置（包括颜色模式选择）不受影响，" +
                "不会删除任何用户皮肤与数据。",
            confirmText = "重置",
            onConfirm = {
                showResetConfirm = false
                runBusy {
                    val previous = state.active?.skinId
                    applier.revertToDefault()
                    DiagLog.i(
                        "library", "builtin_reset",
                        mapOf("previousSkinId" to (previous ?: "builtin")),
                    )
                    toast("已恢复内置默认主题")
                }
            },
            dismissText = "取消",
        )
    }

    if (showBatchDeleteConfirm) {
        val targets = state.index.skins.filter { it.id in selectedIds }
        SettingsConfirmDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = "删除所选皮肤？",
            text = "将删除 ${targets.size} 个皮肤，不可恢复。生效中的皮肤会先" +
                "回退内置默认；布局与其他皮肤不受影响。",
            confirmText = "删除",
            onConfirm = {
                showBatchDeleteConfirm = false
                val ids = selectedIds.toList()
                multiSelect = false
                selectedIds = emptySet()
                runBusy {
                    var done = 0
                    var refused = 0
                    for (id in ids) {
                        when (admin.deleteSkin(id) {
                            applier.revertToDefault() is
                                com.jobeen.ime.skin.store.ApplyTransaction.Result
                                    .RevertedToDefault
                        }) {
                            is SkinLibraryAdmin.Result.Done -> done++
                            is SkinLibraryAdmin.Result.Refused -> refused++
                        }
                    }
                    DiagLog.i(
                        "library", "batch_delete_done",
                        mapOf(
                            "requested" to ids.size,
                            "done" to done,
                            "refused" to refused,
                            ),
                        )
                    toast("批量删除完成：成功 $done 个，失败 $refused 个")
                }
            },
            dismissText = "取消",
        )
    }

    // 库页扫码旧主题满槽时的选槽对话框（与主题设置页既有满槽流程一致）：
    // 选一现有自定义主题槽位覆盖；自定义主题最多 2 个，满槽时此列表非空。
    if (showLegacyOverwriteDialog && pendingLegacyImport != null) {
        val pending = pendingLegacyImport!!
        val existing = KeyboardThemePresets.customThemes
        SettingsDialog(
            onDismissRequest = {
                showLegacyOverwriteDialog = false
                pendingLegacyImport = null
            },
            title = "自定义主题已满",
            body = {
                if (existing.isEmpty()) {
                    Text("没有可覆盖的自定义主题。")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("请选择一个要覆盖的自定义主题：")
                        for ((index, theme) in existing.withIndex()) {
                            TextButton(
                                onClick = {
                                    showLegacyOverwriteDialog = false
                                    pendingLegacyImport = null
                                    runBusy {
                                        ThemeStore.overwrite(index, pending)
                                        ThemeImport.apply(context, pending)
                                        themeFollowSystem = false
                                        themeId = pending.id
                                        dialogText = "已导入旧主题" to
                                            "已覆盖“${theme.name}”，并设“${pending.name}”为当前主题。"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = theme.name,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = "取消",
                    onClick = {
                        showLegacyOverwriteDialog = false
                        pendingLegacyImport = null
                    },
                ),
            ),
        )
    }

}

private fun formatImportReport(report: JimeImporter.ImportReport): String = buildString {
    if (report.importedSkinIds.isNotEmpty()) {
        appendLine("已导入皮肤：${report.importedSkinIds.joinToString()}")
    }
    if (report.importedLayoutIds.isNotEmpty()) {
        appendLine("已导入布局：${report.importedLayoutIds.joinToString()}")
    }
    if (report.skippedSkinIds.isNotEmpty()) {
        appendLine("已跳过（内容相同）：${report.skippedSkinIds.joinToString()}")
    }
    if (report.copies.isNotEmpty()) {
        appendLine("冲突副本：${report.copies.entries.joinToString { "${it.key} → ${it.value}" }}")
    }
    if (report.backgroundDropped) {
        appendLine("注意：背景图因资源问题已回退为纯色")
    }
    if (report.unmappedKeys.isNotEmpty()) {
        appendLine("未映射的按键（请在编辑器里重绑）：${report.unmappedKeys.joinToString()}")
    }
    for (issue in report.issues) {
        appendLine("· ${issue.path}：${issue.detail}")
    }
    if (isEmpty()) append("无变化")
}

/**
 * 皮肤卡片（第三组改版）：跟随页面主题的卡＋快照迷你预览＋名称＋⋮菜单。
 * 选中态（生效中，或多选模式下已勾选）用绿框＋对勾——用户 21:18
 * 明确纠正，不用蓝框。卡片底色＝莫奈色容器色，
 * 文字跟随正文色；快照未建成时显示纯色占位，不阻塞整页。
 */
@Composable
private fun SkinLibraryCard(
    entry: com.jobeen.ime.skin.store.SkinIndexEntry,
    isActive: Boolean,
    isLastGood: Boolean,
    checked: Boolean,
    multiSelect: Boolean,
    preview: com.jobeen.ime.skin.library.SkinCardPreview?,
    enabled: Boolean,
    onCardClick: () -> Unit,
    onApply: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onShareQr: () -> Unit,
    onColorCode: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onReload: () -> Unit,
    onViewLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showLongPressMenu by remember { mutableStateOf(false) }
    val borderColor = if (checked) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LocalMonetCardColor.current)
            .border(
                width = if (checked) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onCardClick,
                onLongClick = { if (!multiSelect) showLongPressMenu = true },
                onLongClickLabel = "更多操作",
            ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box {
                if (preview != null) {
                    SkinMiniPreview(preview.palette, preview.layoutRows)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                if (checked) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(20.dp)
                            .background(Color(0xFF4CAF50), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = if (multiSelect) "已选" else "生效中",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { showMenu = true },
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "皮肤菜单",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("应用") },
                        onClick = { showMenu = false; onApply() },
                        enabled = !isActive,
                    )
                    DropdownMenuItem(
                        text = { Text("导出") },
                        onClick = { showMenu = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("分享皮肤") },
                        onClick = { showMenu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("分享二维码") },
                        onClick = { showMenu = false; onShareQr() },
                    )
                    DropdownMenuItem(
                        text = { Text("配色码") },
                        onClick = { showMenu = false; onColorCode() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showMenu = false; onDelete() },
                    )
                }
                DropdownMenu(
                    expanded = showLongPressMenu,
                    onDismissRequest = { showLongPressMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("详情") },
                        onClick = { showLongPressMenu = false; onDetails() },
                    )
                    DropdownMenuItem(
                        text = { Text("分享皮肤") },
                        onClick = { showLongPressMenu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("导出") },
                        onClick = { showLongPressMenu = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("重新加载") },
                        onClick = { showLongPressMenu = false; onReload() },
                    )
                    DropdownMenuItem(
                        text = { Text("查看错误日志") },
                        onClick = { showLongPressMenu = false; onViewLog() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showLongPressMenu = false; onDelete() },
                    )
                }
            }
            Text(
                entry.name.ifBlank { entry.id },
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = buildString {
                    if (isActive) append("生效中 ")
                    if (isLastGood) append("上个皮肤 ")
                    append("第 ${entry.generation} 版")
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 内置主题卡（主题设置页脱钩后，库页为内置主题唯一切换入口）：
 * 与皮肤卡同形（主题跟随卡＋迷你预览＋名称，选中态绿框＋对勾——
 * 用户 21:18 纠正，不用蓝框），无 ⋮ 菜单、无副标题；点按即切。
 * 预览用通用示意行只呈现配色（内置主题不绑定布局，与未绑定
 * 皮肤同例）；与同名皮肤卡的区别仅在于有无 ⋮ 菜单。
 */
@Composable
private fun BuiltinThemeCard(
    theme: KeyboardTheme,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val borderColor = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LocalMonetCardColor.current)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box {
                SkinMiniPreview(
                    palette = miniPreviewPalette(theme.colors),
                    layoutRows = genericPreviewRows(),
                )
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(20.dp)
                            .background(Color(0xFF4CAF50), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "生效中",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Text(
                label ?: theme.name,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
