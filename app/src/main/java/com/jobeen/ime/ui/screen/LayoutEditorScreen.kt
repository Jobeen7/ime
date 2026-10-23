package com.jobeen.ime.ui.screen

import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.input.keyboard.impl.GenericLayoutKeyboard
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.draft.DraftStore
import com.jobeen.ime.skin.draft.LayoutDraftCommitter
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.layout.LayoutTreeEditor
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.layout.LayoutKeyDefBuilder
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自创布局编辑器（B3 详设 §4）：上 = 通用键盘类的真实结构预览
 * （点选选键、零执行——空监听 + 选键浮层拦截，与逐键编辑同制）；
 * 下 = 名称、逐行结构操作与问题清单。编辑只落布局草稿（草稿区
 * key 前缀隔离，§4.4），「提交布局」才经提交器成新代次（两段式
 * §4.5）。结构无效时预览停留在最近合法结构、问题清单置顶明示，
 * 绝不空白（铁律 4 的编辑器面）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutEditorScreen(layoutId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draftStore = remember { DraftStore(SkinLibrary.layout) }
    val targetLayoutId = remember { layoutId ?: UUID.randomUUID().toString() }
    val draftKey = remember { DraftStore.layoutDraftKey(targetLayoutId) }

    var draft by remember { mutableStateOf<DraftStore.Draft?>(null) }
    var tree by remember { mutableStateOf<JsonObject?>(null) }
    var recoveryDraft by remember { mutableStateOf<DraftStore.Draft?>(null) }
    var lastValidModel by remember { mutableStateOf<LayoutModel?>(null) }
    var libraryLayoutIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingDeleteRow by remember { mutableStateOf<Int?>(null) }
    var pendingDeleteKey by remember {
        mutableStateOf<Triple<Int, Int, Int>?>(null)
    }
    var showCommitDriftDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun logOp(op: String) {
        DiagLog.i(
            "layout", "layout_editor_op",
            mapOf("layoutId" to targetLayoutId, "op" to op),
        )
    }

    suspend fun createDraft(): DraftStore.Draft {
        val index = (SkinLibrary.store.readIndex()
            as? SkinLibraryStore.ReadResult.Ok)?.value
        libraryLayoutIds = index?.layouts?.map { it.id } ?: emptyList()
        val entry = index?.layouts?.firstOrNull { it.id == targetLayoutId }
        if (entry != null) {
            val genDir = SkinLibrary.layout.fileFromRelative(entry.dir)
            val sourceTree = SkinJson.parseObject(
                File(genDir, "layout.json").readText(),
            )
            if (sourceTree != null) {
                return draftStore.create(
                    draftKey, sourceTree, DraftStore.SOURCE_EXISTING,
                    baseGeneration = entry.generation, baseHash = entry.hash,
                )
            }
        }
        return draftStore.create(
            draftKey,
            LayoutTreeEditor.blankStarter(targetLayoutId, "未命名布局"),
            DraftStore.SOURCE_BLANK, baseGeneration = 0,
        )
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val existing = draftStore.load(draftKey)
            if (existing != null) {
                recoveryDraft = existing
            } else {
                val created = createDraft()
                draft = created
                tree = created.tree
            }
            val index = (SkinLibrary.store.readIndex()
                as? SkinLibraryStore.ReadResult.Ok)?.value
            libraryLayoutIds = index?.layouts?.map { it.id } ?: emptyList()
        }
    }

    // 草稿自动保存（防抖 500ms，与皮肤编辑器同口径）
    LaunchedEffect(tree) {
        val currentTree = tree ?: return@LaunchedEffect
        val currentDraft = draft ?: return@LaunchedEffect
        delay(500)
        withContext(Dispatchers.IO) { draftStore.save(currentDraft.meta.skinId, currentTree) }
    }

    val currentTree = tree
    val validation = remember(currentTree) {
        currentTree?.let { LayoutValidator.validate(it, targetLayoutId) }
    }
    LaunchedEffect(validation) {
        if (validation is LayoutValidation.Valid) {
            lastValidModel = validation.layout
        }
    }
    val previewModel = (validation as? LayoutValidation.Valid)?.layout ?: lastValidModel
    val previewDefs = remember(previewModel) {
        previewModel?.let {
            LayoutKeyDefBuilder.build(
                it, com.jobeen.ime.skin.runtime.PlaceholderValues.current(context),
            )
        }
    }

    fun applyTree(newTree: JsonObject) {
        tree = newTree
    }

    suspend fun countKeyReferrers(logicalId: String): Int = withContext(Dispatchers.IO) {
        val index = (SkinLibrary.store.readIndex()
            as? SkinLibraryStore.ReadResult.Ok)?.value ?: return@withContext 0
        var count = 0
        for (skin in index.skins) {
            val skinTree = runCatching {
                SkinJson.parseObject(
                    File(
                        SkinLibrary.layout.fileFromRelative(skin.dir), "skin.json",
                    ).readText(),
                )
            }.getOrNull() ?: continue
            val mappings = skinTree["keyMappings"] as? JsonObject ?: continue
            if (logicalId in mappings) count++
        }
        count
    }

    fun requestDeleteKey(rowIndex: Int, keyIndex: Int) {
        val t = tree ?: return
        val keys = LayoutTreeEditor.keysOf(LayoutTreeEditor.rowsOf(t)[rowIndex])
        if (keys.size <= 1) {
            toast("行内最后一个键不能单独删除，请删除整行")
            return
        }
        val stableId = (keys[keyIndex]["stableId"] as? JsonPrimitive)?.content ?: return
        val logicalId = LayoutModel.logicalId(targetLayoutId, stableId)
        scope.launch {
            val referrers = countKeyReferrers(logicalId)
            if (referrers == 0) {
                applyTree(LayoutTreeEditor.removeKey(t, rowIndex, keyIndex))
                logOp("delete_key")
            } else {
                pendingDeleteKey = Triple(rowIndex, keyIndex, referrers)
            }
        }
    }

    suspend fun doCommit() {
        val t = tree ?: return
        val committer = LayoutDraftCommitter(
            ImportTransaction(SkinLibrary.layout, SkinLibrary.store),
        )
        when (
            val result = withContext(Dispatchers.IO) {
                committer.commit(targetLayoutId, draftStore.encodeTree(t))
            }
        ) {
            is LayoutDraftCommitter.Result.Committed -> {
                withContext(Dispatchers.IO) { draftStore.delete(draftKey) }
                toast("布局已提交（第 ${result.generation} 代）")
                onClose()
            }
            LayoutDraftCommitter.Result.NoChanges -> toast("内容未变更，无需提交")
            is LayoutDraftCommitter.Result.Failed ->
                toast("提交失败：${result.reason}")
        }
    }

    fun onCommitClicked() {
        val currentDraft = draft ?: return
        scope.launch {
            busy = true
            try {
                if (currentDraft.meta.source == DraftStore.SOURCE_EXISTING) {
                    val index = (SkinLibrary.store.readIndex()
                        as? SkinLibraryStore.ReadResult.Ok)?.value
                    val currentHash = index?.layouts
                        ?.firstOrNull { it.id == targetLayoutId }?.hash
                    if (currentHash != null && currentHash != currentDraft.meta.baseHash) {
                        DiagLog.w(
                            "layout", "layout_draft_drift",
                            mapOf("layoutId" to targetLayoutId, "side" to "layout_editor"),
                        )
                        showCommitDriftDialog = true
                        return@launch
                    }
                }
                doCommit()
            } finally {
                busy = false
            }
        }
    }

    // ---------- 弹窗 ----------

    if (recoveryDraft != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("发现未提交的布局草稿") },
            text = {
                Text(
                    "布局「${LayoutTreeEditor.nameOf(recoveryDraft!!.tree) ?: targetLayoutId}」" +
                        "有未提交的编辑。继续编辑，或丢弃草稿重新开始。",
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
                            val recreated = createDraft()
                            draft = recreated
                            tree = recreated.tree
                        }
                    }
                }) { Text("丢弃草稿") }
            },
        )
    }

    if (showCommitDriftDialog) {
        AlertDialog(
            onDismissRequest = { showCommitDriftDialog = false },
            title = { Text("布局已被别处修改") },
            text = { Text("编辑期间该布局已入库新版本（可能来自同步或导入）。继续提交将以当前草稿为准生成新版本。") },
            confirmButton = {
                TextButton(onClick = {
                    showCommitDriftDialog = false
                    scope.launch {
                        busy = true
                        try {
                            doCommit()
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("仍然提交") }
            },
            dismissButton = {
                TextButton(onClick = { showCommitDriftDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("丢弃布局草稿？") },
            text = { Text("未提交的编辑将全部删除，不留副本。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { draftStore.delete(draftKey) }
                        onClose()
                    }
                }) { Text("丢弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("取消") }
            },
        )
    }

    pendingDeleteRow?.let { rowIndex ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRow = null },
            title = { Text("删除整行？") },
            text = { Text("第 ${rowIndex + 1} 行的全部键将一并删除（草稿内操作，提交前可通过丢弃草稿恢复）。") },
            confirmButton = {
                TextButton(onClick = {
                    tree?.let { applyTree(LayoutTreeEditor.removeRow(it, rowIndex)) }
                    logOp("delete_row")
                    pendingDeleteRow = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRow = null }) { Text("取消") }
            },
        )
    }

    pendingDeleteKey?.let { (rowIndex, keyIndex, referrers) ->
        AlertDialog(
            onDismissRequest = { pendingDeleteKey = null },
            title = { Text("删除此键？") },
            text = {
                Text(
                    "$referrers 个皮肤的逐键覆写引用了此键。删除后这些覆写将悬空" +
                        "（运行时不命中、无效果），皮肤文件本身不会被修改。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    tree?.let {
                        applyTree(LayoutTreeEditor.removeKey(it, rowIndex, keyIndex))
                    }
                    logOp("delete_key")
                    pendingDeleteKey = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteKey = null }) { Text("取消") }
            },
        )
    }

    // ---------- 逐键浮层 ----------

    val editing = editingKey
    if (editing != null && currentTree != null) {
        val (rowIndex, keyIndex) = editing
        val rows = LayoutTreeEditor.rowsOf(currentTree)
        val node = rows.getOrNull(rowIndex)
            ?.let { LayoutTreeEditor.keysOf(it).getOrNull(keyIndex) }
        if (node == null) {
            editingKey = null
        } else {
            val stableId = (node["stableId"] as? JsonPrimitive)?.content.orEmpty()
            val logicalId = LayoutModel.logicalId(targetLayoutId, stableId)
            val nodeLabel = (node["label"] as? JsonPrimitive)?.content
            KeyOverrideDialog(
                layoutName = "",
                keyId = logicalId,
                entry = node,
                admittedSounds = emptySet(),
                onDismiss = { editingKey = null },
                onSaveEntry = { newEntry ->
                    if (newEntry != null) {
                        applyTree(
                            LayoutTreeEditor.setKeyNode(currentTree, rowIndex, keyIndex, newEntry),
                        )
                        logOp("edit_key")
                    }
                    editingKey = null
                },
                isOverridable = { it == logicalId },
                isAnchor = { false },
                baseLabel = nodeLabel,
                validateAssembled = { assembled ->
                    val candidate = LayoutTreeEditor.setKeyNode(
                        currentTree, rowIndex, keyIndex, assembled,
                    )
                    when (val v = LayoutValidator.validate(candidate, targetLayoutId)) {
                        is LayoutValidation.Invalid -> "布局将无效：" +
                            v.issues.take(3).joinToString("；") { "${it.path} ${it.reason}" }
                        is LayoutValidation.Valid -> null
                    }
                },
                switchLayoutTargets = WhitelistAction.LAYOUT_TARGETS +
                    libraryLayoutIds.map { "custom:$it" }.toSet(),
                includeSound = false,
                showReset = false,
                structural = KeyStructuralState(
                    widthWeightText = (node["widthWeight"] as? JsonPrimitive)
                        ?.content ?: "",
                    rowSpan = ((node["rowSpan"] as? JsonPrimitive)?.content?.toIntOrNull())
                        ?: 1,
                    maxRowSpan = rows.size - rowIndex,
                    alignSpanEnd = (node["alignSpanEnd"] as? JsonPrimitive)
                        ?.content?.toBooleanStrictOrNull() ?: false,
                ),
                onDeleteKey = {
                    editingKey = null
                    requestDeleteKey(rowIndex, keyIndex)
                },
                dialogTitle = "编辑键 $logicalId",
            )
        }
    }

    // ---------- 主体 ----------

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("布局编辑") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (currentTree == null || previewDefs == null) {
                Text("加载中…")
            } else {
            val rows = LayoutTreeEditor.rowsOf(currentTree)
            val totalKeys = LayoutTreeEditor.keyCount(currentTree)

            Text(
                "点按预览中的键编辑该键（标签/动作/宽度/跨行）；预览只选键、不执行任何动作。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val scheme = KeyboardTheme.DEFAULT.colors
            key(previewDefs) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    factory = { ctx ->
                        val keyboard = GenericLayoutKeyboard(
                            ctx, scheme,
                            com.jobeen.ime.skin.runtime.CustomLayoutRuntime
                                .nameFor(targetLayoutId),
                            previewDefs,
                        )
                        keyboard.keyActionListener = KeyActionListener.Empty
                        val container = FrameLayout(ctx)
                        container.addView(
                            keyboard,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        val picker = KeyPickingView(ctx, keyboard, previewDefs) { pickedId ->
                            val parsed = pickedId?.let { LayoutModel.parseLogicalId(it) }
                            if (parsed == null) {
                                toast("该键不可编辑")
                            } else {
                                val model = previewModel
                                val position = model?.rows?.withIndex()
                                    ?.firstNotNullOfOrNull { (r, rowKeys) ->
                                        val k = rowKeys.indexOfFirst {
                                            it.stableId == parsed.second
                                        }
                                        if (k >= 0) r to k else null
                                    }
                                if (position != null) {
                                    editingKey = position
                                }
                            }
                        }
                        container.addView(
                            picker,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        container
                    },
                )
            }

            // 问题清单（无效置顶红字、逐字段回退项警示）与键宽估算警告
            when (val v = validation) {
                is LayoutValidation.Invalid -> {
                    Text(
                        "结构无效：预览停留在最近合法结构，修正后才能提交。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    for (issue in v.issues.take(6)) {
                        Text(
                            "· ${issue.path} — ${issue.reason}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is LayoutValidation.Valid -> {
                    for (issue in v.issues.take(6)) {
                        Text(
                            "· ${issue.path} — ${issue.reason}（已回退/丢弃）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                null -> Unit
            }
            val availWidthDp = LocalConfiguration.current.screenWidthDp - 32
            val narrowCount = previewDefs.flatten().count { def ->
                availWidthDp * def.appearance.percentWidth < 40f
            }
            if (narrowCount > 0) {
                Text(
                    "$narrowCount 个键的预估键宽小于 40dp（按当前屏宽估算），点按目标可能过小。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = LayoutTreeEditor.nameOf(currentTree) ?: "",
                onValueChange = { applyTree(LayoutTreeEditor.setName(currentTree, it)) },
                label = { Text("布局名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "布局 id：$targetLayoutId · ${rows.size} 行 · $totalKeys 键" +
                    "（上限 8 行 / 每行 12 键 / 共 64 键）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 逐行结构：行头（加键/删行）+ 键片（点开浮层、‹ › 行内移动）
            rows.forEachIndexed { rowIndex, row ->
                val keys = LayoutTreeEditor.keysOf(row)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "第 ${rowIndex + 1} 行（${keys.size} 键）",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val newId = LayoutTreeEditor.nextStableId(currentTree)
                            val grown = LayoutTreeEditor.addKey(
                                currentTree, rowIndex,
                                LayoutTreeEditor.letterKey(newId, "a"),
                            )
                            applyTree(grown)
                            logOp("add_key")
                            editingKey = rowIndex to keys.size
                        },
                        enabled = totalKeys < LayoutValidator.MAX_TOTAL_KEYS &&
                            keys.size < LayoutValidator.MAX_KEYS_PER_ROW,
                    ) { Text("加键") }
                    TextButton(
                        onClick = {
                            if (keys.isEmpty()) {
                                applyTree(LayoutTreeEditor.removeRow(currentTree, rowIndex))
                                logOp("delete_row")
                            } else {
                                pendingDeleteRow = rowIndex
                            }
                        },
                    ) { Text("删行") }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    keys.forEachIndexed { keyIndex, keyNode ->
                        val label = (keyNode["label"] as? JsonPrimitive)?.content
                            ?: (keyNode["stableId"] as? JsonPrimitive)?.content ?: "?"
                        OutlinedButton(
                            onClick = { editingKey = rowIndex to keyIndex },
                        ) { Text(label.take(6)) }
                        if (keys.size > 1) {
                            TextButton(
                                onClick = {
                                    applyTree(
                                        LayoutTreeEditor.moveKey(
                                            currentTree,
                                            rowIndex, keyIndex, rowIndex, keyIndex - 1,
                                        ),
                                    )
                                },
                                enabled = keyIndex > 0,
                            ) { Text("‹") }
                            TextButton(
                                onClick = {
                                    applyTree(
                                        LayoutTreeEditor.moveKey(
                                            currentTree,
                                            rowIndex, keyIndex, rowIndex, keyIndex + 1,
                                        ),
                                    )
                                },
                                enabled = keyIndex < keys.lastIndex,
                            ) { Text("›") }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    applyTree(LayoutTreeEditor.addRow(currentTree))
                    logOp("add_row")
                },
                enabled = rows.size < LayoutValidator.MAX_ROWS,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加行") }

            Spacer(Modifier.size(4.dp))
            Button(
                onClick = { onCommitClicked() },
                enabled = !busy && validation is LayoutValidation.Valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("提交布局（生成新代次）") }
            OutlinedButton(
                onClick = { showDiscardDialog = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("丢弃草稿") }
            Spacer(Modifier.size(16.dp))
            }
        }
    }
}
