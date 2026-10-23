package com.jobeen.ime.ui.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.input.keyboard.impl.BaseKeyboard
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.skin.draft.SkinTreeEditor
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeySlot
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinKeyMappingsParser
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.PlaceholderValues
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 逐键编辑区（定稿十四章 / 详设 §6）：真实键盘预览 + 点选选键 +
 * 浮层编辑。预览零执行三重保障——
 * 1. 选键浮层视图拦截全部触摸（键盘视图收不到任何手势，
 *    长按候选条/语音/点击都不可能触发，P-2/P-4/P-5）；
 * 2. 键盘挂 [KeyActionListener.Empty] 空监听（P-3）；
 * 3. Qwerty 预览实例带 previewMode，唯一就地消费动作 Caps 在
 *    onAction 入口吞掉（P-1；T9/T15/Number 无 onAction 就地
 *    消费面，施工核查结论见详设 §10.2-2 回填）。
 * 预览不经 KeyboardWindowView 工厂、不碰全局快照槽：显式喂
 * 草稿树构建的快照与装配后布局（详设 §6.1/§10.2-7）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinKeyEditorSection(
    tree: JsonObject,
    onTreeChange: (JsonObject) -> Unit,
    draftId: String?,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var layoutName by remember { mutableStateOf(QwertyKeyboard.NAME) }
    var editingKeyId by remember { mutableStateOf<String?>(null) }

    // 源代次清单（已接纳音效列表与预览接纳判定的数据来源）
    var manifest by remember(draftId) { mutableStateOf(PackageManifest()) }
    LaunchedEffect(draftId) {
        manifest = withContext(Dispatchers.IO) { loadSourceManifest(draftId) }
    }
    // 草稿音效索引与事实（编辑器导入的音效：与清单资产同口径接纳）
    var draftSoundIndex by remember(draftId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(draftId) {
        draftSoundIndex = withContext(Dispatchers.IO) {
            if (draftId == null) {
                emptyMap()
            } else {
                com.jobeen.ime.skin.draft.DraftStore(SkinLibrary.layout)
                    .readDraftSoundIndex(draftId)
            }
        }
    }
    val draftFacts = remember(draftId, draftSoundIndex) {
        buildDraftAudioFacts(draftId, draftSoundIndex)
    }
    val snapshot = remember(tree, manifest, draftFacts) {
        editorSnapshot(context, tree, manifest, draftFacts)
    }
    // 可接纳音效集：对全部候选资产（清单 + 草稿导入）按同一接纳
    // 公式试算（选全局 tap / 逐键音效的候选面；未被引用时它们
    // 尚未进快照接纳表）
    val admissibleSounds = remember(manifest, draftFacts) {
        val manifestFacts =
            SkinSnapshotFactory.manifestAudioFacts(SkinLibrary.layout, manifest)
        val orderedIds = manifest.assets().map { it.id } +
            draftFacts.keys.filter { id -> manifest.assets().none { it.id == id } }
        com.jobeen.ime.skin.model.SkinSoundAdmission.admit(
            referencedInOrder = orderedIds,
            defaultTapAssetId = null,
            facts = { id -> draftFacts[id] ?: manifestFacts(id) },
            audioBudgetBytes = 32L * 1024 * 1024,
            skipAll = false,
        ).admitted.keys
    }

    val soundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || draftId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                importDraftSound(context, draftId, uri, draftSoundIndex)
            }
            if (imported == null) {
                Toast.makeText(context, "导入音效失败", Toast.LENGTH_SHORT).show()
            } else {
                draftSoundIndex = imported.second
                Toast.makeText(
                    context, "已导入音效 ${imported.first}，可在全局/逐键音效中选用",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val scheme = snapshot?.colorScheme ?: KeyboardTheme.DEFAULT.colors
    val effective = remember(snapshot, layoutName) {
        val raw = rawLayoutOf(layoutName)
        if (snapshot == null) {
            raw
        } else {
            KeyOverrideApplier.apply(
                layoutName, raw, snapshot, PlaceholderValues.current(context),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "逐键编辑（真实预览）",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in EDITABLE_LAYOUTS) {
                FilterChip(
                    selected = layoutName == name,
                    onClick = { layoutName = name },
                    label = { Text(name) },
                )
            }
        }
        Text(
            "点按预览中的键编辑该键；预览只选键、不执行任何动作。" +
                "符号/表情面板仅登记功能锚点，不支持逐键编辑。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        key(layoutName, effective, scheme) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(248.dp),
                factory = { ctx ->
                    val keyboard: BaseKeyboard = when (layoutName) {
                        QwertyKeyboard.NAME -> QwertyKeyboard(
                            ctx, scheme, effective, previewMode = true,
                        )
                        T9Keyboard.NAME -> T9Keyboard(ctx, scheme, effective)
                        T15Keyboard.NAME -> T15Keyboard(ctx, scheme, effective)
                        else -> NumberKeyboard(ctx, scheme, effective)
                    }
                    keyboard.keyActionListener = KeyActionListener.Empty
                    val container = FrameLayout(ctx)
                    container.addView(
                        keyboard,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    val picker = KeyPickingView(ctx, keyboard, effective) { keyId ->
                        if (keyId == null) {
                            Toast.makeText(
                                ctx, "侧栏/未登记键不可逐键编辑",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            editingKeyId = keyId
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("全局点按音", fontSize = 14.sp, modifier = Modifier.weight(1f))
            LabeledDropdown(
                label = "",
                options = listOf("" to "出厂 pop") +
                    admissibleSounds.sorted().map { it to it },
                selectedKey = SkinTreeEditor.audioTapOf(tree) ?: "",
                onSelect = { picked ->
                    onTreeChange(
                        SkinTreeEditor.setAudioTap(tree, picked.ifEmpty { null }),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { soundPicker.launch(arrayOf("audio/*")) },
                enabled = draftId != null,
            ) { Text("导入音效到草稿") }
            Spacer(Modifier.size(8.dp))
            Text(
                "支持 mp3/ogg/wav/m4a，≤250KB、≤5 秒（接纳口径定稿十二.2）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val editing = editingKeyId
    if (editing != null) {
        KeyOverrideDialog(
            layoutName = layoutName,
            keyId = editing,
            entry = SkinTreeEditor.keyEntryOf(tree, editing),
            admittedSounds = admissibleSounds,
            onDismiss = { editingKeyId = null },
            onSaveEntry = { newEntry ->
                onTreeChange(SkinTreeEditor.setKeyEntry(tree, editing, newEntry))
                editingKeyId = null
            },
        )
    }
}

private val EDITABLE_LAYOUTS = listOf(
    QwertyKeyboard.NAME, T9Keyboard.NAME, T15Keyboard.NAME, NumberKeyboard.NAME,
)

private fun rawLayoutOf(name: String): List<List<KeyDef>> = when (name) {
    QwertyKeyboard.NAME -> QwertyKeyboard.buildLayout()
    T9Keyboard.NAME -> T9Keyboard.Layout
    T15Keyboard.NAME -> T15Keyboard.Layout
    else -> NumberKeyboard.Layout
}

/** 草稿所属皮肤的当前库内代次清单（新皮肤无代次时为空清单）。 */
private suspend fun loadSourceManifest(skinId: String?): PackageManifest {
    if (skinId == null) return PackageManifest()
    val index = (SkinLibrary.store.readIndex()
        as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
    val entry = index?.skins?.firstOrNull { it.id == skinId } ?: return PackageManifest()
    return runCatching {
        PackageManifest.parse(
            File(SkinLibrary.layout.fileFromRelative(entry.dir), "manifest.json").readText(),
        )
    }.getOrNull() ?: PackageManifest()
}

/**
 * 编辑器预览快照：与应用共用同一校验器+构建器（详设 §6.3-4）；
 * 资产 sha 不解析（背景由编辑器单独展示位图），音效事实按源
 * 代次清单解析以便接纳列表与应用口径一致。
 */
private fun editorSnapshot(
    context: Context,
    tree: JsonObject,
    manifest: PackageManifest,
    draftFacts: Map<String, com.jobeen.ime.skin.model.AudioAssetFacts>,
): SkinSnapshot? {
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
    val manifestFacts =
        SkinSnapshotFactory.manifestAudioFacts(SkinLibrary.layout, manifest)
    val draftOnlyIds = draftFacts.keys.filter { id ->
        manifest.assets().none { it.id == id }
    }
    return SkinSnapshotBuilder.build(
        validated = validated,
        ref = SkinGenRef("preview", "", 0),
        defaultScheme = KeyboardTheme.DEFAULT.colors,
        tier = MemoryTier.NORMAL,
        assetSha256 = { null },
        targetWidthPx = 100,
        targetHeightPx = 50,
        audioAssetOrder = manifest.assets().map { it.id } + draftOnlyIds,
        audioFacts = { id -> draftFacts[id] ?: manifestFacts(id) },
    )
}

/** 草稿导入音效的事实表（索引 → 草稿文件 → 解码元信息）。 */
private fun buildDraftAudioFacts(
    draftId: String?,
    index: Map<String, String>,
): Map<String, com.jobeen.ime.skin.model.AudioAssetFacts> {
    if (draftId == null || index.isEmpty()) return emptyMap()
    val draftStore = com.jobeen.ime.skin.draft.DraftStore(SkinLibrary.layout)
    val files = draftStore.draftAssetFiles(draftId)
    return index.mapNotNull { (logicalId, sha) ->
        val file = files.firstOrNull { it.name.contains(sha) } ?: return@mapNotNull null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
        logicalId to com.jobeen.ime.skin.model.AudioAssetFacts(
            sha256 = sha,
            ext = file.extension.lowercase(),
            byteSize = file.length(),
            info = com.jobeen.ime.skin.media.AndroidAudioInfoProbe.probe(bytes),
        )
    }.toMap()
}

/**
 * 导入一个音效文件进草稿（详设 §7.1-3）：字节 → 内容寻址草稿
 * 文件 + 索引认领；逻辑 id 取文件名净化，冲突时加序号后缀。
 * 返回（逻辑 id，新索引）；失败返回 null。须在 IO 线程调用。
 */
private fun importDraftSound(
    context: Context,
    draftId: String,
    uri: android.net.Uri,
    currentIndex: Map<String, String>,
): Pair<String, Map<String, String>>? {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return null
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    val displayName = queryDisplayName(context, uri)
    val ext = when (mime) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        else -> displayName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in setOf("mp3", "ogg", "wav", "m4a") } ?: return null
    }
    val sha = com.jobeen.ime.base.util.DigestUtil.sha256Hex(bytes)
    var base = displayName?.substringBeforeLast('.')
        ?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.trim('_')
        .orEmpty()
    if (base.isEmpty()) base = "snd_custom"
    if (!base.startsWith("snd_")) base = "snd_$base"
    var logicalId = base
    var suffix = 2
    while (currentIndex[logicalId] != null && currentIndex[logicalId] != sha) {
        logicalId = "${base}_$suffix"
        suffix++
    }
    val draftStore = com.jobeen.ime.skin.draft.DraftStore(SkinLibrary.layout)
    val target = draftStore.draftAssetFile(draftId, sha, ext)
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
    val newIndex = currentIndex + (logicalId to sha)
    draftStore.writeDraftSoundIndex(draftId, newIndex)
    return logicalId to newIndex
}

private fun queryDisplayName(context: Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

/**
 * 选键浮层：覆盖预览键盘并吞掉全部触摸（预览零执行的物理
 * 隔离），抬手时按坐标命中最上层 KeyView，以其 Appearance
 * 引用回查 KeyDef.id 选键（不靠位置/文本比对，详设 §6.1-4），
 * 并描出选中高亮框。
 */
class KeyPickingView(
    context: Context,
    private val keyboard: View,
    private val layout: List<List<KeyDef>>,
    private val onPicked: (keyId: String?) -> Unit,
) : View(context) {

    private var highlight: RectF? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0xFF0A84FF.toInt()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            pick(event.x, event.y)
        }
        return true
    }

    private fun pick(x: Float, y: Float) {
        val myLoc = IntArray(2)
        getLocationOnScreen(myLoc)
        val screenX = myLoc[0] + x
        val screenY = myLoc[1] + y
        var bestRect: Rect? = null
        var bestAppearance: KeyDef.Appearance? = null
        walkViews(keyboard) { view ->
            if (view is KeyView && view.width > 0 && view.height > 0) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
                if (screenX >= rect.left && screenX < rect.right &&
                    screenY >= rect.top && screenY < rect.bottom
                ) {
                    // 后遍历到的绘制在上层：持续覆盖取最上层命中
                    bestRect = rect
                    bestAppearance = view.def
                }
            }
        }
        val rect = bestRect
        if (rect == null) {
            highlight = null
            invalidate()
            return
        }
        highlight = RectF(
            (rect.left - myLoc[0]).toFloat(),
            (rect.top - myLoc[1]).toFloat(),
            (rect.right - myLoc[0]).toFloat(),
            (rect.bottom - myLoc[1]).toFloat(),
        )
        invalidate()
        val appearance = bestAppearance
        val def = layout.flatten().firstOrNull { it.appearance === appearance }
        onPicked(def?.id)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        highlight?.let { canvas.drawRoundRect(it, 14f, 14f, paint) }
    }

    private fun walkViews(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkViews(view.getChildAt(i), visit)
            }
        }
    }
}


// ---------- 逐键浮层 ----------

/** 浮层内的槽位编辑态（强类型）。sequence 已按 B3 裁定删除：编辑器不展示该类型。 */
internal sealed interface SlotEdit {
    data object None : SlotEdit
    data class TextVal(val value: String) : SlotEdit
    data class Cmd(val action: WhitelistAction, val value: String) : SlotEdit
}

private sealed interface SoundChoice {
    data object Factory : SoundChoice
    data object Muted : SoundChoice
    data class Asset(val assetId: String) : SoundChoice
}

internal fun SkinAction?.toSlotEdit(): SlotEdit = when (this) {
    null -> SlotEdit.None
    is SkinAction.Text -> SlotEdit.TextVal(value)
    is SkinAction.Command -> SlotEdit.Cmd(action, value ?: "")
    // 解析器已不再产生占位（sequence 一律拒绝）；防御性映射为不覆写
    SkinAction.SequencePlaceholder -> SlotEdit.None
}

internal fun SlotEdit.toJson(): kotlinx.serialization.json.JsonElement? = when (this) {
    SlotEdit.None -> null
    is SlotEdit.TextVal -> if (value.isEmpty()) {
        null
    } else {
        buildJsonObject {
            put("type", "text")
            put("value", value)
        }
    }
    is SlotEdit.Cmd -> buildJsonObject {
        put("type", "command")
        put("action", action.formatName)
        if (action.valueRule == WhitelistAction.ValueRule.REQUIRED) {
            put("value", value)
        }
    }
}

/**
 * 结构编辑初值（B3 布局模式）：条目即完整键节点，装配时把
 * widthWeight/rowSpan/alignSpanEnd 一并写回（键节点其余字段——
 * stableId 与未知节点——由 assemble 的「从条目起步」自动保留）。
 */
internal data class KeyStructuralState(
    /** 权重原文（原始数值文本；文件存原始权重，裁定 B1）。 */
    val widthWeightText: String,
    val rowSpan: Int,
    /** rowSpan 可达上限（当前行到底部的行数）。 */
    val maxRowSpan: Int,
    val alignSpanEnd: Boolean,
)

@Composable
internal fun KeyOverrideDialog(
    layoutName: String,
    keyId: String,
    entry: JsonObject?,
    admittedSounds: Set<String>,
    onDismiss: () -> Unit,
    onSaveEntry: (JsonObject?) -> Unit,
    // ---- B3 泛化点（默认值 = B2 皮肤模式行为，皮肤侧调用方零改动） ----
    /** 条目解析的谓词对（布局模式：该键恒可覆写、无锚点面）。 */
    isOverridable: (String) -> Boolean = KeyIds::isOverridable,
    isAnchor: (String) -> Boolean = KeyIds::isAnchor,
    /** 基准标签覆盖（布局模式传键节点 label；null = 按出厂表查）。 */
    baseLabel: String? = null,
    /** 整节点校验钩子（布局模式传全布局预演校验，返回错误文案或 null）。 */
    validateAssembled: ((JsonObject) -> String?)? = null,
    /** switch_layout 的可选目标（布局模式追加 custom:<id> 项）。 */
    switchLayoutTargets: Set<String> = WhitelistAction.LAYOUT_TARGETS,
    /** 音效段（布局键节点无 sound 字段——音效走 keyMappings——布局模式关）。 */
    includeSound: Boolean = true,
    /** 「恢复出厂」钮（布局模式无出厂条目概念，关；删除走 onDeleteKey）。 */
    showReset: Boolean = true,
    /** 结构编辑初值（非 null 时渲染结构段：权重/跨行/跨行对齐）。 */
    structural: KeyStructuralState? = null,
    /** 删除此键（布局模式；确认在调用方）。 */
    onDeleteKey: (() -> Unit)? = null,
    /** 标题覆盖（布局模式显示逻辑 ID）。 */
    dialogTitle: String? = null,
) {
    // 以与运行时相同的解析器读现行条目：无效绑定在运行时本就不
    // 生效，浮层展示生效态（保存时一并落为干净条目）
    val parsed: KeyOverride? = remember(entry, keyId) {
        if (entry == null) {
            null
        } else {
            SkinKeyMappingsParser.parse(
                JsonObject(mapOf(keyId to entry)),
                isOverridable, isAnchor,
            ).overrides[keyId]
        }
    }
    val factoryLabel = baseLabel ?: remember(layoutName, keyId) {
        val def = rawLayoutOf(layoutName).flatten().firstOrNull { it.id == keyId }
        when (val appearance = def?.appearance) {
            is KeyDef.Appearance.Text -> appearance.displayText
            else -> ""
        }
    }

    var labelText by remember { mutableStateOf(parsed?.label ?: "") }
    var tapEdit by remember { mutableStateOf(parsed?.tap.toSlotEdit()) }
    var swipeUpEdit by remember { mutableStateOf(parsed?.swipeUp.toSlotEdit()) }
    var swipeDownEdit by remember { mutableStateOf(parsed?.swipeDown.toSlotEdit()) }
    var longPressEdits by remember {
        mutableStateOf(parsed?.longPress?.map { it.toSlotEdit() } ?: emptyList())
    }
    var soundChoice by remember {
        mutableStateOf(
            when (val ref = parsed?.soundRef) {
                null -> SoundChoice.Factory
                "" -> SoundChoice.Muted
                else -> SoundChoice.Asset(ref)
            },
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    // 结构段状态（布局模式；皮肤模式 structural=null 不渲染不写回）
    var weightText by remember { mutableStateOf(structural?.widthWeightText ?: "") }
    var rowSpanValue by remember { mutableStateOf(structural?.rowSpan ?: 1) }
    var alignEndValue by remember { mutableStateOf(structural?.alignSpanEnd ?: false) }

    fun assemble(): JsonObject {
        val map = (entry ?: JsonObject(emptyMap())).toMutableMap()
        if (labelText.isBlank()) {
            map.remove("label")
        } else {
            map["label"] = JsonPrimitive(labelText)
        }
        if (structural != null) {
            val weight = weightText.trim()
            when {
                weight.isEmpty() -> map.remove("widthWeight")
                weight.toDoubleOrNull() != null ->
                    map["widthWeight"] = JsonPrimitive(weight.toDouble())
                // 非数值原文照写：校验器以 weight_out_of_range 明示（不静默改值）
                else -> map["widthWeight"] = JsonPrimitive(weight)
            }
            if (rowSpanValue > 1) {
                map["rowSpan"] = JsonPrimitive(rowSpanValue)
            } else {
                map.remove("rowSpan")
            }
            if (alignEndValue && rowSpanValue > 1) {
                map["alignSpanEnd"] = JsonPrimitive(true)
            } else {
                map.remove("alignSpanEnd")
            }
        }
        fun putSlot(name: String, edit: SlotEdit) {
            val node = edit.toJson()
            if (node == null) map.remove(name) else map[name] = node
        }
        putSlot("tap", tapEdit)
        putSlot("swipeUp", swipeUpEdit)
        putSlot("swipeDown", swipeDownEdit)
        val longPressNodes = longPressEdits.mapNotNull { it.toJson() }
        if (longPressNodes.isEmpty()) {
            map.remove("longPress")
        } else {
            map["longPress"] = buildJsonArray {
                longPressNodes.forEach { add(it) }
            }
        }
        if (includeSound) {
            when (val choice = soundChoice) {
                SoundChoice.Factory -> map.remove("sound")
                SoundChoice.Muted -> map["sound"] = JsonPrimitive("")
                is SoundChoice.Asset -> map["sound"] = JsonPrimitive(choice.assetId)
            }
        }
        return JsonObject(map)
    }

    fun save() {
        // 必填 value 先行拦截（与解析器 VALUE_MISSING 同口径）
        val edits = listOf(tapEdit, swipeUpEdit, swipeDownEdit) + longPressEdits
        val missing = edits.filterIsInstance<SlotEdit.Cmd>().firstOrNull {
            it.action.valueRule == WhitelistAction.ValueRule.REQUIRED && it.value.isBlank()
        }
        if (missing != null) {
            errorText = "动作 ${missing.action.formatName} 需要填写 value"
            return
        }
        val assembled = assemble()
        if (assembled.isEmpty()) {
            onSaveEntry(null)
            return
        }
        if (validateAssembled != null) {
            // 布局模式：整布局预演校验（结构改动与绑定改动一并核）
            val problem = validateAssembled(assembled)
            if (problem != null) {
                errorText = problem
                return
            }
        } else {
            // 写前预校验（详设 §6.3-2）：与导入同规则，编辑器内不可存无效绑定
            val rejections = SkinTreeEditor.validateKeyEntry(keyId, assembled)
            if (rejections.isNotEmpty()) {
                errorText = "绑定无效：" + rejections.joinToString("；") {
                    "${it.slot?.jsonName ?: "键"} → ${it.reason.name.lowercase()}"
                }
                return
            }
        }
        onSaveEntry(assembled)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle ?: "编辑按键 $keyId") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (factoryLabel.isNotEmpty()) {
                    Text(
                        "出厂标签：$factoryLabel",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it.take(16) },
                    label = { Text("标签（≤16 字，清空=出厂；可用 \$schemaName / \$versionName）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (structural != null) {
                    Text("结构", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it },
                        label = { Text("宽度权重 widthWeight（原始值，渲染期按行内比例分配；空 = 1）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "跨行 rowSpan：$rowSpanValue",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { if (rowSpanValue > 1) rowSpanValue-- },
                            enabled = rowSpanValue > 1,
                        ) { Text("－") }
                        TextButton(
                            onClick = {
                                if (rowSpanValue < structural.maxRowSpan) rowSpanValue++
                            },
                            enabled = rowSpanValue < structural.maxRowSpan,
                        ) { Text("＋") }
                    }
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "跨行时对齐行尾 alignSpanEnd",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.Switch(
                            checked = alignEndValue && rowSpanValue > 1,
                            onCheckedChange = { alignEndValue = it },
                            enabled = rowSpanValue > 1,
                        )
                    }
                }
                ActionEditRow(
                    "点按 tap", tapEdit, KeySlot.TAP,
                    switchLayoutTargets = switchLayoutTargets,
                    onChange = { tapEdit = it },
                )
                ActionEditRow(
                    "上滑 swipeUp", swipeUpEdit, KeySlot.SWIPE_UP,
                    switchLayoutTargets = switchLayoutTargets,
                    onChange = { swipeUpEdit = it },
                )
                ActionEditRow(
                    "下滑 swipeDown", swipeDownEdit, KeySlot.SWIPE_DOWN,
                    switchLayoutTargets = switchLayoutTargets,
                    onChange = { swipeDownEdit = it },
                )
                Text("长按 longPress（数组序 = 候选条展示序）", fontWeight = FontWeight.Bold)
                val voiceLocked = longPressEdits.any {
                    it is SlotEdit.Cmd && it.action == WhitelistAction.VOICE_INPUT
                }
                longPressEdits.forEachIndexed { index, item ->
                    ActionEditRow(
                        title = "长按 #${index + 1}",
                        edit = item,
                        slot = KeySlot.LONG_PRESS,
                        switchLayoutTargets = switchLayoutTargets,
                        onChange = { newEdit ->
                            longPressEdits = if (
                                newEdit is SlotEdit.Cmd &&
                                newEdit.action == WhitelistAction.VOICE_INPUT
                            ) {
                                // voice_input 独占：选中即锁定为单项（定稿四.5）
                                listOf(newEdit)
                            } else {
                                longPressEdits.toMutableList()
                                    .also { it[index] = newEdit }
                            }
                        },
                        onDelete = {
                            longPressEdits = longPressEdits.filterIndexed { i, _ -> i != index }
                        },
                        onMoveUp = if (index > 0) {
                            {
                                longPressEdits = longPressEdits.toMutableList().also {
                                    val moved = it.removeAt(index)
                                    it.add(index - 1, moved)
                                }
                            }
                        } else {
                            null
                        },
                        onMoveDown = if (index < longPressEdits.lastIndex) {
                            {
                                longPressEdits = longPressEdits.toMutableList().also {
                                    val moved = it.removeAt(index)
                                    it.add(index + 1, moved)
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                if (voiceLocked) {
                    Text(
                        "voice_input 已锁定长按数组为单项（定稿四.5）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedButton(
                        onClick = { longPressEdits = longPressEdits + SlotEdit.TextVal("") },
                    ) { Text("添加长按动作") }
                }
                if (includeSound) {
                Text("音效", fontWeight = FontWeight.Bold)
                LabeledDropdown(
                    label = "该键点按音",
                    options = listOf(
                        "factory" to "出厂（跟随全局）",
                        "muted" to "静音",
                    ) + admittedSounds.sorted().map { "asset:$it" to "音效 $it" },
                    selectedKey = when (val choice = soundChoice) {
                        SoundChoice.Factory -> "factory"
                        SoundChoice.Muted -> "muted"
                        is SoundChoice.Asset -> "asset:${choice.assetId}"
                    },
                    onSelect = { picked ->
                        soundChoice = when {
                            picked == "muted" -> SoundChoice.Muted
                            picked.startsWith("asset:") ->
                                SoundChoice.Asset(picked.removePrefix("asset:"))
                            else -> SoundChoice.Factory
                        }
                    },
                )
                if (soundChoice is SoundChoice.Asset &&
                    (soundChoice as SoundChoice.Asset).assetId !in admittedSounds
                ) {
                    Text(
                        "当前绑定音效未在已接纳列表中（包内缺失或超限），运行时回退全局",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                }
                errorText?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { save() }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDeleteKey != null) {
                    TextButton(onClick = onDeleteKey) { Text("删除此键") }
                }
                if (showReset) {
                    TextButton(onClick = { onSaveEntry(null) }) { Text("恢复出厂") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ActionEditRow(
    title: String,
    edit: SlotEdit,
    slot: KeySlot,
    /** switch_layout 的目标候选（布局模式含 custom:<id> 项）。 */
    switchLayoutTargets: Set<String> = WhitelistAction.LAYOUT_TARGETS,
    onChange: (SlotEdit) -> Unit,
    onDelete: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (onMoveUp != null) {
                TextButton(onClick = onMoveUp) { Text("上移") }
            }
            if (onMoveDown != null) {
                TextButton(onClick = onMoveDown) { Text("下移") }
            }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
        val typeOptions = buildList {
            add("none" to "不覆写")
            add("text" to "文本（喂组字）")
            add("command" to "命令")
        }
        val typeKey = when (edit) {
            SlotEdit.None -> "none"
            is SlotEdit.TextVal -> "text"
            is SlotEdit.Cmd -> "command"
        }
        LabeledDropdown(
            label = "类型",
            options = typeOptions,
            selectedKey = typeKey,
            onSelect = { picked ->
                onChange(
                    when (picked) {
                        "text" -> SlotEdit.TextVal("")
                        "command" -> SlotEdit.Cmd(
                            WhitelistAction.entries.first { slot in it.allowedSlots }, "",
                        )
                        else -> SlotEdit.None
                    },
                )
            },
        )
        when (edit) {
            is SlotEdit.TextVal -> OutlinedTextField(
                value = edit.value,
                onValueChange = { onChange(SlotEdit.TextVal(it)) },
                label = { Text("组字文本（参与 Caps，不做标点转换）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            is SlotEdit.Cmd -> {
                val allowed = WhitelistAction.entries.filter { slot in it.allowedSlots }
                LabeledDropdown(
                    label = "动作",
                    options = allowed.map { it.formatName to actionGloss(it) },
                    selectedKey = edit.action.formatName,
                    onSelect = { picked ->
                        val action = WhitelistAction.fromFormatName(picked)!!
                        onChange(
                            SlotEdit.Cmd(
                                action,
                                if (action == edit.action) edit.value else "",
                            ),
                        )
                    },
                )
                when (edit.action) {
                    WhitelistAction.COMMIT_DIRECT -> OutlinedTextField(
                        value = edit.value,
                        onValueChange = { onChange(SlotEdit.Cmd(edit.action, it)) },
                        label = { Text("提交文本（字面：不转全角、不随 Caps）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WhitelistAction.SWITCH_LAYOUT -> LabeledDropdown(
                        label = "目标布局",
                        options = switchLayoutTargets.map { it to it },
                        selectedKey = edit.value.ifEmpty { "Qwerty" },
                        onSelect = { onChange(SlotEdit.Cmd(edit.action, it)) },
                    )
                    else -> Unit
                }
            }
            else -> Unit
        }
    }
}

/** 类型/动作下拉（material3 菜单；禁用项渲染但不可选）。 */
@Composable
private fun LabeledDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    enabledKeys: Set<String> = options.map { it.first }.toSet(),
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: selectedKey
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(
                label, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for ((key, text) in options) {
                    DropdownMenuItem(
                        text = { Text(text) },
                        enabled = key in enabledKeys,
                        onClick = {
                            expanded = false
                            onSelect(key)
                        },
                    )
                }
            }
        }
    }
}

/** 白名单动作的编辑器文案（格式名 + 语义注，防 text/commit_direct 混用）。 */
private fun actionGloss(action: WhitelistAction): String = when (action) {
    WhitelistAction.COMMIT_DIRECT -> "commit_direct · 直接上屏（字面）"
    WhitelistAction.LITERAL_NEWLINE -> "literal_newline · 字面换行"
    WhitelistAction.ENTER -> "enter · 回车（随输入框）"
    WhitelistAction.DELETE -> "delete · 退格"
    WhitelistAction.DELETE_FORWARD -> "delete_forward · 向前删除"
    WhitelistAction.SPACE -> "space · 空格"
    WhitelistAction.CURSOR_LEFT -> "cursor_left · 光标左移"
    WhitelistAction.CURSOR_RIGHT -> "cursor_right · 光标右移"
    WhitelistAction.ROTATE_SCHEMA -> "rotate_schema · 轮换方案"
    WhitelistAction.OPEN_SCHEMA_PICKER -> "open_schema_picker · 方案列表"
    WhitelistAction.SWITCH_SYSTEM_IME -> "switch_system_ime · 下一个系统输入法"
    WhitelistAction.OPEN_SYSTEM_IME_PICKER -> "open_system_ime_picker · 系统输入法选择器"
    WhitelistAction.HIDE_KEYBOARD -> "hide_keyboard · 隐藏键盘"
    WhitelistAction.CAPS -> "caps · 大小写切换"
    WhitelistAction.SWITCH_LAYOUT -> "switch_layout · 切换布局"
    WhitelistAction.RESUME -> "resume · 返回上一布局"
    WhitelistAction.CLEAR -> "clear · 清空组字"
    WhitelistAction.UNDO -> "undo · 撤销"
    WhitelistAction.REDO -> "redo · 重做"
    WhitelistAction.VOICE_INPUT -> "voice_input · 语音输入（仅长按独占）"
}
