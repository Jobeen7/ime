package com.jobeen.ime.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.data.App
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.library.BuiltinPreviewLayout
import com.jobeen.ime.skin.library.SkinCardPreview
import com.jobeen.ime.skin.library.SkinDevInspector
import com.jobeen.ime.skin.library.SkinMiniPreview
import com.jobeen.ime.skin.library.builtinPreviewRows
import com.jobeen.ime.skin.library.layoutPreviewRows
import com.jobeen.ime.skin.library.miniPreviewPalette
import com.jobeen.ime.skin.library.SkinLibraryPrefs
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.SkinPackages
import com.jobeen.ime.skin.runtime.SkinApplier
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 取 App versionCode（开发屏校验的 minAppVersion 门用；失败取 0）。 */
@Suppress("DEPRECATION")
private fun versionCodeOf(context: android.content.Context): Int =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }.getOrDefault(0)

private data class DevDetail(
    val prettyJson: String,
    val validation: String,
    val issueLines: List<String>,
    val snapshotOk: Boolean,
    // 检视皮肤的卡片预览（快照配色＋绑定布局真几何；快照失败为 null）。
    val preview: SkinCardPreview? = null,
)

/**
 * 皮肤开发屏（第三组新增，此前 App 内无此屏）。
 *
 * 范围（入口：皮肤库顶栏 ⋮ → 启动皮肤开发模式）：
 * ① 解析后 skin.json 检视（只读美化文本，超长截断）；
 * ② 校验报告查看（与导入同一 [SkinValidator]，含逐项回填提示）；
 * ③ 皮肤诊断日志查看（App.logsDir 最新日志文件尾 200 行）；
 * ④ 快速重载（仅生效皮肤重建快照；非生效皮肤不设应用入口）；
 * ⑤ .jime 文件导入（与库页同一 JimeImporter 管线）；
 * ⑥ 检视区预览：快照配色＋布局切换（绑定布局真几何，或九宫格/
 * 26 键/十五键/数字的出厂真几何；快照失败时只显示占位文字）。
 *
 * 明确不做什么（亦见屏内文案）：不绕过校验、不加开放动作、
 * 不直接改写代次目录文件、不削弱导入安全（清单/sha256/上限全在）、
 * 不记输入文本/候选词/剪贴板/词典/凭证进日志；skin.json 检视只读，
 * 改皮肤请用网页版编辑器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinDevScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var skins by remember {
        mutableStateOf<List<com.jobeen.ime.skin.store.SkinIndexEntry>>(emptyList())
    }
    var activeId by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<DevDetail?>(null) }
    var detailBusy by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logFileName by remember { mutableStateOf<String?>(null) }
    var perfAggregates by remember {
        mutableStateOf<Map<com.jobeen.ime.skin.perf.PerfMonitor.Event,
            com.jobeen.ime.skin.perf.PerfMonitor.Aggregate>>(emptyMap())
    }
    var dialogText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingConflictFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 检视区预览布局：null = 绑定布局（默认），非空 = 该内置布局真几何。
    var devPreviewLayout by remember { mutableStateOf<BuiltinPreviewLayout?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refreshIndex() {
        scope.launch {
            val index = withContext(Dispatchers.IO) {
                (SkinLibrary.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            }
            val active = withContext(Dispatchers.IO) {
                (SkinLibrary.store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
            }
            if (index != null) skins = index.skins
            activeId = active?.skinId
        }
    }

    fun refreshLogs() {
        scope.launch {
            val (name, lines) = withContext(Dispatchers.IO) {
                runCatching {
                    val files = App.logsDir.listFiles { f -> f.isFile }
                        ?.sortedByDescending { it.lastModified() } ?: emptyList()
                    val newest = files.firstOrNull() ?: return@runCatching null to emptyList<String>()
                    newest.name to SkinDevInspector.tailLines(newest.readText())
                }.getOrElse { error ->
                    DiagLog.w(
                        "dev", "log_view_failed",
                        mapOf("error" to (error::class.simpleName ?: "Unknown")),
                    )
                    null to emptyList()
                }
            }
            logFileName = name
            logLines = lines
        }
    }

    LaunchedEffect(Unit) {
        refreshIndex()
        refreshLogs()
    }

    LaunchedEffect(selectedId) {
        val entry = skins.firstOrNull { it.id == selectedId } ?: run {
            detail = null
            return@LaunchedEffect
        }
        detailBusy = true
        detail = withContext(Dispatchers.IO) {
            runCatching {
                val genDir = SkinLibrary.layout.fileFromRelative(entry.dir)
                val raw = File(genDir, "skin.json").readText()
                val tree = SkinJson.parseObject(raw)
                    ?: return@runCatching DevDetail(
                        prettyJson = "skin.json 解析失败（结构性损坏，校验口径与导入一致）。",
                        validation = "已拒绝：skin_unparseable",
                        issueLines = emptyList(),
                        snapshotOk = false,
                    )
                val manifest = runCatching {
                    PackageManifest.parse(File(genDir, "manifest.json").readText())
                }.getOrNull()
                val assetKindById = manifest?.contents?.associate {
                    it.id to PackageManifest.assetKindOf(it.path)
                } ?: emptyMap()
                val versionCode = versionCodeOf(context)
                val result = SkinValidator.validate(
                    tree, versionCode,
                    SkinSnapshotFactory.geometryFallbacks(context),
                    assetKind = { assetKindById[it] },
                )
                val (valid, issues, reason) = when (result) {
                    is SkinValidator.Result.Valid ->
                        Triple(true, result.issues, null)
                    is SkinValidator.Result.Rejected ->
                        Triple(false, emptyList(), result.reason)
                }
                val snapshot = SkinSnapshotFactory.buildFromGenDir(
                    context, SkinLibrary.layout,
                    SkinGenRef(entry.id, entry.hash, entry.generation),
                )
                DevDetail(
                    prettyJson = SkinDevInspector.prettyPrint(tree),
                    validation = SkinDevInspector.validationSummary(valid, issues, reason),
                    issueLines = issues.map { "${it.path}：${it.detail}" },
                    snapshotOk = snapshot != null,
                    preview = snapshot?.let {
                        SkinCardPreview(
                            palette = miniPreviewPalette(it.colorScheme),
                            layoutRows = it.resolvedLayout?.let { resolved ->
                                layoutPreviewRows(resolved.baseLayout)
                            },
                        )
                    },
                )
            }.getOrElse { error ->
                DiagLog.w(
                    "dev", "inspect_failed",
                    mapOf(
                        "skinId" to entry.id,
                        "error" to (error::class.simpleName ?: "Unknown"),
                    ),
                )
                DevDetail(
                    prettyJson = "检视失败（仅记元数据，见诊断日志）。",
                    validation = "检视失败",
                    issueLines = emptyList(),
                    snapshotOk = false,
                )
            }
        }
        detailBusy = false
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val temp = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "skin-dev-import-${System.currentTimeMillis()}.jime")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    file
                }
                val importer = SkinPackages.importer(context)
                val ctx = SkinPackages.importContext(context)
                when (val outcome = importer.importZip(temp, ctx, JimeImporter.ConflictMode.FAIL)) {
                    is JimeImporter.Outcome.Imported -> {
                        dialogText = "导入完成" to
                            "已导入皮肤：${outcome.report.importedSkinIds.joinToString()}"
                        refreshIndex()
                    }
                    is JimeImporter.Outcome.ConflictPending -> {
                        pendingConflictFile = temp
                        dialogText = "发现同名皮肤" to "包内皮肤与库中已有版本不同，可取消，或以副本（新 id）导入。"
                    }
                    is JimeImporter.Outcome.Rejected ->
                        dialogText = "导入被拒绝" to outcome.reason
                }
            } finally {
                busy = false
            }
        }
    }

    val applier = remember { SkinApplier(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("皮肤开发模式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("开发模式已开启", fontWeight = FontWeight.Bold)
                    Text(
                        "本屏只做检视、校验查看、日志查看、重载与正常导入。" +
                            "不做什么：不绕过校验、不加开放动作、不直接改写皮肤文件、" +
                            "不削弱导入安全；改皮肤请用网页版编辑器。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                            },
                            enabled = !busy,
                        ) { Text("导入 .jime") }
                        OutlinedButton(
                            onClick = { refreshLogs() },
                            enabled = !busy,
                        ) { Text("刷新日志") }
                        OutlinedButton(
                            onClick = {
                                SkinLibraryPrefs.setDevMode(context, false)
                                onBack()
                            },
                        ) { Text("关闭开发模式") }
                    }
                }
            }
            item {
                Text("皮肤（${skins.size}），点“检视”查看解析与校验", fontWeight = FontWeight.Bold)
            }
            item {
                // 性能（2026-10-29 item-1 B）：进程内聚合数字；
                // 内存单位 KB，其余单位 ms；明细在 Logs/performance
                // 的 JSONL 里，诊断 zip 导出时一并打包。
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("性能（本进程累计）", fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = {
                                perfAggregates =
                                    com.jobeen.ime.skin.perf.PerfMonitor.aggregates()
                            },
                        ) { Text("刷新性能") }
                    }
                    if (perfAggregates.isEmpty()) {
                        Text(
                            "暂无数据（点“刷新性能”读取；冷启动／按键提交／" +
                                "皮肤应用／掉帧／内存五项随使用累计）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for ((event, agg) in perfAggregates) {
                            val unit = if (event ==
                                com.jobeen.ime.skin.perf.PerfMonitor.Event.MEMORY
                            ) "KB" else "ms"
                            Text(
                                "${event.name}：${agg.count} 次，均值 " +
                                    "%.1f".format(agg.avg) + " $unit，最大 ${agg.max} $unit",
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Text(
                        "明细文件：Logs/performance/perf-日期.jsonl（只记元数据与耗时）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(skins, key = { it.id }) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name.ifBlank { entry.id }, fontSize = 14.sp)
                        Text(
                            (if (entry.id == activeId) "生效中 · " else "") +
                                "第 ${entry.generation} 版",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        TextButton(
                            onClick = { selectedId = entry.id },
                            enabled = !busy,
                        ) { Text("检视") }
                        // 快速重载只对生效皮肤：非生效皮肤的应用归库页，
                        // 开发屏不重复造一个应用入口（简洁克制，不扩范围）。
                        if (entry.id == activeId) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val ok = applier.reinstallActiveSnapshot()
                                            if (!ok) {
                                                DiagLog.w(
                                                    "dev", "reload_failed",
                                                    mapOf("skinId" to entry.id),
                                                )
                                            }
                                            toast(if (ok) "已重载" else "重载失败")
                                            refreshIndex()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) { Text("快速重载") }
                        }
                    }
                }
            }
            selectedId?.let {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("检视：$it", fontWeight = FontWeight.Bold)
                        if (detailBusy) {
                            Text("正在解析与校验…", fontSize = 13.sp)
                        } else {
                            detail?.let { d ->
                                Text("校验：${d.validation}", fontSize = 13.sp)
                                Text(
                                    "快照可构建：" + if (d.snapshotOk) "是" else "否",
                                    fontSize = 13.sp,
                                )
                                // 检视区预览：配色取本皮肤快照，几何随布局切换；
                                // 快照失败时只显示占位文字，不阻塞校验与 JSON 检视。
                                Text("预览（配色取本皮肤）：", fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { devPreviewLayout = null }) {
                                        Text(
                                            "绑定布局",
                                            fontWeight = if (devPreviewLayout == null) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                    }
                                    for (builtin in BuiltinPreviewLayout.entries) {
                                        TextButton(onClick = { devPreviewLayout = builtin }) {
                                            Text(
                                                builtin.label,
                                                fontWeight = if (devPreviewLayout == builtin) {
                                                    FontWeight.Bold
                                                } else {
                                                    FontWeight.Normal
                                                },
                                            )
                                        }
                                    }
                                }
                                if (d.preview != null) {
                                    // 委托 var 不可 smart-cast，先取局部 val。
                                    val selected = devPreviewLayout
                                    val rows = if (selected != null) {
                                        builtinPreviewRows(selected)
                                    } else {
                                        d.preview.layoutRows
                                    }
                                    SkinMiniPreview(d.preview.palette, rows)
                                } else {
                                    Text("预览不可用（快照构建失败）", fontSize = 13.sp)
                                }
                                for (line in d.issueLines) {
                                    Text("· $line", fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("skin.json（只读，超长截断）：", fontSize = 13.sp)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(320.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Text(d.prettyJson, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("诊断日志", fontWeight = FontWeight.Bold)
                    Text(
                        logFileName?.let { "最新文件：$it（尾 200 行）" } ?: "暂无诊断日志文件",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (line in logLines) {
                            Text(line, fontSize = 11.sp)
                        }
                    }
                    Text(
                        "完整导出请走设置页“导出诊断日志”（与错误日志同一缓冲链路）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    dialogText?.let { (title, body) ->
        SettingsDialog(
            onDismissRequest = {
                dialogText = null
                pendingConflictFile = null
            },
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
                            scope.launch {
                                val importer = SkinPackages.importer(context)
                                val ctx = SkinPackages.importContext(context)
                                when (val outcome = importer.importZip(
                                    file, ctx, JimeImporter.ConflictMode.COPY,
                                )) {
                                    is JimeImporter.Outcome.Imported -> {
                                        dialogText = "导入完成" to
                                            "已导入皮肤：${outcome.report.importedSkinIds.joinToString()}"
                                        refreshIndex()
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
}
