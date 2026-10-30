// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.input.panel.toolbar.CustomButtonSlot
import com.jobeen.ime.input.panel.toolbar.CustomDisplayKind
import com.jobeen.ime.input.panel.toolbar.ToolbarActionBinding
import com.jobeen.ime.input.panel.toolbar.ToolbarBackground
import com.jobeen.ime.input.panel.toolbar.ToolbarButtonEntry
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import com.jobeen.ime.input.panel.toolbar.ToolbarCustomButton
import com.jobeen.ime.input.panel.toolbar.ToolbarCustomConfig
import com.jobeen.ime.input.panel.toolbar.ToolbarSkinState
import com.jobeen.ime.input.panel.toolbar.ToolbarUserData
import com.jobeen.ime.input.panel.toolbar.ToolbarUserStore
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SliderRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow
import java.io.File
import java.util.UUID

/**
 * App 内工具栏编辑页（2026-10-29 工具栏编辑轮）。
 *
 * 分层与存储见 [ToolbarUserStore]：用户编辑按皮肤 id 分键，
 * 胜过皮肤工具栏；皮肤未定义处回落出厂默认。编辑在工具栏
 * 下次刷新时生效（键盘重开或皮肤／主题刷新，与皮肤工具栏
 * 改动同口径）。本页不设隐藏工具栏开关——Jime 无此能力，
 * 不造开关。
 *
 * 页内导航（单 Activity 内状态机，不另起 Activity）：
 * 主页 → 编辑按钮／添加按钮／新建自定义按钮／自定义工具栏
 * → 各表单；返回一律回主页（表单内返回回来源页）。
 * 切换行为只走选择自定义工具栏对话框（管理页只做新建／编辑
 * ／删除，不留行内切换入口，避免双入口歧义）。
 */
private enum class ToolbarPage {
    MAIN, EDIT, TOOLBARS,
}

@Composable
fun ToolbarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val storeFile = remember { File(context.filesDir, ToolbarUserStore.FILE_NAME) }
    val imageDir = remember { File(context.filesDir, ToolbarUserStore.IMAGE_DIR) }
    // 当前皮肤 id：current() 触发持久化 verified 记录加载，与输入
    // 服务面板创建后的 peek() 口径一致（设置页进程可能尚未加载
    // 快照，直接 peek 会误取 builtin 键、与键盘侧串台）。
    val skinId = remember {
        SkinSnapshotProvider.current(context)?.ref?.skinId
            ?: ToolbarUserStore.BUILTIN_SKIN_KEY
    }
    var data by remember { mutableStateOf(ToolbarUserStore.load(storeFile).data) }
    var page by remember { mutableStateOf(ToolbarPage.MAIN) }
    // 编辑目标列：null＝当前皮肤默认列；toolbarId＝该自定义栏的列。
    var editToolbarId by remember { mutableStateOf<String?>(null) }
    // 自定义按钮表单目标：null＝新建；id＝编辑既有定义。
    var customFormId by remember { mutableStateOf<String?>(null) }
    // 自定义栏表单目标：null＝新建；id＝编辑既有栏。
    var toolbarFormId by remember { mutableStateOf<String?>(null) }
    // 对话框态：添加／自定义按钮表单／自定义栏表单均为对话框（页式入口已拆除）；
    // customFormId／toolbarFormId 沿用“null＝新建、id＝编辑”语义。
    var addDialog by remember { mutableStateOf(false) }
    var customFormOpen by remember { mutableStateOf(false) }
    var toolbarFormOpen by remember { mutableStateOf(false) }
    // 选择自定义工具栏对话框（切换唯一入口；管理页不再行内切换）。
    var selectDialog by remember { mutableStateOf(false) }

    fun persist(next: ToolbarUserData) {
        data = next
        ToolbarUserStore.saveDebounced(storeFile, next)
    }
    // 退出前刷盘：500ms 防抖内返回不丢最后一次编辑。
    DisposableEffect(Unit) {
        onDispose { ToolbarUserStore.flush() }
    }

    fun backToMain() {
        editToolbarId = null
        page = ToolbarPage.MAIN
    }
    BackHandler(enabled = page != ToolbarPage.MAIN) { backToMain() }

    fun currentSkinState(): ToolbarSkinState =
        data.skins[skinId] ?: ToolbarSkinState()

    fun activeToolbar(): ToolbarCustomConfig? =
        currentSkinState().activeToolbarId?.let { data.customToolbars[it] }

    fun currentList(): List<ToolbarButtonEntry> =
        editToolbarId?.let { data.customToolbars[it]?.buttons }
            ?: currentSkinState().buttons

    fun setCurrentList(list: List<ToolbarButtonEntry>) {
        val next = if (editToolbarId == null) {
            data.copy(
                skins = data.skins + (skinId to currentSkinState().copy(buttons = list)),
            )
        } else {
            val config = data.customToolbars[editToolbarId] ?: return
            data.copy(
                customToolbars = data.customToolbars +
                    (editToolbarId!! to config.copy(buttons = list)),
            )
        }
        persist(next)
    }

    @Composable
    fun editTitle(): String {
        val active = editToolbarId?.let { data.customToolbars[it] }
        return if (active != null) {
            stringResource(R.string.toolbar_edit_named, active.name)
        } else {
            stringResource(R.string.toolbar_edit_buttons)
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.toolbar_title),
        onBack = {
            if (customFormOpen) customFormOpen = false
            else if (toolbarFormOpen) toolbarFormOpen = false
            else if (addDialog) addDialog = false
            else if (selectDialog) selectDialog = false
            else if (page != ToolbarPage.MAIN) backToMain()
            else onBack()
        },
    ) {
        when (page) {
            ToolbarPage.MAIN -> ToolbarMainPage(
                data = data,
                skinId = skinId,
                activeToolbar = activeToolbar(),
                hideToolbar = data.hideToolbar,
                onHideChange = { persist(data.copy(hideToolbar = it)) },
                onEditCurrent = {
                    editToolbarId = currentSkinState().activeToolbarId
                    page = ToolbarPage.EDIT
                },
                onOpenToolbars = { page = ToolbarPage.TOOLBARS },
                onNewToolbar = {
                    toolbarFormId = null
                    toolbarFormOpen = true
                },
            )
            ToolbarPage.EDIT -> ToolbarEditPage(
                title = editTitle(),
                list = currentList(),
                onListChange = ::setCurrentList,
                defOf = { data.customButtons[it] },
                onAdd = { addDialog = true },
                onEditCustom = { id ->
                    customFormId = id
                    customFormOpen = true
                },
            )
            // 添加／自定义按钮表单／自定义栏表单均为对话框（见 when 后的对话框挂载），
            // 此处不再有页分支。
            ToolbarPage.TOOLBARS -> ToolbarToolbarsPage(
                data = data,
                activeId = currentSkinState().activeToolbarId,
                onSelect = { selectDialog = true },
                onOpenForm = { id ->
                    toolbarFormId = id
                    toolbarFormOpen = true
                },
                onNew = {
                    toolbarFormId = null
                    toolbarFormOpen = true
                },
            )
        }
        if (addDialog) {
            ToolbarAddDialog(
                list = currentList(),
                customButtons = data.customButtons,
                onAddPool = { id ->
                    setCurrentList(currentList() + ToolbarButtonEntry(id))
                    addDialog = false
                },
                onAddCustom = { id ->
                    setCurrentList(currentList() + ToolbarButtonEntry(id))
                    addDialog = false
                },
                onNewCustom = {
                    customFormId = null
                    customFormOpen = true
                    addDialog = false
                },
                onDismiss = { addDialog = false },
            )
        }
        if (customFormOpen) {
            ToolbarCustomFormDialog(
                existing = customFormId?.let { data.customButtons[it] },
                imageDir = imageDir,
                onSave = { def, isNew ->
                    var next = data.copy(
                        customButtons = data.customButtons + (def.id to def),
                    )
                    // 新建保存后进当前列（列表未满时；满则只存定义）。
                    if (isNew && currentList().size < ToolbarUserStore.MAX_BUTTONS) {
                        if (editToolbarId == null) {
                            val state = next.skins[skinId] ?: ToolbarSkinState()
                            next = next.copy(
                                skins = next.skins +
                                    (skinId to state.copy(
                                        buttons = state.buttons + ToolbarButtonEntry(def.id),
                                    )),
                            )
                        } else {
                            val config = next.customToolbars[editToolbarId]
                            if (config != null) {
                                next = next.copy(
                                    customToolbars = next.customToolbars +
                                        (editToolbarId!! to config.copy(
                                            buttons = config.buttons + ToolbarButtonEntry(def.id),
                                        )),
                                )
                            }
                        }
                    }
                    persist(next)
                    customFormOpen = false
                },
                onDelete = { def ->
                    val removedLists: (List<ToolbarButtonEntry>) -> List<ToolbarButtonEntry> =
                        { list -> list.filter { it.ref != def.id } }
                    var next = data.copy(
                        customButtons = data.customButtons - def.id,
                        customToolbars = data.customToolbars.mapValues { (_, config) ->
                            config.copy(buttons = removedLists(config.buttons))
                        },
                        skins = data.skins.mapValues { (_, state) ->
                            state.copy(buttons = removedLists(state.buttons))
                        },
                    )
                    if (def.kind == CustomDisplayKind.IMAGE) {
                        ToolbarUserStore.sanitizeImageFile(def.imageFile)?.let { name ->
                            runCatching { File(imageDir, name).delete() }
                        }
                    }
                    persist(next)
                    customFormOpen = false
                },
                onDismiss = { customFormOpen = false },
            )
        }
        if (toolbarFormOpen) {
            ToolbarToolbarFormDialog(
                existing = toolbarFormId?.let { data.customToolbars[it] },
                customButtons = data.customButtons,
                imageDir = imageDir,
                onSave = { config, isNew ->
                    val next = data.copy(
                        customToolbars = data.customToolbars + (config.id to config),
                    )
                    persist(next)
                    toolbarFormOpen = false
                    // 新建后直进该栏的按钮编辑；编辑既有栏关对话框回栏列表。
                    if (isNew) {
                        editToolbarId = config.id
                        page = ToolbarPage.EDIT
                    }
                },
                onEditButtons = { id ->
                    editToolbarId = id
                    page = ToolbarPage.EDIT
                    toolbarFormOpen = false
                },
                onDelete = { config ->
                    var next = data.copy(customToolbars = data.customToolbars - config.id)
                    next = next.copy(
                        skins = next.skins.mapValues { (_, state) ->
                            if (state.activeToolbarId == config.id) {
                                state.copy(activeToolbarId = null)
                            } else state
                        },
                    )
                    if (config.background is ToolbarBackground.Image) {
                        ToolbarUserStore.sanitizeImageFile(config.background.file)?.let { name ->
                            runCatching { File(imageDir, name).delete() }
                        }
                    }
                    persist(next)
                    toolbarFormOpen = false
                },
                onDismiss = { toolbarFormOpen = false },
            )
        }
        if (selectDialog) {
            ToolbarSelectDialog(
                data = data,
                activeId = currentSkinState().activeToolbarId,
                onUse = { id ->
                    persist(
                        data.copy(
                            skins = data.skins +
                                (skinId to currentSkinState().copy(activeToolbarId = id)),
                        ),
                    )
                    selectDialog = false
                },
                onUseDefault = {
                    persist(
                        data.copy(
                            skins = data.skins +
                                (skinId to currentSkinState().copy(activeToolbarId = null)),
                        ),
                    )
                    selectDialog = false
                },
                onDismiss = { selectDialog = false },
            )
        }
    }
}

@Composable
private fun ToolbarMainPage(
    data: ToolbarUserData,
    skinId: String,
    activeToolbar: ToolbarCustomConfig?,
    hideToolbar: Boolean,
    onHideChange: (Boolean) -> Unit,
    onEditCurrent: () -> Unit,
    onOpenToolbars: () -> Unit,
    onNewToolbar: () -> Unit,
) {
    val state = data.skins[skinId] ?: ToolbarSkinState()
    val currentCount = if (activeToolbar != null) {
        activeToolbar.buttons.count { it.enabled }
    } else {
        state.buttons.count { it.enabled }
    }
    SettingsGroup(title = stringResource(R.string.toolbar_current)) {
        NavRow(
            title = activeToolbar?.name ?: stringResource(R.string.toolbar_default),
            subtitle = stringResource(R.string.toolbar_button_count, currentCount),
            onClick = onOpenToolbars,
        )
        NavRow(
            title = stringResource(R.string.toolbar_edit_buttons),
            subtitle = stringResource(R.string.toolbar_edit_current_hint),
            onClick = onEditCurrent,
        )
        // 隐藏输入工具栏：全局开关（不分皮肤），开＝不渲染工具栏区，
        // 改动在工具栏下次刷新时生效（与工具栏编辑同口径）。
        SwitchRow(
            title = stringResource(R.string.toolbar_hide_toolbar),
            checked = hideToolbar,
            onCheckedChange = onHideChange,
        )
    }
    SettingsGroup(title = stringResource(R.string.toolbar_custom_toolbars)) {
        NavRow(
            title = stringResource(R.string.toolbar_custom_toolbars),
            subtitle = stringResource(R.string.toolbar_toolbar_count, data.customToolbars.size),
            onClick = onOpenToolbars,
        )
        NavRow(
            title = stringResource(R.string.toolbar_new_toolbar),
            onClick = onNewToolbar,
        )
    }
    Text(
        text = stringResource(R.string.toolbar_main_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ToolbarEditPage(
    title: String,
    list: List<ToolbarButtonEntry>,
    onListChange: (List<ToolbarButtonEntry>) -> Unit,
    defOf: (String) -> ToolbarCustomButton?,
    onAdd: () -> Unit,
    onEditCustom: (String) -> Unit,
) {
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    // 编辑页标题（与分组标题同式：14sp 半粗体正文色，左 4dp、上 8dp、下 6dp）。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    if (list.isEmpty()) {
        Text(
            text = stringResource(R.string.toolbar_empty_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
    // 每钮一张卡：名·id 在上、控件行（开关＋上移＋下移＋删除）在下右对齐。
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEachIndexed { index, entry ->
            val label = if (ToolbarUserStore.isCustomRef(entry.ref)) {
                val def = defOf(entry.ref)
                when {
                    def == null -> entry.ref
                    def.kind == CustomDisplayKind.IMAGE -> stringResource(R.string.toolbar_image_button)
                    else -> def.display
                }
            } else {
                ToolbarConfig.item(entry.ref)?.label ?: entry.ref
            }
            GroupCard {
                // 编辑行：名·id 在上（名上 id 下）；自定义钮
                // 的 id 行用内部标识（custom:xxxxxxxx），同名钮可区分。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (ToolbarUserStore.isCustomRef(entry.ref)) {
                                Modifier.clickable { onEditCustom(entry.ref) }
                            } else Modifier,
                        ),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.ref,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { checked ->
                            val next = list.toMutableList()
                            next[index] = entry.copy(enabled = checked)
                            onListChange(next)
                        },
                    )
                    IconButton(
                        onClick = {
                            if (index > 0) {
                                val next = list.toMutableList()
                                next[index] = next[index - 1].also { next[index - 1] = next[index] }
                                onListChange(next)
                            }
                        },
                        enabled = index > 0,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.toolbar_move_up),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (index < list.size - 1) {
                                val next = list.toMutableList()
                                next[index] = next[index + 1].also { next[index + 1] = next[index] }
                                onListChange(next)
                            }
                        },
                        enabled = index < list.size - 1,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDownward,
                            contentDescription = stringResource(R.string.toolbar_move_down),
                        )
                    }
                    IconButton(onClick = { deleteIndex = index }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.toolbar_delete),
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val full = list.size >= ToolbarUserStore.MAX_BUTTONS
    // 添加钮：居中药丸（与参考底部悬浮药丸同形；页内居中，不做真悬浮）。
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = onAdd,
            enabled = !full,
            shape = RoundedCornerShape(50),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(text = stringResource(R.string.toolbar_add_button))
        }
    }
    if (full) {
        Text(
            text = stringResource(R.string.toolbar_full_note, ToolbarUserStore.MAX_BUTTONS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
    val doomed = deleteIndex?.let { list.getOrNull(it) }
    if (doomed != null) {
        SettingsDialog(
            onDismissRequest = { deleteIndex = null },
            title = stringResource(R.string.toolbar_delete_button_title),
            description = stringResource(R.string.toolbar_delete_button_note),
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { deleteIndex = null },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.toolbar_delete),
                    color = MaterialTheme.colorScheme.error,
                    onClick = {
                        val next = list.toMutableList()
                        next.removeAt(deleteIndex!!)
                        onListChange(next)
                        deleteIndex = null
                    },
                ),
            ),
        )
    }
}

/** 动作名（资源文案，与 21 项白名单一一对应）。 */
@Composable
internal fun whitelistActionLabel(action: WhitelistAction): String = stringResource(
    when (action) {
        WhitelistAction.COMMIT_DIRECT -> R.string.action_commit_direct
        WhitelistAction.LITERAL_NEWLINE -> R.string.action_literal_newline
        WhitelistAction.ENTER -> R.string.action_enter
        WhitelistAction.DELETE -> R.string.action_delete
        WhitelistAction.DELETE_FORWARD -> R.string.action_delete_forward
        WhitelistAction.SPACE -> R.string.action_space
        WhitelistAction.CURSOR_LEFT -> R.string.action_cursor_left
        WhitelistAction.CURSOR_RIGHT -> R.string.action_cursor_right
        WhitelistAction.ROTATE_SCHEMA -> R.string.action_rotate_schema
        WhitelistAction.OPEN_SCHEMA_PICKER -> R.string.action_open_schema_picker
        WhitelistAction.SWITCH_SCHEMA -> R.string.action_switch_schema
        WhitelistAction.SWITCH_SYSTEM_IME -> R.string.action_switch_system_ime
        WhitelistAction.OPEN_SYSTEM_IME_PICKER -> R.string.action_open_system_ime_picker
        WhitelistAction.HIDE_KEYBOARD -> R.string.action_hide_keyboard
        WhitelistAction.CAPS -> R.string.action_caps
        WhitelistAction.SWITCH_LAYOUT -> R.string.action_switch_layout
        WhitelistAction.RESUME -> R.string.action_resume
        WhitelistAction.CLEAR -> R.string.action_clear
        WhitelistAction.UNDO -> R.string.action_undo
        WhitelistAction.REDO -> R.string.action_redo
        WhitelistAction.VOICE_INPUT -> R.string.action_voice_input
    },
)

@Composable
internal fun customSlotLabel(slot: CustomButtonSlot): String = stringResource(
    when (slot) {
        CustomButtonSlot.TAP -> R.string.slot_tap
        CustomButtonSlot.LONG_PRESS -> R.string.slot_long_press
        CustomButtonSlot.DOUBLE_TAP -> R.string.slot_double_tap
        CustomButtonSlot.SWIPE_UP -> R.string.slot_swipe_up
        CustomButtonSlot.SWIPE_DOWN -> R.string.slot_swipe_down
        CustomButtonSlot.SWIPE_LEFT -> R.string.slot_swipe_left
        CustomButtonSlot.SWIPE_RIGHT -> R.string.slot_swipe_right
    },
)

@Composable
internal fun customKindLabel(kind: CustomDisplayKind): String = stringResource(
    when (kind) {
        CustomDisplayKind.TEXT -> R.string.kind_text
        CustomDisplayKind.EMOJI -> R.string.kind_emoji
        CustomDisplayKind.IMAGE -> R.string.kind_image
    },
)

/** 必填值动作的输入提示（其余动作无值字段）。 */
@Composable
internal fun requiredValueHint(action: WhitelistAction): String = stringResource(
    when (action) {
        WhitelistAction.COMMIT_DIRECT -> R.string.value_hint_commit
        WhitelistAction.SWITCH_SCHEMA -> R.string.value_hint_schema
        WhitelistAction.SWITCH_LAYOUT -> R.string.value_hint_layout
        else -> R.string.value_hint_generic
    },
)

internal fun newImageFileName(mime: String?, fallbackExt: String): String {
    val ext = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> fallbackExt.lowercase().ifEmpty { "bin" }
    }
    return "img_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext
}

/** 预设色（与外观页预设同值，工具栏页自有副本，不改外观页）。 */
private val TOOLBAR_COLOR_PRESETS: List<Pair<Int, Int?>> = listOf(
    R.string.follow to null,
    R.string.color_white to 0xFFFFFFFF.toInt(),
    R.string.color_light_gray to 0xFFEEEEEE.toInt(),
    R.string.color_dark_gray to 0xFF424242.toInt(),
    R.string.color_black to 0xFF000000.toInt(),
    R.string.color_red to 0xFFE53935.toInt(),
    R.string.color_green to 0xFF43A047.toInt(),
    R.string.color_blue to 0xFF1E88E5.toInt(),
    R.string.color_orange to 0xFFFB8C00.toInt(),
)

@Composable
private fun presetColorName(value: Int?): String {
    if (value == null) return stringResource(R.string.follow)
    val res = TOOLBAR_COLOR_PRESETS.firstOrNull { it.second == value }?.first
    return if (res != null) stringResource(res) else stringResource(R.string.color_custom)
}

@Composable
private fun toolbarBackgroundSummary(background: ToolbarBackground): String {
    return when (background) {
        is ToolbarBackground.Color -> stringResource(
            R.string.toolbar_bg_color_named, presetColorName(background.argb),
        )
        is ToolbarBackground.Transparent -> stringResource(R.string.toolbar_bg_transparent)
        is ToolbarBackground.Image -> stringResource(R.string.toolbar_bg_image)
    }
}

/** 预设色选择对话框（跟随＋8 色；返回 null＝跟随；栏背景不含跟随项）。 */
@Composable
private fun ToolbarColorDialog(
    selected: Int?,
    includeFollow: Boolean = true,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.toolbar_pick_color),
        body = {
            Column {
                TOOLBAR_COLOR_PRESETS.filter { includeFollow || it.second != null }.forEach { (nameRes, argb) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(argb)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(nameRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (argb == selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        },
        actions = listOf(
            SettingsDialogAction(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
            ),
        ),
    )
}

@Composable
/**
 * 自定义工具栏管理页：只做新建／编辑（点行进表单，表单内删除），
 * 切换只走选择对话框（顶部“选择自定义工具栏”按钮），行内不再
 * 设使用／回默认入口，避免双入口歧义。
 */
private fun ToolbarToolbarsPage(
    data: ToolbarUserData,
    activeId: String?,
    onSelect: () -> Unit,
    onOpenForm: (String) -> Unit,
    onNew: () -> Unit,
) {
    OutlinedButton(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.toolbar_select_toolbar))
    }
    Spacer(Modifier.height(8.dp))
    SettingsGroup(title = stringResource(R.string.toolbar_custom_toolbars)) {
        if (data.customToolbars.isEmpty()) {
            Text(
                text = stringResource(R.string.toolbar_no_toolbars),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        data.customToolbars.values.forEach { config ->
            val isActive = config.id == activeId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenForm(config.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = config.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.toolbar_config_summary,
                            config.buttons.count { it.enabled },
                            toolbarBackgroundSummary(config.background),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    Text(
                        text = stringResource(R.string.toolbar_in_use),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
    Button(
        onClick = onNew,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(R.string.toolbar_new_toolbar))
    }
    Spacer(Modifier.height(20.dp))
}

/** 选择自定义工具栏对话框：切换唯一入口——列出自定义栏（点行
 * 使用并关闭），使用中行只标“使用中”不点；有使用中栏时给回默认
 * 动作；空表时只显示暂无文案＋取消。 */
@Composable
private fun ToolbarSelectDialog(
    data: ToolbarUserData,
    activeId: String?,
    onUse: (String) -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.toolbar_select_toolbar),
        body = {
            Column {
                if (data.customToolbars.isEmpty()) {
                    Text(
                        text = stringResource(R.string.toolbar_no_toolbars),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                data.customToolbars.values.forEach { config ->
                    val isActive = config.id == activeId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isActive) Modifier
                                else Modifier.clickable { onUse(config.id) },
                            )
                            .padding(vertical = 12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = config.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(
                                    R.string.toolbar_config_summary,
                                    config.buttons.count { it.enabled },
                                    toolbarBackgroundSummary(config.background),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isActive) {
                            Text(
                                text = stringResource(R.string.toolbar_in_use),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        actions = buildList {
            if (activeId != null) {
                add(
                    SettingsDialogAction(
                        text = stringResource(R.string.toolbar_use_default),
                        onClick = onUseDefault,
                    ),
                )
            }
            add(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                ),
            )
        },
    )
}

/** 添加按钮对话框：新建行在首，其后池行（副标题带 Jime 原生 id），最后未进列表的自定义行。 */
@Composable
private fun ToolbarAddDialog(
    list: List<ToolbarButtonEntry>,
    customButtons: Map<String, ToolbarCustomButton>,
    onAddPool: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    onNewCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.toolbar_add_button),
        body = {
            Column {
                ToolbarUserStore.toolbarAddRows(list, customButtons).forEach { row ->
                    when (row) {
                        is ToolbarUserStore.ToolbarAddRow.NewCustom -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNewCustom() }
                                .padding(vertical = 12.dp),
                        ) {
                            // 新建行：单行（与池行名·id 同行一致；说明行已并入标题省略号）。
                            Text(
                                text = stringResource(R.string.toolbar_new_custom_button) + "...",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        is ToolbarUserStore.ToolbarAddRow.Pool -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (row.added) Modifier.alpha(0.5f)
                                    else Modifier.clickable { onAddPool(row.id) },
                                )
                                .padding(vertical = 12.dp),
                        ) {
                            // 添加行：名·id 同行（池 id 即 Jime 原生 id）。
                            Text(
                                text = "${row.label} · ${row.id}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (row.added) {
                                Text(
                                    text = stringResource(R.string.toolbar_added),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is ToolbarUserStore.ToolbarAddRow.Custom -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddCustom(row.id) }
                                .padding(vertical = 12.dp),
                        ) {
                            // 添加行：名·id 同行（自定义钮 id 即内部标识）。
                            Text(
                                text = if (row.isImage) {
                                    "${stringResource(R.string.toolbar_image_button)} · ${row.id}"
                                } else {
                                    "${row.display} · ${row.id}"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        actions = listOf(
            SettingsDialogAction(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
            ),
        ),
    )
}

/** 自定义按钮单个手势槽行：动作行＋必填值输入框（与页式表单同口径）。 */
@Composable
private fun ToolbarCustomSlotRow(
    slot: CustomButtonSlot,
    binding: ToolbarActionBinding?,
    onPick: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    NavRow(
        title = customSlotLabel(slot),
        subtitle = when {
            binding == null -> stringResource(R.string.toolbar_unspecified)
            binding.action.valueRule == WhitelistAction.ValueRule.REQUIRED &&
                binding.value.isNullOrBlank() -> stringResource(R.string.toolbar_value_missing)
            else -> whitelistActionLabel(binding.action)
        },
        onClick = onPick,
    )
    if (binding != null &&
        binding.action.valueRule == WhitelistAction.ValueRule.REQUIRED
    ) {
        OutlinedTextField(
            value = binding.value ?: "",
            onValueChange = onValueChange,
            label = { Text(text = requiredValueHint(binding.action)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
}

/** 新建／编辑自定义按钮对话框：基本＋点击＋外观＋更多手势与尺寸（默认收起）。 */
@Composable
private fun ToolbarCustomFormDialog(
    existing: ToolbarCustomButton?,
    imageDir: File,
    onSave: (ToolbarCustomButton, Boolean) -> Unit,
    onDelete: (ToolbarCustomButton) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(existing?.kind ?: CustomDisplayKind.TEXT) }
    var display by remember { mutableStateOf(existing?.display ?: "") }
    var imageFile by remember { mutableStateOf(existing?.imageFile) }
    var iconSize by remember {
        mutableStateOf((existing?.iconSizeDp ?: ToolbarUserStore.DEFAULT_ICON_DP).toFloat())
    }
    var width by remember {
        mutableStateOf((existing?.widthDp ?: ToolbarUserStore.DEFAULT_WIDTH_DP).toFloat())
    }
    var backgroundArgb by remember { mutableStateOf(existing?.backgroundArgb) }
    var fontSizeSp by remember { mutableStateOf(existing?.fontSizeSp) }
    var borderArgb by remember { mutableStateOf(existing?.borderArgb) }
    var borderWidthDp by remember { mutableStateOf(existing?.borderWidthDp ?: 0f) }
    var pressedArgb by remember { mutableStateOf(existing?.pressedArgb) }
    var bindings by remember { mutableStateOf(existing?.bindings ?: emptyMap()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var imageError by remember { mutableStateOf(false) }
    var actionSlot by remember { mutableStateOf<CustomButtonSlot?>(null) }
    var colorDialog by remember { mutableStateOf(false) }
    var borderColorDialog by remember { mutableStateOf(false) }
    var pressedColorDialog by remember { mutableStateOf(false) }
    var appearanceDialog by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageError = false
        val imported = runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@runCatching null
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                var overflow = false
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 5L * 1024 * 1024) {
                        overflow = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                if (overflow) return@runCatching null
                val mime = context.contentResolver.getType(uri)
                val ext = uri.lastPathSegment
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    .orEmpty()
                val name = newImageFileName(mime, ext)
                val file = File(imageDir, name)
                file.parentFile?.mkdirs()
                file.writeBytes(out.toByteArray())
                name
            }
        }.getOrNull()
        if (imported != null) {
            val old = imageFile
            if (old != null && old != imported) {
                runCatching { File(imageDir, old).delete() }
            }
            imageFile = imported
            kind = CustomDisplayKind.IMAGE
        } else {
            imageError = true
        }
    }

    SettingsDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) {
            stringResource(R.string.toolbar_new_custom_button)
        } else {
            stringResource(
                R.string.toolbar_edit_named,
                if (existing.kind == CustomDisplayKind.IMAGE) {
                    stringResource(R.string.toolbar_image_button)
                } else {
                    existing.display
                },
            )
        },
        body = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    CustomDisplayKind.entries.forEach { entry ->
                        val selected = kind == entry
                        if (selected) {
                            Button(onClick = {}) {
                                Text(text = customKindLabel(entry))
                            }
                        } else {
                            OutlinedButton(onClick = { kind = entry }) {
                                Text(text = customKindLabel(entry))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                if (kind != CustomDisplayKind.IMAGE) {
                    OutlinedTextField(
                        value = display,
                        onValueChange = { display = it },
                        label = {
                            Text(
                                text = if (kind == CustomDisplayKind.EMOJI) {
                                    stringResource(R.string.toolbar_display_emoji)
                                } else {
                                    stringResource(R.string.toolbar_display_text)
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                } else {
                    Button(
                        onClick = { imagePicker.launch(arrayOf("image/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = if (imageFile != null) {
                                stringResource(R.string.toolbar_image_picked, imageFile!!)
                            } else {
                                stringResource(R.string.toolbar_pick_image)
                            },
                        )
                    }
                    if (imageError) {
                        Text(
                            text = stringResource(R.string.toolbar_image_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                SliderRow(
                    title = stringResource(R.string.toolbar_icon_size),
                    value = iconSize,
                    valueLabel = stringResource(R.string.toolbar_dp_value, iconSize.toInt()),
                    range = ToolbarUserStore.MIN_ICON_DP.toFloat()..ToolbarUserStore.MAX_ICON_DP.toFloat(),
                    onValueChange = { iconSize = it },
                )
                ToolbarCustomSlotRow(
                    slot = CustomButtonSlot.TAP,
                    binding = bindings[CustomButtonSlot.TAP],
                    onPick = { actionSlot = CustomButtonSlot.TAP },
                    onValueChange = { value ->
                        bindings[CustomButtonSlot.TAP]?.let { current ->
                            bindings = bindings + (CustomButtonSlot.TAP to current.copy(value = value))
                        }
                    },
                )
                NavRow(
                    title = stringResource(R.string.toolbar_button_appearance),
                    subtitle = presetColorName(backgroundArgb),
                    onClick = { appearanceDialog = true },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { moreExpanded = !moreExpanded }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.toolbar_more_gestures_size),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (moreExpanded) "▼" else "▶",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (moreExpanded) {
                    SliderRow(
                        title = stringResource(R.string.toolbar_button_width),
                        value = width,
                        valueLabel = stringResource(R.string.toolbar_dp_value, width.toInt()),
                        range = ToolbarUserStore.MIN_WIDTH_DP.toFloat()..ToolbarUserStore.MAX_WIDTH_DP.toFloat(),
                        onValueChange = { width = it },
                    )
                    listOf(
                        CustomButtonSlot.LONG_PRESS,
                        CustomButtonSlot.DOUBLE_TAP,
                        CustomButtonSlot.SWIPE_UP,
                        CustomButtonSlot.SWIPE_DOWN,
                        CustomButtonSlot.SWIPE_LEFT,
                        CustomButtonSlot.SWIPE_RIGHT,
                    ).forEach { slot ->
                        ToolbarCustomSlotRow(
                            slot = slot,
                            binding = bindings[slot],
                            onPick = { actionSlot = slot },
                            onValueChange = { value ->
                                bindings[slot]?.let { current ->
                                    bindings = bindings + (slot to current.copy(value = value))
                                }
                            },
                        )
                    }
                }
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                if (existing != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.toolbar_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        actions = listOf(
            SettingsDialogAction(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
            ),
            SettingsDialogAction(
                text = stringResource(R.string.toolbar_save),
                onClick = {
                    val id = existing?.id ?: ToolbarUserStore.newCustomButtonId()
                    val (def, problems) = ToolbarUserStore.buildCustomButton(
                        id = id,
                        kind = kind,
                        display = display,
                        imageFile = imageFile,
                        iconSizeDp = iconSize.toInt(),
                        widthDp = width.toInt(),
                        backgroundArgb = backgroundArgb,
                        fontSizeSp = fontSizeSp,
                        borderArgb = borderArgb,
                        borderWidthDp = borderWidthDp,
                        pressedArgb = pressedArgb,
                        bindings = bindings,
                    )
                    if (def != null) {
                        errorText = null
                        onSave(def, existing == null)
                    } else {
                        errorText = problems.joinToString("；")
                    }
                },
            ),
        ),
    )
    // 按钮外观子对话框：控件直写表单态（与颜色对话框同口径），完成即关闭，
    // 真正保存仍由主表单的保存按钮完成。
    if (appearanceDialog) {
        SettingsDialog(
            onDismissRequest = { appearanceDialog = false },
            title = stringResource(R.string.toolbar_button_appearance),
            body = {
                Column {
                    SwitchRow(
                        title = stringResource(R.string.toolbar_font_follow_icon),
                        checked = fontSizeSp == null,
                        onCheckedChange = { follow ->
                            fontSizeSp = if (follow) null else ToolbarUserStore.DEFAULT_FONT_SP
                        },
                    )
                    val fontSp = fontSizeSp
                    if (fontSp != null) {
                        SliderRow(
                            title = stringResource(R.string.toolbar_font_size),
                            value = fontSp,
                            valueLabel = stringResource(R.string.toolbar_sp_value, fontSp.toInt()),
                            range = ToolbarUserStore.MIN_FONT_SP..ToolbarUserStore.MAX_FONT_SP,
                            onValueChange = { fontSizeSp = it },
                        )
                    }
                    NavRow(
                        title = stringResource(R.string.toolbar_button_background),
                        subtitle = presetColorName(backgroundArgb),
                        onClick = { colorDialog = true },
                    )
                    SliderRow(
                        title = stringResource(R.string.toolbar_border_width),
                        value = borderWidthDp,
                        valueLabel = stringResource(R.string.toolbar_dp_value, borderWidthDp.toInt()),
                        range = ToolbarUserStore.MIN_BORDER_DP..ToolbarUserStore.MAX_BORDER_DP,
                        onValueChange = { v ->
                            borderWidthDp = v
                            if (v > 0f && borderArgb == null) {
                                borderArgb = ToolbarUserStore.DEFAULT_BORDER_ARGB
                            }
                        },
                    )
                    NavRow(
                        title = stringResource(R.string.toolbar_button_border_color),
                        subtitle = borderArgb?.let { presetColorName(it) }
                            ?: stringResource(R.string.toolbar_border_none),
                        onClick = { borderColorDialog = true },
                    )
                    NavRow(
                        title = stringResource(R.string.toolbar_pressed_color),
                        subtitle = pressedArgb?.let { presetColorName(it) }
                            ?: stringResource(R.string.toolbar_pressed_default),
                        onClick = { pressedColorDialog = true },
                    )
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.toolbar_done),
                    onClick = { appearanceDialog = false },
                ),
            ),
        )
    }
    if (colorDialog) {
        ToolbarColorDialog(
            selected = backgroundArgb,
            onSelect = { backgroundArgb = it },
            onDismiss = { colorDialog = false },
        )
    }
    if (borderColorDialog) {
        ToolbarColorDialog(
            selected = borderArgb,
            includeFollow = false,
            onSelect = { borderArgb = it },
            onDismiss = { borderColorDialog = false },
        )
    }
    if (pressedColorDialog) {
        ToolbarColorDialog(
            selected = pressedArgb,
            onSelect = { pressedArgb = it },
            onDismiss = { pressedColorDialog = false },
        )
    }
    val slot = actionSlot
    if (slot != null) {
        val current = bindings[slot]
        SettingsDialog(
            onDismissRequest = { actionSlot = null },
            title = stringResource(R.string.toolbar_pick_action, customSlotLabel(slot)),
            body = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                bindings = bindings - slot
                                actionSlot = null
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.toolbar_unspecified),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (current == null) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                            )
                        }
                    }
                    WhitelistAction.entries.forEach { action ->
                        val voiceBlocked = action == WhitelistAction.VOICE_INPUT &&
                            slot != CustomButtonSlot.LONG_PRESS
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (voiceBlocked) Modifier.alpha(0.5f)
                                    else Modifier.clickable {
                                        bindings = bindings +
                                            (slot to ToolbarActionBinding(action, null))
                                        actionSlot = null
                                    },
                                )
                                .padding(vertical = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = whitelistActionLabel(action),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (voiceBlocked) {
                                    Text(
                                        text = stringResource(R.string.toolbar_voice_long_only),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (current?.action == action) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { actionSlot = null },
                ),
            ),
        )
    }
    if (confirmDelete && existing != null) {
        SettingsDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.toolbar_delete_custom_title),
            description = stringResource(R.string.toolbar_delete_custom_note),
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { confirmDelete = false },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.toolbar_delete),
                    color = MaterialTheme.colorScheme.error,
                    onClick = {
                        confirmDelete = false
                        onDelete(existing)
                    },
                ),
            ),
        )
    }
}

/** 新建／编辑自定义工具栏对话框：栏名＋栏背景＋（既有栏）按钮编辑入口。 */
@Composable
private fun ToolbarToolbarFormDialog(
    existing: ToolbarCustomConfig?,
    customButtons: Map<String, ToolbarCustomButton>,
    imageDir: File,
    onSave: (ToolbarCustomConfig, Boolean) -> Unit,
    onEditButtons: (String) -> Unit,
    onDelete: (ToolbarCustomConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var background by remember {
        mutableStateOf<ToolbarBackground>(
            existing?.background ?: ToolbarBackground.Transparent,
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var backgroundDialog by remember { mutableStateOf(false) }
    var colorDialog by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageError = false
        val imported = runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@runCatching null
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                var overflow = false
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 5L * 1024 * 1024) {
                        overflow = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                if (overflow) return@runCatching null
                val mime = context.contentResolver.getType(uri)
                val ext = uri.lastPathSegment
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    .orEmpty()
                val fileName = newImageFileName(mime, ext)
                val file = File(imageDir, fileName)
                file.parentFile?.mkdirs()
                file.writeBytes(out.toByteArray())
                fileName
            }
        }.getOrNull()
        if (imported != null) {
            val old = (background as? ToolbarBackground.Image)?.file
            if (old != null && old != imported) {
                runCatching { File(imageDir, old).delete() }
            }
            background = ToolbarBackground.Image(imported)
        } else {
            imageError = true
        }
    }

    SettingsDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) {
            stringResource(R.string.toolbar_new_toolbar)
        } else {
            stringResource(R.string.toolbar_edit_named, existing.name)
        },
        body = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(R.string.toolbar_toolbar_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
                NavRow(
                    title = stringResource(R.string.toolbar_toolbar_background),
                    subtitle = toolbarBackgroundSummary(background),
                    onClick = { backgroundDialog = true },
                )
                if (existing != null) {
                    NavRow(
                        title = stringResource(R.string.toolbar_edit_buttons),
                        subtitle = stringResource(
                            R.string.toolbar_button_count, existing.buttons.count { it.enabled },
                        ),
                        onClick = { onEditButtons(existing.id) },
                    )
                }
                if (imageError) {
                    Text(
                        text = stringResource(R.string.toolbar_image_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                if (existing != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.toolbar_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        actions = listOf(
            SettingsDialogAction(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
            ),
            SettingsDialogAction(
                text = stringResource(R.string.toolbar_save),
                onClick = {
                    if (name.isBlank()) {
                        errorText = context.getString(R.string.toolbar_name_empty)
                    } else {
                        errorText = null
                        val config = ToolbarCustomConfig(
                            id = existing?.id ?: ToolbarUserStore.newCustomToolbarId(),
                            name = name.trim(),
                            buttons = existing?.buttons ?: emptyList(),
                            background = background,
                        )
                        val problems = ToolbarUserStore.validateCustomToolbar(config, customButtons)
                        if (problems.isEmpty()) {
                            onSave(config, existing == null)
                        } else {
                            errorText = problems.joinToString("；")
                        }
                    }
                },
            ),
        ),
    )
    if (backgroundDialog) {
        SettingsDialog(
            onDismissRequest = { backgroundDialog = false },
            title = stringResource(R.string.toolbar_toolbar_background),
            body = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                backgroundDialog = false
                                colorDialog = true
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.toolbar_bg_color),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (background is ToolbarBackground.Color) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                background = ToolbarBackground.Transparent
                                backgroundDialog = false
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.toolbar_bg_transparent),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (background is ToolbarBackground.Transparent) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                backgroundDialog = false
                                imagePicker.launch(arrayOf("image/*"))
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.toolbar_bg_image),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (background is ToolbarBackground.Image) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                            )
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { backgroundDialog = false },
                ),
            ),
        )
    }
    if (colorDialog) {
        ToolbarColorDialog(
            selected = (background as? ToolbarBackground.Color)?.argb,
            includeFollow = false,
            onSelect = { argb ->
                if (argb != null) background = ToolbarBackground.Color(argb)
            },
            onDismiss = { colorDialog = false },
        )
    }
    if (confirmDelete && existing != null) {
        SettingsDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.toolbar_delete_toolbar_title),
            description = stringResource(R.string.toolbar_delete_toolbar_note),
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { confirmDelete = false },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.toolbar_delete),
                    color = MaterialTheme.colorScheme.error,
                    onClick = {
                        confirmDelete = false
                        onDelete(existing)
                    },
                ),
            ),
        )
    }
}
