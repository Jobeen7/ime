package com.jobeen.ime.ui.screen

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.skin.draft.ContrastWarnings
import com.jobeen.ime.skin.draft.DraftStore
import com.jobeen.ime.skin.draft.SkinDraftCommitter
import com.jobeen.ime.skin.draft.SkinTreeEditor
import com.jobeen.ime.skin.migrate.SkinTreeBuilder
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.StyleField
import com.jobeen.ime.skin.pkg.SkinPackages
import com.jobeen.ime.skin.runtime.SkinApplier
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 全局外观编辑器（定稿十四.1 B1 面 / 详设 §1.3、§5）：
 * 上 = 示意预览（简化键格，非真实键盘——逐键编辑与真机预览在
 * Batch 2）；下 = 名称 / 28 色 / 4 几何 / 背景。编辑只落草稿
 * （防抖 500ms），「应用」才经导入管线成新代次并走应用事务；
 * 进入时有未决草稿先弹恢复询问（继续/丢弃），应用前对已有
 * 皮肤做基线漂移检查（§5.3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinEditorScreen(
    skinId: String?,
    presetId: String?,
    legacyId: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draftStore = remember { DraftStore(SkinLibrary.layout) }

    var draft by remember { mutableStateOf<DraftStore.Draft?>(null) }
    var tree by remember { mutableStateOf<JsonObject?>(null) }
    var recoveryDraft by remember { mutableStateOf<DraftStore.Draft?>(null) }
    var showDriftDialog by remember { mutableStateOf(false) }
    var showLayoutDriftDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    suspend fun createFromExisting(targetId: String): DraftStore.Draft? {
        val index = (SkinLibrary.store.readIndex()
            as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
        val entry = index?.skins?.firstOrNull { it.id == targetId } ?: return null
        val genDir = SkinLibrary.layout.fileFromRelative(entry.dir)
        val sourceTree = com.jobeen.ime.skin.model.SkinJson.parseObject(
            File(genDir, "skin.json").readText(),
        ) ?: return null
        // 锚二（B3 §4.4）：立稿时录下所绑布局的当前 hash，应用前
        // 据此提示布局在草稿期间被改动（布局不在库中则不设锚）
        val anchorLayoutId = SkinTreeEditor.layoutIdOf(sourceTree)
        val anchorLayout = anchorLayoutId?.let { lid ->
            index?.layouts?.firstOrNull { it.id == lid }
        }
        return draftStore.create(
            targetId, sourceTree, DraftStore.SOURCE_EXISTING,
            baseGeneration = entry.generation, baseHash = entry.hash,
            layoutAnchorId = anchorLayout?.id,
            layoutAnchorHash = anchorLayout?.hash,
        )
    }

    suspend fun createFromSource(targetId: String, source: String): DraftStore.Draft {
        val initial = when {
            source.startsWith("preset:") -> {
                val theme = KeyboardTheme.byId(source.removePrefix("preset:"))
                    ?: KeyboardTheme.DEFAULT
                SkinTreeBuilder.buildTree(targetId, theme.name, theme.colors, geometry = null)
            }
            source.startsWith("legacy:") -> {
                val theme = KeyboardTheme.byId(source.removePrefix("legacy:"))
                    ?: KeyboardTheme.DEFAULT
                SkinTreeBuilder.buildTree(
                    targetId, theme.name, theme.colors,
                    geometry = SkinSnapshotFactory.geometryFallbacks(context),
                )
            }
            else -> SkinTreeBuilder.buildTree(
                targetId, "未命名皮肤", KeyboardTheme.DEFAULT.colors, geometry = null,
            )
        }
        return draftStore.create(targetId, initial, source, baseGeneration = 0)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (skinId != null) {
                val existing = draftStore.load(skinId)
                if (existing != null) {
                    recoveryDraft = existing
                    return@withContext
                }
                val created = createFromExisting(skinId) ?: createFromSource(
                    skinId,
                    when {
                        presetId != null -> "preset:$presetId"
                        legacyId != null -> "legacy:$legacyId"
                        else -> DraftStore.SOURCE_BLANK
                    },
                )
                draft = created
                tree = created.tree
            } else {
                val targetId = UUID.randomUUID().toString()
                val source = when {
                    presetId != null -> "preset:$presetId"
                    legacyId != null -> "legacy:$legacyId"
                    else -> DraftStore.SOURCE_BLANK
                }
                val created = createFromSource(targetId, source)
                draft = created
                tree = created.tree
            }
        }
    }

    // 草稿自动保存：变更防抖 500ms 落盘（详设 §5.2 暂定值）
    LaunchedEffect(tree) {
        val currentTree = tree ?: return@LaunchedEffect
        val currentDraft = draft ?: return@LaunchedEffect
        delay(500)
        withContext(Dispatchers.IO) {
            draftStore.save(currentDraft.meta.skinId, currentTree)
        }
    }

    DisposableEffect(Unit) {
        onDispose { SkinSnapshotProvider.evictPreviewBitmap() }
    }

    suspend fun commitAndApply() {
        val currentDraft = draft ?: return
        val committer = SkinDraftCommitter(
            layout = SkinLibrary.layout,
            store = SkinLibrary.store,
            draftStore = draftStore,
            importerProvider = {
                SkinPackages.importer(context) to SkinPackages.importContext(context)
            },
        )
        when (val result = withContext(Dispatchers.IO) { committer.commit(currentDraft) }) {
            is SkinDraftCommitter.Result.Failed -> toast("应用失败：${result.reason}")
            is SkinDraftCommitter.Result.Committed, SkinDraftCommitter.Result.NoChanges -> {
                val index = (SkinLibrary.store.readIndex()
                    as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
                val entry = index?.skins?.firstOrNull { it.id == currentDraft.meta.skinId }
                if (entry == null) {
                    toast("应用失败：入库后索引缺失")
                    return
                }
                val applied = SkinApplier(context).applySkin(
                    SkinGenRef(entry.id, entry.hash, entry.generation),
                )
                withContext(Dispatchers.IO) { draftStore.delete(currentDraft.meta.skinId) }
                toast(
                    if (applied is com.jobeen.ime.skin.store.ApplyTransaction.Result.Applied) {
                        "已应用"
                    } else {
                        "已入库（$applied）"
                    },
                )
                onClose()
            }
        }
    }

    fun onApplyClicked() {
        val currentDraft = draft ?: return
        scope.launch {
            busy = true
            try {
                if (currentDraft.meta.source == DraftStore.SOURCE_EXISTING) {
                    val index = (SkinLibrary.store.readIndex()
                        as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
                    val currentHash = index?.skins
                        ?.firstOrNull { it.id == currentDraft.meta.skinId }?.hash
                    if (currentHash != null && currentHash != currentDraft.meta.baseHash) {
                        showDriftDialog = true
                        return@launch
                    }
                }
                // 锚二布局漂移（B3 §4.4）：立稿后所绑布局被别处改动
                // （含被删除）→ 知情确认后才继续（存量 keyMappings
                // 覆写的命中对象可能已随布局变化）
                val anchorLayoutId = currentDraft.meta.layoutAnchorId
                if (anchorLayoutId != null) {
                    val indexNow = (SkinLibrary.store.readIndex()
                        as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
                    val currentLayoutHash = indexNow?.layouts
                        ?.firstOrNull { it.id == anchorLayoutId }?.hash
                    if (currentLayoutHash != currentDraft.meta.layoutAnchorHash) {
                        com.jobeen.ime.skin.diag.DiagLog.w(
                            "draft", "layout_draft_drift",
                            mapOf(
                                "skinId" to currentDraft.meta.skinId,
                                "layoutId" to anchorLayoutId,
                                "layoutPresent" to (currentLayoutHash != null),
                            ),
                        )
                        showLayoutDriftDialog = true
                        return@launch
                    }
                }
                commitAndApply()
            } finally {
                busy = false
            }
        }
    }

    if (recoveryDraft != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("发现未完成的草稿") },
            text = {
                Text(
                    "皮肤「${SkinTreeEditor.nameOf(recoveryDraft!!.tree)}」有未应用的编辑" +
                        "（来源 ${recoveryDraft!!.meta.source}）。继续编辑，或丢弃草稿重新开始。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = recoveryDraft
                    tree = recoveryDraft!!.tree
                    recoveryDraft = null
                }) { Text("继续编辑") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val old = recoveryDraft!!
                    recoveryDraft = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            draftStore.delete(old.meta.skinId)
                            val recreated = if (old.meta.source == DraftStore.SOURCE_EXISTING) {
                                createFromExisting(old.meta.skinId)
                                    ?: createFromSource(old.meta.skinId, DraftStore.SOURCE_BLANK)
                            } else {
                                createFromSource(old.meta.skinId, old.meta.source)
                            }
                            draft = recreated
                            tree = recreated.tree
                        }
                    }
                }) { Text("丢弃草稿") }
            },
        )
    }

    if (showDriftDialog) {
        AlertDialog(
            onDismissRequest = { showDriftDialog = false },
            title = { Text("原皮肤已被修改") },
            text = { Text("编辑期间该皮肤已入库新版本（可能来自同步或导入）。继续应用将以当前草稿为准生成新版本。") },
            confirmButton = {
                TextButton(onClick = {
                    showDriftDialog = false
                    scope.launch {
                        busy = true
                        try {
                            commitAndApply()
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("仍然应用") }
            },
            dismissButton = {
                TextButton(onClick = { showDriftDialog = false }) { Text("取消") }
            },
        )
    }

    if (showLayoutDriftDialog) {
        AlertDialog(
            onDismissRequest = { showLayoutDriftDialog = false },
            title = { Text("绑定的布局已被修改") },
            text = {
                Text(
                    "编辑期间该皮肤绑定的自创布局已变更（可能来自布局编辑、同步或导入）。" +
                        "继续应用将以当前布局为准；针对旧布局写的逐键覆写可能不再命中。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLayoutDriftDialog = false
                    scope.launch {
                        busy = true
                        try {
                            commitAndApply()
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("仍然应用") }
            },
            dismissButton = {
                TextButton(onClick = { showLayoutDriftDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("丢弃草稿？") },
            text = { Text("未应用的编辑将全部删除，不留副本。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    val currentDraft = draft
                    scope.launch {
                        if (currentDraft != null) {
                            withContext(Dispatchers.IO) {
                                draftStore.delete(currentDraft.meta.skinId)
                            }
                        }
                        onClose()
                    }
                }) { Text("丢弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("皮肤编辑") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showDiscardDialog = true },
                        enabled = draft != null && !busy,
                    ) { Text("丢弃") }
                    Button(
                        onClick = { onApplyClicked() },
                        enabled = draft != null && !busy,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("应用") }
                },
            )
        },
    ) { padding ->
        val currentTree = tree
        if (currentTree == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("加载中…") }
        } else {
            SkinEditorBody(
                tree = currentTree,
                onTreeChange = { tree = it },
                draftId = draft?.meta?.skinId,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun SkinEditorBody(
    tree: JsonObject,
    onTreeChange: (JsonObject) -> Unit,
    draftId: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var colorDialogField by remember { mutableStateOf<StyleField?>(null) }

    // 生效方案（预览与对比度共用）：草稿树经校验器合成，坏字段逐项回退
    val scheme = remember(tree) { previewScheme(context, tree) }
    val warnings = remember(scheme) { scheme?.let { ContrastWarnings.check(it) } ?: emptyList() }

    // 背景预览位图：草稿新选图优先，其次库内资产字节（解码采样至 ≤1024px）
    var backgroundBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val backgroundSpec = remember(tree) { SkinTreeEditor.backgroundOf(tree) }
    LaunchedEffect(backgroundSpec, draftId) {
        val spec = backgroundSpec
        backgroundBitmap = null
        SkinSnapshotProvider.evictPreviewBitmap()
        if (spec == null || draftId == null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            val draftStore = DraftStore(SkinLibrary.layout)
            val draftFile = draftStore.draftAssetFiles(draftId)
                .firstOrNull()
            val bytes = draftFile?.readBytes() ?: run {
                // 库内资产：经源代次清单由逻辑 id 解析 sha
                val index = (SkinLibrary.store.readIndex()
                    as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
                val sha = index?.skins
                    ?.firstOrNull { it.id == draftId }
                    ?.let { entry ->
                        runCatching {
                            com.jobeen.ime.skin.model.PackageManifest.parse(
                                File(
                                    SkinLibrary.layout.fileFromRelative(entry.dir),
                                    "manifest.json",
                                ).readText(),
                            )
                        }.getOrNull()?.assets()?.firstOrNull { it.id == spec.assetId }?.sha256
                    }
                sha?.let {
                    SkinLibrary.layout.assetFiles(it).firstOrNull()?.readBytes()
                }
            } ?: return@withContext null
            decodeSampled(bytes, 1024)
        }
        backgroundBitmap = bitmap
        if (bitmap != null) SkinSnapshotProvider.setPreviewBitmap(bitmap)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || draftId == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            Toast.makeText(context, "读取图片失败", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val ext = (resolver.getType(uri)?.substringAfterLast('/') ?: "png")
            .lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
        val sha = DigestUtil.sha256Hex(bytes)
        val draftStore = DraftStore(SkinLibrary.layout)
        val target = draftStore.draftAssetFile(draftId, sha, ext)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        val assetId = backgroundSpec?.assetId ?: "bg_main"
        onTreeChange(
            SkinTreeEditor.setBackground(
                tree,
                com.jobeen.ime.skin.model.BackgroundSpec(assetId, 0f, 0.5f),
            ),
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SchematicPreview(scheme = scheme, backgroundBitmap = backgroundBitmap)
            Text(
                text = "示意预览（全局配色/几何）；逐键编辑与真实预览在下方",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SkinKeyEditorSection(
                tree = tree,
                onTreeChange = onTreeChange,
                draftId = draftId,
            )
        }
        if (warnings.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                ) {
                    for (warning in warnings) {
                        Text(text = "⚠ $warning", fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = SkinTreeEditor.nameOf(tree),
                onValueChange = { onTreeChange(SkinTreeEditor.setName(tree, it.take(32))) },
                label = { Text("皮肤名称（≤32 字）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            LayoutBindingRow(tree = tree, onTreeChange = onTreeChange)
        }
        item {
            Text("几何", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        for (field in GeometryField.entries) {
            item {
                val range = when (field) {
                    GeometryField.CORNER_RADIUS -> 0f..40f
                    GeometryField.KEY_H_MARGIN, GeometryField.KEY_V_MARGIN -> 0f..20f
                    GeometryField.BOTTOM_INSET -> 0f..48f
                }
                val label = when (field) {
                    GeometryField.CORNER_RADIUS -> "按键圆角"
                    GeometryField.KEY_H_MARGIN -> "按键横间距"
                    GeometryField.KEY_V_MARGIN -> "按键纵间距"
                    GeometryField.BOTTOM_INSET -> "底部安全间距"
                }
                val value = SkinTreeEditor.geometryOf(tree, field)
                    ?: SkinSnapshotFactory.geometryFallbacks(context).let {
                        when (field) {
                            GeometryField.CORNER_RADIUS -> it.cornerRadius
                            GeometryField.KEY_H_MARGIN -> it.keyHMargin
                            GeometryField.KEY_V_MARGIN -> it.keyVMargin
                            GeometryField.BOTTOM_INSET -> it.bottomInsetDp
                        }
                    }
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, fontSize = 14.sp)
                        Text(
                            "${"%.1f".format(value)} dp" +
                                if (SkinTreeEditor.geometryOf(tree, field) == null) {
                                    "（跟随全局）"
                                } else {
                                    ""
                                },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = value.coerceIn(range),
                        onValueChange = {
                            onTreeChange(SkinTreeEditor.setGeometry(tree, field, it))
                        },
                        valueRange = range,
                        steps = ((range.endInclusive - range.start) * 2 - 1).toInt()
                            .coerceAtLeast(0),
                    )
                }
            }
        }
        item {
            Text("背景图", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            val spec = backgroundSpec
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (spec == null) {
                    Text("无背景图（纯色背景）", fontSize = 14.sp)
                    OutlinedButton(
                        onClick = { imagePicker.launch(arrayOf("image/*")) },
                    ) { Text("选择背景图") }
                } else {
                    Text("当前背景：${spec.assetId}", fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("模糊半径 ${"%.0f".format(spec.blurRadius)}", fontSize = 13.sp)
                        Text("色罩不透明度 ${"%.2f".format(spec.overlayAlpha)}", fontSize = 13.sp)
                    }
                    Slider(
                        value = spec.blurRadius,
                        onValueChange = {
                            onTreeChange(SkinTreeEditor.setBackgroundParams(tree, blurRadius = it))
                        },
                        valueRange = 0f..25f,
                    )
                    Slider(
                        value = spec.overlayAlpha,
                        onValueChange = {
                            onTreeChange(
                                SkinTreeEditor.setBackgroundParams(tree, overlayAlpha = it),
                            )
                        },
                        valueRange = 0f..1f,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                        ) { Text("更换图片") }
                        OutlinedButton(
                            onClick = { onTreeChange(SkinTreeEditor.setBackground(tree, null)) },
                        ) { Text("移除背景") }
                    }
                }
            }
        }
        item {
            Text("配色", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        for (field in StyleField.entries) {
            item {
                val argb = scheme?.let { field.valueIn(it) } ?: 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { colorDialogField = field }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(argb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(styleFieldLabel(field), fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "#%08X".format(argb),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    val dialogField = colorDialogField
    if (dialogField != null && scheme != null) {
        ColorEditDialog(
            label = styleFieldLabel(dialogField),
            initialArgb = dialogField.valueIn(scheme),
            onDismiss = { colorDialogField = null },
            onConfirm = { argb ->
                onTreeChange(SkinTreeEditor.setColor(tree, dialogField, argb))
                colorDialogField = null
            },
        )
    }
}

/**
 * 布局绑定行（B3 §4.6 皮肤编辑内改绑）：显示当前绑定，选择布局
 * （含解绑），并可直达布局编辑器新建/改绑中的布局。改绑只写草稿
 * 树 layoutId 节点，随草稿应用走提交管线成新代次。
 */
@Composable
private fun LayoutBindingRow(
    tree: JsonObject,
    onTreeChange: (JsonObject) -> Unit,
) {
    val context = LocalContext.current
    var layouts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        layouts = withContext(Dispatchers.IO) {
            val index = (SkinLibrary.store.readIndex()
                as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
            index?.layouts?.map { entry ->
                val name = runCatching {
                    val layoutTree = com.jobeen.ime.skin.model.SkinJson.parseObject(
                        File(
                            SkinLibrary.layout.fileFromRelative(entry.dir),
                            "layout.json",
                        ).readText(),
                    )
                    layoutTree?.let { com.jobeen.ime.skin.layout.LayoutTreeEditor.nameOf(it) }
                }.getOrNull()
                entry.id to (name ?: entry.id)
            } ?: emptyList()
        }
    }
    val boundId = SkinTreeEditor.layoutIdOf(tree)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("自创布局", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            boundId?.let { id ->
                "当前绑定：${layouts.firstOrNull { it.first == id }?.second ?: id}"
            } ?: "未绑定（使用出厂布局）",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { showPicker = true }) { Text("选择布局") }
            if (boundId != null) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            com.jobeen.ime.ui.LayoutEditorActivity
                                .intentForLayout(context, boundId),
                        )
                    },
                ) { Text("编辑布局") }
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        com.jobeen.ime.ui.LayoutEditorActivity.intentForNew(context),
                    )
                },
            ) { Text("新建布局") }
        }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择布局") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = {
                            onTreeChange(SkinTreeEditor.setLayoutId(tree, null))
                            showPicker = false
                        },
                    ) { Text(if (boundId == null) "✓ 不绑定（出厂布局）" else "不绑定（出厂布局）") }
                    for ((id, name) in layouts) {
                        TextButton(
                            onClick = {
                                onTreeChange(SkinTreeEditor.setLayoutId(tree, id))
                                showPicker = false
                            },
                        ) { Text(if (id == boundId) "✓ $name" else name) }
                    }
                    if (layouts.isEmpty()) {
                        Text(
                            "库中暂无自创布局，可先点「新建布局」创建。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        )
    }
}

/** 草稿树 → 生效方案（预览口径：资产不解析，背景单独展示位图）。 */
private fun previewScheme(
    context: android.content.Context,
    tree: JsonObject,
): KeyboardColors.ColorScheme? {
    val validated = when (
        val result = SkinValidator.validate(
            tree = tree,
            currentVersionCode = SkinSnapshotFactory.currentVersionCode(context),
            fallbacks = SkinSnapshotFactory.geometryFallbacks(context),
            assetKind = { null },
        )
    ) {
        is SkinValidator.Result.Valid -> result.skin
        is SkinValidator.Result.Rejected -> return null
    }
    return SkinSnapshotBuilder.build(
        validated = validated,
        ref = SkinGenRef("preview", "", 0),
        defaultScheme = KeyboardTheme.DEFAULT.colors,
        tier = MemoryTier.NORMAL,
        assetSha256 = { null },
        targetWidthPx = 100,
        targetHeightPx = 50,
    ).colorScheme
}

private fun decodeSampled(bytes: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        .getOrNull()
}

@Composable
private fun SchematicPreview(
    scheme: KeyboardColors.ColorScheme?,
    backgroundBitmap: android.graphics.Bitmap?,
) {
    val colors = scheme ?: KeyboardTheme.DEFAULT.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(colors.background)),
    ) {
        if (backgroundBitmap != null) {
            Image(
                bitmap = backgroundBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(colors.panel.candidateBackground)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "候选预览",
                    color = Color(colors.panel.candidateText),
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            val rows = listOf(listOf("Q", "W", "E", "R", "T"), listOf("A", "S", "D", "F", "G"))
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    for (label in row) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(colors.cornerRadius.dp))
                                .background(Color(colors.keyBackground)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = Color(colors.keyText), fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(colors.cornerRadius.dp))
                        .background(Color(colors.specialKeyBackground)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("符", color = Color(colors.specialKeyText), fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(3f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(colors.cornerRadius.dp))
                        .background(Color(colors.keyBackground)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("空格", color = Color(colors.keyText), fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(colors.cornerRadius.dp))
                        .background(Color(colors.accentKeyBackground)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("回车", color = Color(colors.accentKeyText), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ColorEditDialog(
    label: String,
    initialArgb: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var alpha by remember { mutableStateOf((initialArgb ushr 24) and 0xFF) }
    var red by remember { mutableStateOf((initialArgb shr 16) and 0xFF) }
    var green by remember { mutableStateOf((initialArgb shr 8) and 0xFF) }
    var blue by remember { mutableStateOf(initialArgb and 0xFF) }
    var hexText by remember { mutableStateOf("%08X".format(initialArgb)) }

    fun currentArgb(): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(currentArgb())),
                )
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        hexText = raw.filter { it.isLetterOrDigit() }.take(8).uppercase()
                        val parsed = hexText.toLongOrNull(16)
                        if (parsed != null && hexText.length >= 6) {
                            val value = parsed.toInt()
                            if (hexText.length == 8) {
                                alpha = (value ushr 24) and 0xFF
                            }
                            red = (value shr 16) and 0xFF
                            green = (value shr 8) and 0xFF
                            blue = value and 0xFF
                        }
                    },
                    label = { Text("十六进制 AARRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                listOf(
                    "透明度" to (alpha to { v: Int -> alpha = v }),
                    "红" to (red to { v: Int -> red = v }),
                    "绿" to (green to { v: Int -> green = v }),
                    "蓝" to (blue to { v: Int -> blue = v }),
                ).forEach { (name, state) ->
                    val (value, setValue) = state
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = 13.sp, modifier = Modifier.size(width = 44.dp, height = 20.dp))
                        Slider(
                            value = value.toFloat(),
                            onValueChange = {
                                setValue(it.toInt())
                                hexText = "%08X".format(currentArgb())
                            },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$value", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentArgb()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun styleFieldLabel(field: StyleField): String = when (field) {
    StyleField.KEY_BACKGROUND -> "按键底色"
    StyleField.KEY_PRESSED -> "按键按下色"
    StyleField.KEY_BORDER_STROKE -> "按键描边"
    StyleField.SPECIAL_KEY_BACKGROUND -> "特殊键底色"
    StyleField.SPECIAL_KEY_PRESSED -> "特殊键按下色"
    StyleField.SPECIAL_KEY_BORDER_STROKE -> "特殊键描边"
    StyleField.ACCENT_KEY_BACKGROUND -> "强调键底色"
    StyleField.ACCENT_KEY_PRESSED -> "强调键按下色"
    StyleField.ACCENT_KEY_BORDER_STROKE -> "强调键描边"
    StyleField.KEY_TEXT -> "按键文字"
    StyleField.SPECIAL_KEY_TEXT -> "特殊键文字"
    StyleField.ACCENT_KEY_TEXT -> "强调键文字"
    StyleField.ALT_TEXT -> "次要文字"
    StyleField.BACKGROUND -> "键盘背景"
    StyleField.TOAST_BACKGROUND -> "提示条底色"
    StyleField.TOAST_TEXT -> "提示文字"
    StyleField.PANEL_BACKGROUND -> "面板底色"
    StyleField.PANEL_TOOLBAR_TEXT -> "工具栏文字"
    StyleField.PANEL_TOOLBAR_ACTIVED -> "工具栏激活色"
    StyleField.PANEL_TOOLBAR_ICON -> "工具栏图标"
    StyleField.PANEL_TOOLBAR_PRESSED -> "工具栏按下色"
    StyleField.PANEL_CANDIDATE_BACKGROUND -> "候选区底色"
    StyleField.PANEL_CANDIDATE_TEXT -> "候选文字"
    StyleField.PANEL_CANDIDATE_INDEX -> "候选序号"
    StyleField.PANEL_CANDIDATE_DIVIDER -> "候选分隔线"
    StyleField.PINNER_BACKGROUND -> "置顶条底色"
    StyleField.PINNER_TEXT_COLOR -> "置顶文字"
    StyleField.PINNER_SECONDARY_TEXT_COLOR -> "置顶次要文字"
}
