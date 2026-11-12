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
import androidx.compose.ui.res.stringResource
import com.jobeen.ime.R
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
        // U-L1：先清旧内容再读新皮肤，否则点开 B 的瞬间仍看到 A 的检视。
        detail = null
        detailBusy = true
        detail = withContext(Dispatchers.IO) {
            runCatching {
                val genDir = SkinLibrary.layout.fileFromRelative(entry.dir)
                val raw = File(genDir, "skin.json").readText()
                val tree = SkinJson.parseObject(raw)
                    ?: return@runCatching DevDetail(
                        prettyJson = context.getString(R.string.skin_dev_json_parse_failed),
                        validation = context.getString(R.string.skin_dev_rejected, "skin_unparseable"),
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
                    prettyJson = context.getString(R.string.skin_dev_inspect_failed),
                    validation = context.getString(R.string.skin_dev_inspect_failed_label),
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
                        dialogText = context.getString(R.string.skin_import_done) to
                            context.getString(R.string.skin_lib_import_report_skins, outcome.report.importedSkinIds.joinToString())
                        refreshIndex()
                    }
                    is JimeImporter.Outcome.ConflictPending -> {
                        pendingConflictFile = temp
                        dialogText = context.getString(R.string.skin_import_conflict_title) to context.getString(R.string.skin_dev_import_conflict_body)
                    }
                    is JimeImporter.Outcome.Rejected ->
                        dialogText = context.getString(R.string.skin_import_rejected_title) to outcome.reason
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
                title = { Text(stringResource(R.string.skin_dev_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                    Text(stringResource(R.string.skin_dev_enabled), fontWeight = FontWeight.Bold)
                    Text(
stringResource(R.string.skin_dev_scope_note),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.skin_dev_import_jime)) }
                        OutlinedButton(
                            onClick = { refreshLogs() },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.skin_dev_refresh_logs)) }
                        OutlinedButton(
                            onClick = {
                                SkinLibraryPrefs.setDevMode(context, false)
                                onBack()
                            },
                        ) { Text(stringResource(R.string.skin_dev_close)) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.skin_dev_skin_count, skins.size), fontWeight = FontWeight.Bold)
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
                        Text(stringResource(R.string.skin_dev_perf_title), fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = {
                                perfAggregates =
                                    com.jobeen.ime.skin.perf.PerfMonitor.aggregates()
                            },
                        ) { Text(stringResource(R.string.skin_dev_refresh_perf)) }
                    }
                    if (perfAggregates.isEmpty()) {
                        Text(
stringResource(R.string.skin_dev_perf_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for ((event, agg) in perfAggregates) {
                            val unit = if (event ==
                                com.jobeen.ime.skin.perf.PerfMonitor.Event.MEMORY
                            ) "KB" else "ms"
                            Text(
                                stringResource(
                                    R.string.skin_dev_perf_row,
                                    event.name,
                                    agg.count,
                                    "%.1f".format(agg.avg),
                                    unit,
                                    agg.max,
                                ),
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.skin_dev_perf_file_note),
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
                            (if (entry.id == activeId) stringResource(R.string.skin_dev_active_prefix) else "") +
                                stringResource(R.string.skin_dev_skin_version, entry.generation),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        TextButton(
                            onClick = { selectedId = entry.id },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.skin_dev_inspect)) }
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
                                            toast(if (ok) context.getString(R.string.skin_dev_reloaded) else context.getString(R.string.skin_dev_reload_failed))
                                            refreshIndex()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) { Text(stringResource(R.string.skin_dev_quick_reload)) }
                        }
                    }
                }
            }
            selectedId?.let {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.skin_dev_inspect_title, it), fontWeight = FontWeight.Bold)
                        if (detailBusy) {
                            Text(stringResource(R.string.skin_dev_parsing), fontSize = 13.sp)
                        } else {
                            detail?.let { d ->
                                Text(stringResource(R.string.skin_dev_validation, d.validation), fontSize = 13.sp)
                                Text(
                                    stringResource(R.string.skin_dev_snapshot_ok, if (d.snapshotOk) stringResource(R.string.yes) else stringResource(R.string.no)),
                                    fontSize = 13.sp,
                                )
                                // 检视区预览：配色取本皮肤快照，几何随布局切换；
                                // 快照失败时只显示占位文字，不阻塞校验与 JSON 检视。
                                Text(stringResource(R.string.skin_dev_preview_label), fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { devPreviewLayout = null }) {
                                        Text(
                                            stringResource(R.string.skin_dev_bound_layout),
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
                                    Text(stringResource(R.string.skin_dev_preview_unavailable), fontSize = 13.sp)
                                }
                                for (line in d.issueLines) {
                                    Text("· $line", fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(stringResource(R.string.skin_dev_json_label), fontSize = 13.sp)
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
                    Text(stringResource(R.string.skin_dev_diag_log_title), fontWeight = FontWeight.Bold)
                    Text(
                        logFileName?.let { stringResource(R.string.skin_dev_latest_log, it) } ?: stringResource(R.string.skin_dev_no_log_file),
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
                        stringResource(R.string.skin_dev_export_hint),
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
                    text = stringResource(R.string.cancel),
                    onClick = {
                        dialogText = null
                        pendingConflictFile = null
                    },
                ),
                if (pendingConflictFile != null && title == stringResource(R.string.skin_import_conflict_title)) {
                    SettingsDialogAction(
                        text = stringResource(R.string.skin_import_copy),
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
                                        dialogText = context.getString(R.string.skin_import_done) to
                                            context.getString(R.string.skin_lib_import_report_skins, outcome.report.importedSkinIds.joinToString())
                                        refreshIndex()
                                    }
                                    is JimeImporter.Outcome.Rejected ->
                                        dialogText = context.getString(R.string.skin_import_rejected_title) to outcome.reason
                                    is JimeImporter.Outcome.ConflictPending ->
                                        dialogText = context.getString(R.string.import_failed) to context.getString(R.string.skin_import_copy_conflict)
                                }
                            }
                        },
                    )
                } else {
                    SettingsDialogAction(
                        text = stringResource(R.string.ok),
                        onClick = { dialogText = null },
                    )
                },
            ),
        )
    }
}
