// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.skin.layout.LayoutEditorModel
import com.jobeen.ime.skin.library.ActiveSkinFiles
import com.jobeen.ime.skin.library.EditorFile
import com.jobeen.ime.skin.library.ResolveActiveResult
import com.jobeen.ime.skin.library.SKIN_GEOMETRY_FIELDS
import com.jobeen.ime.skin.library.SkinAppearanceDraft
import com.jobeen.ime.skin.library.SkinEditorReasons
import com.jobeen.ime.skin.library.SkinEditorSaveResult
import com.jobeen.ime.skin.library.SkinEditorSaver
import com.jobeen.ime.skin.library.keyMappingId
import com.jobeen.ime.skin.library.readGeometryValue
import com.jobeen.ime.skin.library.readStyleColor
import com.jobeen.ime.skin.model.SkinJson
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 可视化皮肤编辑器（2026-11-16；参照：松烟为主、中州鹅为次，见报告对照表）：
 *
 * - 顶栏：9键／26键切换＋撤销／重做＋应用；预览区点键选中（image-68
 *   交互形态）；预设／配色／几何三标签（无音效——v3 无音效字段，
 *   Jime 音效在设置页另行管理）；深色预览切换。
 * - 逐键编辑（image-68 底部条）：标签／宽度／点按／长按／上滑／下滑
 *   走共享 KeyEditor（布局文件）；组字中点按／上滑／下滑与逐键颜色
 *   （文字／背景／阴影色＝styleOverrides text/face/lowerEdge）写
 *   keyMappings；类型与目标即点按动作的类型选择器（命令 switch_layout
 *   的值就是目标布局，与中州鹅“目标”字段同义）；分体键盘位置无
 *   Jime 对应字段，不做（不发明新机制）。
 * - 预览是 Compose 结构预览（布局行／权重／标签＋草稿配色板＋草稿
 *   几何），不做真机键盘渲染；逐键变体着色不猜——变体只在快照构建期
 *   由样式名解析，预览统一用按键底色，强调／特殊键底色另在色板条里
 *   实时可见。取消即丢弃草稿（未写盘、未应用）；应用走
 *   SkinEditorSaver（校验→copy-on-write 新代→应用事务，旧代即回滚）。
 *
 * 入口：皮肤库卡片 ⋮ 菜单“编辑外观”（仅生效皮肤；内置主题先走既有
 * “复制当前主题为新皮肤”再编辑）。skinId 为 null 时编当前生效皮肤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualSkinEditorScreen(
    skinId: String?,
    onBack: () -> Unit,
    onOpenJsonEditor: (filePath: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val saver = remember(context) { SkinEditorSaver(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    // 加载错误只存串 id＋格式参数（SkinLibraryScreen 同款口径）：
    // 回调里调 context.getString 会被 lint LocalContextGetResourceValueCall
    // 拦，故组合期用 stringResource 解析（见下方 loadErrorText）。
    var loadErrorResId by remember { mutableStateOf<Int?>(null) }
    var loadErrorArg by remember { mutableStateOf<String?>(null) }
    val loadErrorText = loadErrorResId?.let { resId ->
        val format = stringResource(resId)
        loadErrorArg?.let { String.format(format, it) } ?: format
    }
    var active by remember { mutableStateOf<ActiveSkinFiles?>(null) }
    var skinFile by remember { mutableStateOf<EditorFile?>(null) }
    var layoutFiles by remember { mutableStateOf<Map<String, EditorFile>>(emptyMap()) }
    var pinyinLayoutId by remember { mutableStateOf<String?>(null) }
    var alphabeticLayoutId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<SkinAppearanceDraft?>(null) }
    var draftVersion by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SkinEditorSaveResult?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var nineKey by remember { mutableStateOf(true) }
    var darkPreview by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(VisualTab.PRESETS) }

    fun setLoadError(resId: Int, arg: String? = null) {
        loadErrorResId = resId
        loadErrorArg = arg
    }

    suspend fun load() {
        loading = true
        loadErrorResId = null
        loadErrorArg = null
        result = null
        draft = null
        selectedId = null
        val resolved = saver.resolveActiveFiles()
        when (resolved) {
            is ResolveActiveResult.Unavailable -> {
                loading = false
                setLoadError(SkinEditorReasons.reasonResId(resolved.reason))
            }
            is ResolveActiveResult.Ok -> {
                val files = resolved.files
                if (skinId != null && skinId != files.skinId) {
                    loading = false
                    setLoadError(R.string.visual_editor_skin_not_active)
                    return
                }
                val skin = files.files.firstOrNull { it.kind == EditorFile.Kind.SKIN_JSON }
                val skinText = skin?.let { saver.loadText(it.file) }
                val skinTree = skinText?.let { SkinJson.parseObject(it) }
                if (skin == null || skinTree == null) {
                    loading = false
                    setLoadError(
                        R.string.skin_editor_load_failed,
                        skin?.displayName ?: files.skinId,
                    )
                    return
                }
                val keyboards = skinTree["keyboards"] as? JsonObject
                val pinyinId = ((keyboards?.get("pinyin") as? JsonObject)
                    ?.get("layoutId") as? JsonPrimitive)?.contentOrNull
                val alphaId = ((keyboards?.get("alphabetic") as? JsonObject)
                    ?.get("layoutId") as? JsonPrimitive)?.contentOrNull
                val trees = mutableMapOf<String, JsonObject>()
                val layoutFileById = mutableMapOf<String, EditorFile>()
                for (id in listOfNotNull(pinyinId, alphaId)) {
                    val file = files.files.firstOrNull {
                        it.id == id && it.kind == EditorFile.Kind.LAYOUT
                    }
                    val text = file?.let { saver.loadText(it.file) }
                    val tree = text?.let { SkinJson.parseObject(it) }
                    if (tree != null) {
                        trees[id] = tree
                        if (file != null) layoutFileById[id] = file
                    }
                }
                active = files
                skinFile = skin
                layoutFiles = layoutFileById
                pinyinLayoutId = pinyinId
                alphabeticLayoutId = alphaId
                draft = SkinAppearanceDraft(files.skinId, skinTree, trees)
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    fun touchDraft(block: SkinAppearanceDraft.() -> Unit) {
        val d = draft ?: return
        d.block()
        draftVersion++
        result = null
    }

    fun requestBack() {
        if (draft?.dirty == true) confirmLeave = true else onBack()
    }

    fun apply() {
        val d = draft ?: return
        val current = active ?: return
        busy = true
        result = null
        scope.launch {
            var saved: SkinEditorSaveResult
            var ok: Boolean
            // skin.json 只有改过才写（不变不建新代）；布局改过才写。
            var fresh = current
            if (d.skin != d.originalSkin) {
                saved = saver.saveSkinJson(current, d.toSkinJsonText())
                ok = saved is SkinEditorSaveResult.Saved
                // 布局保存须用新代次的活动记录：重解析一次。
                if (ok) {
                    when (val re = saver.resolveActiveFiles()) {
                        is ResolveActiveResult.Ok -> fresh = re.files
                        is ResolveActiveResult.Unavailable -> {
                            saved = SkinEditorSaveResult.Failed(re.reason)
                            ok = false
                        }
                    }
                }
            } else {
                saved = SkinEditorSaveResult.Saved(emptyList())
                ok = true
            }
            if (ok) {
                for (layoutId in d.dirtyLayoutIds) {
                    val file = layoutFiles[layoutId]
                    val text = d.layoutText(layoutId)
                    if (file == null || text == null) continue
                    val r = saver.saveLayout(fresh, file, text)
                    if (r !is SkinEditorSaveResult.Saved) {
                        saved = r
                        ok = false
                        break
                    }
                }
            }
            busy = false
            result = saved
            if (ok) load()
            else if (saved is SkinEditorSaveResult.Stale) load()
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.skin_editor_unsaved_title)) },
            text = { Text(stringResource(R.string.skin_editor_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) {
                    Text(stringResource(R.string.skin_editor_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.skin_editor_keep_editing))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        draft?.let { nameOf(it.skin) } ?: stringResource(R.string.skin_editor_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // 9键／26键切换（image-67 顶栏）。
                    Row(
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                            .clip(MaterialTheme.shapes.large),
                    ) {
                        for ((isNine, label) in listOf(
                            true to stringResource(R.string.visual_editor_9key),
                            false to stringResource(R.string.visual_editor_26key),
                        )) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (nineKey == isNine) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent,
                                    )
                                    .clickable {
                                        if (nineKey != isNine) {
                                            nineKey = isNine
                                            selectedId = null
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                    TextButton(
                        onClick = { draft?.undo()?.let { draftVersion++ } },
                        enabled = draft?.canUndo == true && !busy,
                    ) { Text(stringResource(R.string.action_undo)) }
                    TextButton(
                        onClick = { draft?.redo()?.let { draftVersion++ } },
                        enabled = draft?.canRedo == true && !busy,
                    ) { Text(stringResource(R.string.action_redo)) }
                    TextButton(
                        onClick = ::apply,
                        enabled = draft?.dirty == true && !busy,
                    ) { Text(stringResource(R.string.global_apply)) }
                },
            )
        },
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.leveldb_loading)) }
            }
            loadErrorText != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(loadErrorText, textAlign = TextAlign.Center) }
            }
            draft != null -> {
                key(draftVersion) {
                    VisualEditorBody(
                        draft = draft!!,
                        skinFile = skinFile,
                        nineKey = nineKey,
                        darkPreview = darkPreview,
                        onDarkPreviewChange = { darkPreview = it },
                        selectedId = selectedId,
                        onSelectKey = { selectedId = it },
                        tab = tab,
                        onTabChange = { tab = it },
                        busy = busy,
                        result = result,
                        onTouchDraft = ::touchDraft,
                        onOpenJsonEditor = onOpenJsonEditor,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }
}

private enum class VisualTab { PRESETS, COLORS, GEOMETRY }

/** skin.json meta.name（无则用 skin id）。 */
private fun nameOf(skinTree: JsonObject): String {
    val meta = skinTree["meta"] as? JsonObject
    val name = (meta?.get("name") as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
    val id = (meta?.get("id") as? JsonPrimitive)?.contentOrNull
    return name ?: id ?: ""
}

/** #AARRGGBB / #RRGGBB → Color（非法时回落 fallback）。 */
private fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(fallback)
}

@Composable
private fun VisualEditorBody(
    draft: SkinAppearanceDraft,
    skinFile: EditorFile?,
    nineKey: Boolean,
    darkPreview: Boolean,
    onDarkPreviewChange: (Boolean) -> Unit,
    selectedId: String?,
    onSelectKey: (String?) -> Unit,
    tab: VisualTab,
    onTabChange: (VisualTab) -> Unit,
    busy: Boolean,
    result: SkinEditorSaveResult?,
    onTouchDraft: (SkinAppearanceDraft.() -> Unit) -> Unit,
    onOpenJsonEditor: (filePath: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = if (darkPreview) "stylesDark" else "styles"
    // 当前预览布局 id：九宫格取 keyboards.pinyin.layoutId，26 键取 alphabetic。
    val keyboards = draft.skin["keyboards"] as? JsonObject
    fun layoutIdFor(nine: Boolean): String? {
        val section = keyboards?.get(if (nine) "pinyin" else "alphabetic") as? JsonObject
        return (section?.get("layoutId") as? JsonPrimitive)?.contentOrNull
    }
    val layoutId = layoutIdFor(nineKey)
    val layoutTree = layoutId?.let { draft.layouts[it] }
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 预览区（点键选中；深色预览切换） ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(
                            if (nineKey) R.string.visual_editor_9key else R.string.visual_editor_26key,
                        ),
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.visual_editor_dark_preview), fontSize = 13.sp)
                    Switch(
                        checked = darkPreview,
                        onCheckedChange = onDarkPreviewChange,
                    )
                }
                if (layoutTree == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.visual_editor_no_layout))
                    }
                } else {
                    SkinKeyboardPreview(
                        draft = draft,
                        tree = layoutTree,
                        board = board,
                        selectedId = selectedId,
                        onSelectKey = onSelectKey,
                    )
                    // 色板条：强调／特殊键底色实时可见（预览键统一用按键底色，不猜变体）。
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BoardSwatch(
                            label = stringResource(R.string.visual_editor_key_face),
                            hex = readStyleColor(draft.skin, board, "keyBackground"),
                        )
                        BoardSwatch(
                            label = stringResource(R.string.visual_editor_key_special_face),
                            hex = readStyleColor(draft.skin, board, "specialKeyBackground"),
                        )
                        BoardSwatch(
                            label = stringResource(R.string.visual_editor_key_accent_face),
                            hex = readStyleColor(draft.skin, board, "accentKeyBackground"),
                        )
                    }
                }
            }
        }

        // ---- 选中键编辑条（image-68 形态） ----
        if (layoutTree != null && selectedId != null) {
            val key = LayoutEditorModel.keyByStableId(layoutTree, selectedId)
            if (key != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.visual_editor_edit_key, selectedId),
                                fontSize = 15.sp,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    val id = layoutId ?: return@TextButton
                                    onTouchDraft { resetKey(id, selectedId) }
                                },
                            ) { Text(stringResource(R.string.visual_editor_reset_key)) }
                        }
                        val lid = layoutId!!
                        KeyEditor(
                            tree = layoutTree,
                            stableId = selectedId,
                            onTreeChange = { newTree ->
                                onTouchDraft { setLayout(lid, newTree) }
                            },
                        )
                        // 组字中行为（keyMappings preedit*；中州鹅“合成中按键行为”）。
                        val logicalId = keyMappingId(lid, selectedId)
                        val entry = draft.keyMappingEntry(logicalId)
                        Text(stringResource(R.string.visual_editor_compose_actions))
                        ActionEditor(
                            label = stringResource(R.string.visual_editor_compose_tap),
                            action = entry?.get("preeditTap") as? JsonObject,
                            onChange = { action ->
                                onTouchDraft { setKeyMappingField(logicalId, "preeditTap", action) }
                            },
                        )
                        ActionEditor(
                            label = stringResource(R.string.visual_editor_compose_swipe_up),
                            action = entry?.get("preeditSwipeUp") as? JsonObject,
                            onChange = { action ->
                                onTouchDraft {
                                    setKeyMappingField(logicalId, "preeditSwipeUp", action)
                                }
                            },
                        )
                        ActionEditor(
                            label = stringResource(R.string.visual_editor_compose_swipe_down),
                            action = entry?.get("preeditSwipeDown") as? JsonObject,
                            onChange = { action ->
                                onTouchDraft {
                                    setKeyMappingField(logicalId, "preeditSwipeDown", action)
                                }
                            },
                        )
                        // 逐键颜色（styleOverrides text/face/lowerEdge；阴影色取下边缘）。
                        Text(stringResource(R.string.visual_editor_key_colors))
                        val overrides = entry?.get("styleOverrides") as? JsonObject
                        fun setOverride(role: String, hex: String?) {
                            onTouchDraft {
                                val current = (keyMappingEntry(logicalId)
                                    ?.get("styleOverrides") as? JsonObject)
                                    ?: JsonObject(emptyMap())
                                val updated = JsonObject(
                                    current.toMutableMap().also { map ->
                                        if (hex == null) map.remove(role) else map[role] = JsonPrimitive(hex)
                                    },
                                )
                                setKeyMappingField(
                                    logicalId, "styleOverrides",
                                    if (updated.isEmpty()) null else updated,
                                )
                            }
                        }
                        HexColorField(
                            label = stringResource(R.string.visual_editor_key_text_color),
                            value = (overrides?.get("text") as? JsonPrimitive)?.contentOrNull,
                            onValueChange = { setOverride("text", it) },
                        )
                        HexColorField(
                            label = stringResource(R.string.visual_editor_key_face_color),
                            value = (overrides?.get("face") as? JsonPrimitive)?.contentOrNull,
                            onValueChange = { setOverride("face", it) },
                        )
                        HexColorField(
                            label = stringResource(R.string.visual_editor_key_shadow_color),
                            value = (overrides?.get("lowerEdge") as? JsonPrimitive)?.contentOrNull,
                            onValueChange = { setOverride("lowerEdge", it) },
                        )
                    }
                }
            }
        } else if (layoutTree != null) {
            Text(stringResource(R.string.layout_editor_no_key_selected), fontSize = 13.sp)
        }

        // ---- 三标签：预设／配色／几何（无音效） ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, labelRes) in listOf(
                VisualTab.PRESETS to R.string.visual_editor_presets,
                VisualTab.COLORS to R.string.visual_editor_colors,
                VisualTab.GEOMETRY to R.string.visual_editor_geometry,
            )) {
                OutlinedButton(
                    onClick = { onTabChange(value) },
                    enabled = tab != value,
                ) { Text(stringResource(labelRes)) }
            }
        }
        when (tab) {
            VisualTab.PRESETS -> {
                for (theme in KeyboardThemePresets.ALL) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        onClick = {
                            onTouchDraft { applyPreset(theme.colors, darkPreview) }
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(28.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        Color(theme.colors.keyBackground).copy(alpha = 1f),
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                            )
                            Box(
                                modifier = Modifier.size(28.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        Color(theme.colors.accentKeyBackground).copy(alpha = 1f),
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                            )
                            Text(theme.name, fontSize = 15.sp)
                        }
                    }
                }
            }
            VisualTab.COLORS -> {
                for ((group, fields) in COLOR_FIELD_GROUPS) {
                    Text(group, fontSize = 14.sp)
                    for ((path, label) in fields) {
                        HexColorField(
                            label = label,
                            value = readStyleColor(draft.skin, board, path),
                            onValueChange = { hex ->
                                onTouchDraft { setStyleColor(board, path, hex) }
                            },
                        )
                    }
                }
            }
            VisualTab.GEOMETRY -> {
                for (field in SKIN_GEOMETRY_FIELDS) {
                    val current = readGeometryValue(draft.skin, field)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(field.label, modifier = Modifier.width(150.dp), fontSize = 13.sp)
                        Slider(
                            value = (current ?: field.min).coerceIn(field.min, field.max),
                            onValueChange = { v ->
                                val rounded = (v * 2).toInt() / 2f
                                onTouchDraft { setGeometry(field, rounded) }
                            },
                            valueRange = field.min..field.max,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(current ?: field.min).let { if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString() }}${field.unit}",
                            fontSize = 13.sp,
                            modifier = Modifier.width(64.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }

        // ---- 底部：高级入口＋保存结果 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val file = skinFile
            if (file != null) {
                TextButton(
                    onClick = { onOpenJsonEditor(file.file.absolutePath) },
                    enabled = !busy,
                ) { Text(stringResource(R.string.visual_editor_advanced_json)) }
            }
            when (val r = result) {
                is SkinEditorSaveResult.Saved -> {
                    Text(
                        stringResource(R.string.visual_editor_applied),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                }
                is SkinEditorSaveResult.Blocked -> {
                    Text(r.reason, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                is SkinEditorSaveResult.Failed -> {
                    Text(r.reason, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                is SkinEditorSaveResult.Stale -> {
                    Text(r.reason, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                null -> Unit
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** 配色字段分组（与网页编辑器 COLOR_FIELDS 同口径：组／路径／中文标签）。 */
private val COLOR_FIELD_GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "按键" to listOf(
        "keyBackground" to "按键底色",
        "keyPressed" to "按键按压底色",
        "keyBorderStroke" to "按键边框",
    ),
    "特殊键" to listOf(
        "specialKeyBackground" to "特殊键底色",
        "specialKeyPressed" to "特殊键按压底色",
        "specialKeyBorderStroke" to "特殊键边框",
    ),
    "强调键" to listOf(
        "accentKeyBackground" to "强调键底色（回车）",
        "accentKeyPressed" to "强调键按压底色",
        "accentKeyBorderStroke" to "强调键边框",
    ),
    "文字" to listOf(
        "keyText" to "按键文字",
        "specialKeyText" to "特殊键文字",
        "accentKeyText" to "强调键文字",
        "altText" to "角标/副文字",
    ),
    "背景与提示" to listOf(
        "background" to "键盘背景",
        "toastBackground" to "提示条背景",
        "toastText" to "提示条文字",
    ),
    "候选栏" to listOf(
        "panel.background" to "候选栏背景",
        "panel.candidateBackground" to "候选底色",
        "panel.candidateText" to "候选文字",
        "panel.selectedCandidateText" to "选中候选文字",
        "panel.candidateIndex" to "候选序号",
        "panel.candidateDivider" to "候选分隔线",
        "panel.candidateComment" to "候选注释",
        "panel.candidatePressed" to "候选按压底色",
        "panel.toolbarText" to "工具栏文字",
        "panel.toolbarIcon" to "工具栏图标",
        "panel.toolbarActived" to "工具栏激活",
        "panel.toolbarPressed" to "工具栏按压",
        "panel.verticalSeparator" to "竖网格分隔线",
    ),
    "首选候选" to listOf(
        "panel.preferredBackground" to "首选候选底色",
        "panel.preferredText" to "首选候选文字",
        "panel.preferredComment" to "首选候选注释",
        "panel.preferredIndex" to "首选候选序号",
    ),
    "固定栏" to listOf(
        "pinner.background" to "固定栏背景",
        "pinner.textColor" to "固定栏文字",
        "pinner.secondaryTextColor" to "固定栏副文字",
    ),
)

/** 色板条一格：标签＋色块（无值时画空框）。 */
@Composable
private fun BoardSwatch(label: String, hex: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp)
                .clip(MaterialTheme.shapes.small)
                .background(parseHexColor(hex, Color.Transparent))
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
        )
        Text(label, fontSize = 12.sp)
    }
}

/** 颜色字段：色块＋十六进制文本框（非法不提交，红字提示格式）。 */
@Composable
private fun HexColorField(
    label: String,
    value: String?,
    onValueChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value ?: "") }
    var invalid by remember { mutableStateOf(false) }
    val hexRegex = remember { Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(28.dp)
                .clip(MaterialTheme.shapes.small)
                .background(parseHexColor(text.takeIf { hexRegex.matches(it) }, Color.Transparent))
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
        )
        OutlinedTextField(
            shape = MaterialTheme.shapes.medium,
            value = text,
            onValueChange = { input ->
                text = input
                if (hexRegex.matches(input)) {
                    invalid = false
                    onValueChange(input.uppercase())
                } else {
                    invalid = input.isNotEmpty()
                }
            },
            label = { Text(label) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.visual_editor_hex_invalid)) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 键盘结构预览（复用 LayoutEditorScreen 的预览形态＋草稿配色／几何）：
 * 行／权重／标签取布局树；键面取当前板 keyBackground、文字取 keyText、
 * 背景取 background；圆角与间距取草稿几何（geometry.* 优先于
 * appearance.*）。跨行键只画首行（与 SkinMiniPreview 同一简化）。
 */
@Composable
private fun SkinKeyboardPreview(
    draft: SkinAppearanceDraft,
    tree: JsonObject,
    board: String,
    selectedId: String?,
    onSelectKey: (String?) -> Unit,
) {
    val bg = parseHexColor(
        readStyleColor(draft.skin, board, "background"),
        MaterialTheme.colorScheme.surface,
    )
    val keyBg = parseHexColor(
        readStyleColor(draft.skin, board, "keyBackground"),
        MaterialTheme.colorScheme.surfaceVariant,
    )
    val keyText = parseHexColor(
        readStyleColor(draft.skin, board, "keyText"),
        MaterialTheme.colorScheme.onSurface,
    )
    val radius = (
        readGeometryValue(draft.skin, SKIN_GEOMETRY_FIELDS.first { it.path == "geometry.keyRadiusDp" })
            ?: readGeometryValue(draft.skin, SKIN_GEOMETRY_FIELDS.first { it.path == "appearance.cornerRadius" })
            ?: 10f
        ).dp
    val hGap = (
        readGeometryValue(draft.skin, SKIN_GEOMETRY_FIELDS.first { it.path == "geometry.keyHGapDp" })
            ?: readGeometryValue(draft.skin, SKIN_GEOMETRY_FIELDS.first { it.path == "appearance.keyHMargin" })
            ?: 6f
        ).dp
    Column(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(radius)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in LayoutEditorModel.rows(tree)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(hGap),
            ) {
                for (key in row) {
                    val stableId =
                        (key["stableId"] as? JsonPrimitive)?.contentOrNull ?: ""
                    val label =
                        (key["label"] as? JsonPrimitive)?.contentOrNull ?: ""
                    val weight =
                        (key["widthWeight"] as? JsonPrimitive)?.content
                            ?.toDoubleOrNull() ?: 1.0
                    val isSelected = stableId == selectedId
                    Box(
                        modifier = Modifier
                            .weight(weight.toFloat().coerceAtLeast(0.05f))
                            .height(52.dp)
                            .clip(RoundedCornerShape(radius))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer else keyBg,
                            )
                            .border(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(radius),
                            )
                            .clickable { onSelectKey(stableId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label.ifEmpty { " " },
                            color = keyText,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
