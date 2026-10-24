package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.data.keyboard.theme.ThemeCode
import com.jobeen.ime.skin.admin.SkinLibraryAdmin
import com.jobeen.ime.skin.draft.DraftStore
import com.jobeen.ime.skin.export.JimeExporter
import com.jobeen.ime.skin.migrate.LegacySkinSaver
import com.jobeen.ime.skin.model.Degradation
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.SkinPackages
import com.jobeen.ime.skin.runtime.SkinApplier
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.LastGoodPointer
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryStore
import com.jobeen.ime.skin.sync.SkinSync
import com.jobeen.ime.skin.sync.SkinLibrarySync
import com.jobeen.ime.ui.SkinEditorActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LibraryUiState(
    val index: LibraryIndex = LibraryIndex(),
    val active: ActiveRecord? = null,
    val lastGood: LastGoodPointer? = null,
    val syncDirty: Boolean = false,
    val drafts: List<com.jobeen.ime.skin.draft.DraftMeta> = emptyList(),
    val quotaBytes: Long = 0,
    val activeDegradations: List<Degradation> = emptyList(),
    /** 布局显示名（索引条目不带 name，从当前代 layout.json 的 meta.name 读）。 */
    val layoutNames: Map<String, String> = emptyMap(),
    /** 生效皮肤绑定的布局当前不可解析（已删除/无效）——回退状态的常驻提示面（B3 §4.6）。 */
    val activeLayoutMissing: Boolean = false,
)

private suspend fun loadLibraryState(): LibraryUiState = withContext(Dispatchers.IO) {
    val store = SkinLibrary.store
    val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        ?: LibraryIndex()
    val active = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
    val lastGood = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
    val syncState = SkinSync.stateStore.read()
    LibraryUiState(
        index = index,
        active = active,
        lastGood = lastGood,
        syncDirty = syncState.dirty,
        // 布局草稿与皮肤草稿同处一个草稿区（key 前缀 layout:）：
        // 本页只列皮肤草稿，布局草稿只在布局编辑语境出现（B3 §4.4）
        drafts = DraftStore(SkinLibrary.layout).listMetas()
            .filter { !it.skinId.startsWith(DraftStore.LAYOUT_DRAFT_KEY_PREFIX) },
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
 * 列表带生效/Last-Good/降级标记；应用、回退、回默认、删除、
 * 导入（含冲突转副本）、导出 .jime、配色二维码、旧主题另存、
 * 同步（含脏标记与逐单元结果）与编辑器入口集中于此。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(LibraryUiState()) }
    var busy by remember { mutableStateOf(false) }
    var dialogText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrCodeText by remember { mutableStateOf<String?>(null) }
    var deleteSkinId by remember { mutableStateOf<String?>(null) }
    var deleteLayoutId by remember { mutableStateOf<String?>(null) }
    var deleteLayoutReferrers by remember {
        mutableStateOf<List<SkinLibraryAdmin.LayoutReferrer>>(emptyList())
    }
    var showLegacyPicker by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var pendingExportSkinId by remember { mutableStateOf<String?>(null) }
    var pendingConflictFile by remember { mutableStateOf<File?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        scope.launch { state = loadLibraryState() }
    }

    LaunchedEffect(Unit) { state = loadLibraryState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
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
        SkinLibraryAdmin(SkinLibrary.layout, SkinLibrary.store, SkinSync.stateStore)
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
                    SkinSync.stateStore.markDirty()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("皮肤库") },
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                val activeName = state.active?.skinId?.let { id ->
                    state.index.skins.firstOrNull { it.id == id }?.name ?: id
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (activeName != null) "当前生效：$activeName" else "当前生效：内置默认主题",
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.activeDegradations.isNotEmpty()) {
                        Text(
                            text = "已降级：" + state.activeDegradations.joinToString("、") {
                                when (it) {
                                    Degradation.BLUR_DISABLED -> "背景模糊关闭（低内存）"
                                    Degradation.BACKGROUND_DOWNSAMPLED -> "背景图降采样（低内存）"
                                }
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = "库占用 ${(state.quotaBytes / 1024.0 / 1024.0).format1()} MB / 200 MB",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                runBusy {
                                    val report = SkinSync.engine(context).sync()
                                    dialogText = "同步结果" to formatSyncReport(report)
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text(if (state.syncDirty) "同步（有未同步变更）" else "同步")
                        }
                        OutlinedButton(
                            onClick = {
                                runBusy {
                                    val result = applier.rollbackToLastGood()
                                    toast("回退结果：$result")
                                }
                            },
                            enabled = !busy && state.lastGood != null &&
                                state.lastGood?.skinId != state.active?.skinId,
                        ) { Text("回退到上个皮肤") }
                        OutlinedButton(
                            onClick = {
                                runBusy {
                                    applier.revertToDefault()
                                    toast("已回退到内置默认主题")
                                }
                            },
                            enabled = !busy && state.active?.skinId != null,
                        ) { Text("回退内置默认") }
                    }
                }
            }

            item {
                Text(
                    "皮肤（${state.index.skins.size}）",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            // LazyColumn 的 key 在整列内必须唯一：分区前缀不可省——
            // 草稿的 skinId 恒等于其源皮肤 id，不加前缀必与皮肤区撞 key
            // 抛 IllegalArgumentException 整页闪退（真机崩溃实证）。
            items(state.index.skins, key = { "skin:${it.id}" }) { entry ->
                val isActive = state.active?.skinId == entry.id
                val isLastGood = state.lastGood?.skinId == entry.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(entry.name.ifBlank { entry.id }, fontWeight = FontWeight.Medium)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                runBusy {
                                    val result = applier.applySkin(
                                        SkinGenRef(entry.id, entry.hash, entry.generation),
                                    )
                                    toast("应用结果：$result")
                                }
                            },
                            enabled = !busy && !isActive,
                        ) { Text("应用") }
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    SkinEditorActivity.intentForSkin(context, entry.id),
                                )
                            },
                            enabled = !busy,
                        ) { Text("编辑") }
                        TextButton(
                            onClick = {
                                pendingExportSkinId = entry.id
                                exportLauncher.launch("${entry.name.ifBlank { entry.id }}.jime")
                            },
                            enabled = !busy,
                        ) { Text("导出") }
                        TextButton(
                            onClick = {
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
                            enabled = !busy,
                        ) { Text("配色码") }
                        TextButton(
                            onClick = { deleteSkinId = entry.id },
                            enabled = !busy,
                        ) { Text("删除") }
                    }
                }
            }

            if (state.index.layouts.isNotEmpty()) {
                item {
                    Text(
                        "布局（${state.index.layouts.size}）",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(state.index.layouts, key = { "layout:${it.id}" }) { layoutEntry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                state.layoutNames[layoutEntry.id] ?: layoutEntry.id,
                                fontSize = 14.sp,
                            )
                            Text(
                                "${layoutEntry.id} · 第 ${layoutEntry.generation} 代",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        com.jobeen.ime.ui.LayoutEditorActivity
                                            .intentForLayout(context, layoutEntry.id),
                                    )
                                },
                                enabled = !busy,
                            ) { Text("编辑") }
                            TextButton(
                                onClick = {
                                    // 先查全部引用方再弹确认（B3 §4.6 警告面）
                                    runBusy {
                                        deleteLayoutReferrers =
                                            admin.layoutReferrers(layoutEntry.id)
                                        deleteLayoutId = layoutEntry.id
                                    }
                                },
                                enabled = !busy,
                            ) { Text("删除") }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            com.jobeen.ime.ui.LayoutEditorActivity.intentForNew(context),
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) { Text("新建自创布局") }
                if (state.activeLayoutMissing) {
                    Text(
                        "提示：当前生效皮肤绑定的布局已不可用，键盘已回退到默认布局" +
                            "（在皮肤编辑中改绑或删除该绑定可消除此提示）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (state.drafts.isNotEmpty()) {
                item {
                    Text(
                        "未完成草稿（${state.drafts.size}）",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(state.drafts, key = { "draft:${it.skinId}" }) { meta ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("草稿 ${meta.skinId.take(8)}…（${meta.source}）", fontSize = 14.sp)
                        Row {
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        SkinEditorActivity.intentForSkin(context, meta.skinId),
                                    )
                                },
                            ) { Text("继续编辑") }
                            TextButton(
                                onClick = {
                                    runBusy {
                                        withContext(Dispatchers.IO) {
                                            DraftStore(SkinLibrary.layout).delete(meta.skinId)
                                        }
                                    }
                                },
                            ) { Text("删除") }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("导入 .jime 皮肤包") }
                    OutlinedButton(
                        onClick = { showLegacyPicker = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("旧主题另存为皮肤") }
                    OutlinedButton(
                        onClick = { showPresetPicker = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("从预设新建皮肤") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(SkinEditorActivity.intentForBlank(context))
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("空白新建皮肤") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---- 对话框组 ----
    dialogText?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                if (pendingConflictFile != null && title == "发现同名皮肤") {
                    TextButton(onClick = {
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
                                    SkinSync.stateStore.markDirty()
                                    dialogText = "导入完成" to formatImportReport(outcome.report)
                                }
                                is JimeImporter.Outcome.Rejected ->
                                    dialogText = "导入被拒绝" to outcome.reason
                                is JimeImporter.Outcome.ConflictPending ->
                                    dialogText = "导入失败" to "副本导入仍遇冲突"
                            }
                        }
                    }) { Text("以副本导入") }
                } else {
                    TextButton(onClick = { dialogText = null }) { Text("好") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialogText = null
                    pendingConflictFile = null
                }) { Text("取消") }
            },
        )
    }

    if (qrBitmap != null) {
        AlertDialog(
            onDismissRequest = {
                qrBitmap = null
                qrCodeText = null
            },
            title = { Text("配色二维码") },
            text = {
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
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("theme", qrCodeText ?: ""))
                    toast("短码已复制")
                }) { Text("复制短码") }
            },
            dismissButton = {
                TextButton(onClick = {
                    qrBitmap = null
                    qrCodeText = null
                }) { Text("关闭") }
            },
        )
    }

    deleteSkinId?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteSkinId = null },
            title = { Text("删除皮肤？") },
            text = {
                Text(
                    if (state.active?.skinId == target) {
                        "该皮肤正在生效，删除前会先回退到内置默认主题。删除不可恢复。"
                    } else {
                        "删除不可恢复（已同步的皮肤会经墓碑在下次同步时传播删除）。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSkinId = null }) { Text("取消") }
            },
        )
    }

    deleteLayoutId?.let { target ->
        val referrers = deleteLayoutReferrers
        AlertDialog(
            onDismissRequest = { deleteLayoutId = null },
            title = { Text("删除布局？") },
            text = {
                if (referrers.isEmpty()) {
                    Text("该布局未被任何皮肤引用。删除不可恢复（已同步的布局会经墓碑在下次同步时传播删除）。")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("以下 ${referrers.size} 个皮肤正在引用该布局：")
                        for (referrer in referrers) {
                            Text("· ${referrer.skinName}", fontSize = 13.sp)
                        }
                        Text(
                            "删除后这些皮肤将回退到默认布局；皮肤文件本身不修改" +
                                "（重新导入/同步回该布局时绑定自动恢复）。",
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteLayoutId = null
                    runBusy {
                        when (val result = admin.deleteLayout(target)) {
                            is SkinLibraryAdmin.Result.Done -> {
                                if (referrers.isNotEmpty()) {
                                    com.jobeen.ime.skin.diag.DiagLog.w(
                                        "layout", "layout_deleted_referenced",
                                        mapOf(
                                            "layoutId" to target,
                                            "referrerCount" to referrers.size,
                                        ),
                                    )
                                }
                                // 生效皮肤在引用方之列：立即重装其快照，
                                // 运行态当场回退默认布局（§4.6 即时收敛）
                                val activeId = state.active?.skinId
                                if (activeId != null &&
                                    referrers.any { it.skinId == activeId }
                                ) {
                                    val reinstalled = applier.reinstallActiveSnapshot()
                                    com.jobeen.ime.skin.diag.DiagLog.i(
                                        "layout", "layout_delete_revert_applied",
                                        mapOf(
                                            "layoutId" to target,
                                            "skinId" to activeId,
                                            "reinstalled" to reinstalled,
                                        ),
                                    )
                                    toast("已删除，生效皮肤已回退到默认布局")
                                } else {
                                    toast("已删除")
                                }
                                refresh()
                            }
                            is SkinLibraryAdmin.Result.Refused ->
                                toast("删除被拒绝：${result.reason}")
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteLayoutId = null }) { Text("取消") }
            },
        )
    }

    if (showLegacyPicker) {
        AlertDialog(
            onDismissRequest = { showLegacyPicker = false },
            title = { Text("选择旧主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (theme in KeyboardThemePresets.ALL) {
                        TextButton(onClick = {
                            showLegacyPicker = false
                            runBusy {
                                val saver = LegacySkinSaver(
                                    ImportTransaction(
                                        SkinLibrary.layout, SkinLibrary.store,
                                    ),
                                )
                                when (val result = withContext(Dispatchers.IO) {
                                    saver.save(
                                        theme,
                                        SkinSnapshotFactory.geometryFallbacks(context),
                                    )
                                }) {
                                    is LegacySkinSaver.Result.Saved -> {
                                        SkinSync.stateStore.markDirty()
                                        toast("已另存为皮肤")
                                    }
                                    is LegacySkinSaver.Result.Failed ->
                                        toast("另存失败：${result.reason}")
                                }
                            }
                        }) { Text(theme.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLegacyPicker = false }) { Text("取消") }
            },
        )
    }

    if (showPresetPicker) {
        AlertDialog(
            onDismissRequest = { showPresetPicker = false },
            title = { Text("以预设为起点新建") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (theme in KeyboardTheme.PRESETS) {
                        TextButton(onClick = {
                            showPresetPicker = false
                            context.startActivity(
                                SkinEditorActivity.intentForPreset(context, theme.id),
                            )
                        }) { Text(theme.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresetPicker = false }) { Text("取消") }
            },
        )
    }
}

private fun Double.format1(): String = "%.1f".format(this)

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
    for (issue in report.issues) {
        appendLine("· ${issue.path}：${issue.detail}")
    }
    if (isEmpty()) append("无变化")
}

private fun formatSyncReport(report: SkinLibrarySync.Report): String = buildString {
    if (report.abortedReason != null) {
        append("同步中止：${report.abortedReason}")
        return@buildString
    }
    if (report.activeSkinUpdated) {
        appendLine("注意：生效中的皮肤已同步到新版本，重新应用后生效。")
    }
    for (result in report.results) {
        val label = when (result.action) {
            SkinLibrarySync.Action.SKIPPED -> "跳过"
            SkinLibrarySync.Action.UPLOADED -> "已上传"
            SkinLibrarySync.Action.DOWNLOADED -> "已下载"
            SkinLibrarySync.Action.CONFLICT_COPY -> "冲突副本（新 id ${result.copyId}）"
            SkinLibrarySync.Action.DELETED_REMOTE -> "已删远端"
            SkinLibrarySync.Action.FAILED -> "失败（${result.detail}）"
        }
        appendLine("${result.unitKey}：$label")
    }
    if (report.results.isEmpty()) append("无可同步的变更")
}
