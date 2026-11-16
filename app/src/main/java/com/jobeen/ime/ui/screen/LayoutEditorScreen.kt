// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.skin.layout.LayoutEditorModel
import com.jobeen.ime.skin.model.SkinJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 可视化布局编辑器（2026-11-16 松烟可取项 3a：只学思路自己重写，
 * 不搬 GPL 代码）。
 *
 * 逐键改标签／宽度／点按／长按／上滑／下滑，增删移动键与行，顶部
 * 实时预览（结构预览：标签＋相对宽度＋行列，不含皮肤配色——真机
 * 键面以保存后实际键盘为准）。保存复用皮肤编辑器的 saveLayout
 * 链路（校验→新代→应用切换），本页不另写保存逻辑。
 *
 * 入口：SkinEditorScreen 选中布局文件时的“可视化编辑布局”按钮。
 * [onSave] 收到序列化后的布局 JSON 文本（父级据此走保存流程）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutEditorScreen(
    layoutName: String,
    initialText: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val originalTree = remember(initialText) { SkinJson.parseObject(initialText) }
    var tree by remember(initialText) { mutableStateOf(originalTree) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = tree != null && tree != originalTree

    fun requestBack() {
        if (dirty) confirmLeave = true else onBack()
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
                title = { Text(layoutName) },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val current = tree ?: return@TextButton
                            onSave(LayoutEditorModel.toPrettyJson(current))
                        },
                        enabled = dirty,
                    ) {
                        Text(stringResource(R.string.layout_editor_save))
                    }
                },
            )
        },
    ) { padding ->
        val currentTree = tree
        if (currentTree == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.layout_editor_unparseable))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- 实时预览（点键选中） ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (row in LayoutEditorModel.rows(currentTree)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (key in row) {
                                val stableId =
                                    (key["stableId"] as? JsonPrimitive)?.contentOrNull ?: ""
                                val label =
                                    (key["label"] as? JsonPrimitive)?.contentOrNull ?: ""
                                val weight =
                                    (key["widthWeight"] as? JsonPrimitive)?.content
                                        ?.toDoubleOrNull() ?: 1.0
                                val hasLongPress = key["longPress"] is JsonArray
                                val isSelected = stableId == selectedId
                                Box(
                                    modifier = Modifier
                                        .weight(weight.toFloat().coerceAtLeast(0.05f))
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        )
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable { selectedId = stableId },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        label.ifEmpty { " " },
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                    if (hasLongPress) {
                                        Text(
                                            "…",
                                            fontSize = 10.sp,
                                            modifier = Modifier.align(Alignment.BottomEnd)
                                                .padding(end = 4.dp, bottom = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (dirty) {
                Text(
                    stringResource(R.string.layout_editor_modified),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val selectedKey = selectedId?.let { LayoutEditorModel.keyByStableId(currentTree, it) }
            if (selectedKey == null) {
                Text(
                    stringResource(R.string.layout_editor_no_key_selected),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            KeyEditor(
                tree = currentTree,
                stableId = selectedId!!,
                onTreeChange = { tree = it },
            )
        }
    }
}

/** 选中键的编辑区（标签／宽度／动作／长按／滑动／移动／增删）。 */
@Composable
private fun KeyEditor(
    tree: JsonObject,
    stableId: String,
    onTreeChange: (JsonObject) -> Unit,
) {
    val key = LayoutEditorModel.keyByStableId(tree, stableId) ?: return
    val label = (key["label"] as? JsonPrimitive)?.contentOrNull ?: ""
    val weight = (key["widthWeight"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 1.0
    val tap = key["tap"] as? JsonObject
    val longPress = (key["longPress"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val swipeUp = key["swipeUp"] as? JsonObject
    val swipeDown = key["swipeDown"] as? JsonObject
    val loc = LayoutEditorModel.findKey(tree, stableId)
    val rows = LayoutEditorModel.rows(tree)

    fun update(transformed: JsonObject?) {
        if (transformed != null) onTreeChange(transformed)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = label,
            onValueChange = { update(LayoutEditorModel.setLabel(tree, stableId, it)) },
            label = { Text(stringResource(R.string.layout_editor_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 宽度：滑块粗调（0.25–4，常规键全覆盖）＋数字框精调（校验域 0.05–20）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.layout_editor_width))
            Slider(
                value = weight.toFloat().coerceIn(0.25f, 4f),
                onValueChange = {
                    val rounded = (it * 100).toInt() / 100.0
                    update(LayoutEditorModel.setWeight(tree, stableId, rounded))
                },
                valueRange = 0.25f..4f,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                value = trimWeight(weight),
                onValueChange = { text ->
                    text.toDoubleOrNull()?.let {
                        update(LayoutEditorModel.setWeight(tree, stableId, it))
                    }
                },
                singleLine = true,
                modifier = Modifier.width(84.dp),
            )
        }
        ActionEditor(
            label = stringResource(R.string.layout_editor_tap),
            action = tap,
            onChange = { update(LayoutEditorModel.setTap(tree, stableId, it)) },
        )
        // 长按动作列。
        Text(stringResource(R.string.layout_editor_long_press))
        for ((index, action) in longPress.withIndex()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ActionEditor(
                        label = "${index + 1}",
                        action = action,
                        onChange = { newAction ->
                            val newList = longPress.toMutableList()
                            if (newAction == null) newList.removeAt(index)
                            else newList[index] = newAction
                            update(
                                LayoutEditorModel.setLongPress(tree, stableId, newList),
                            )
                        },
                    )
                }
                TextButton(
                    onClick = {
                        val newList = longPress.toMutableList().apply { removeAt(index) }
                        update(LayoutEditorModel.setLongPress(tree, stableId, newList))
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
        OutlinedButton(
            onClick = {
                update(
                    LayoutEditorModel.setLongPress(
                        tree, stableId, longPress + LayoutEditorModel.actionText(""),
                    ),
                )
            },
        ) {
            Text(stringResource(R.string.layout_editor_add_long_press))
        }
        ActionEditor(
            label = stringResource(R.string.layout_editor_swipe_up),
            action = swipeUp,
            onChange = {
                update(LayoutEditorModel.setSwipe(tree, stableId, "swipeUp", it))
            },
        )
        ActionEditor(
            label = stringResource(R.string.layout_editor_swipe_down),
            action = swipeDown,
            onChange = {
                update(LayoutEditorModel.setSwipe(tree, stableId, "swipeDown", it))
            },
        )
        // 移动／增删。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canLeft = loc != null && loc.keyIndex > 0
            val canRight = loc != null && loc.keyIndex < rows[loc.rowIndex].size - 1
            val canUp = loc != null && loc.rowIndex > 0
            val canDown = loc != null && loc.rowIndex < rows.size - 1
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.LEFT,
                        ),
                    )
                },
                enabled = canLeft,
            ) { Text(stringResource(R.string.layout_editor_move_left)) }
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.RIGHT,
                        ),
                    )
                },
                enabled = canRight,
            ) { Text(stringResource(R.string.layout_editor_move_right)) }
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.UP,
                        ),
                    )
                },
                enabled = canUp,
            ) { Text(stringResource(R.string.layout_editor_move_up)) }
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.DOWN,
                        ),
                    )
                },
                enabled = canDown,
            ) { Text(stringResource(R.string.layout_editor_move_down)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val rowIndex = loc?.rowIndex ?: return@OutlinedButton
                    update(LayoutEditorModel.addKey(tree, rowIndex, "键"))
                },
            ) { Text(stringResource(R.string.layout_editor_add_key)) }
            OutlinedButton(
                onClick = { update(LayoutEditorModel.addRow(tree)) },
                enabled = rows.size < 8,
            ) { Text(stringResource(R.string.layout_editor_add_row)) }
            TextButton(
                onClick = {
                    update(LayoutEditorModel.removeKey(tree, stableId))
                },
            ) {
                Text(
                    stringResource(R.string.layout_editor_delete_key),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = {
                    val rowIndex = loc?.rowIndex ?: return@TextButton
                    update(LayoutEditorModel.removeRow(tree, rowIndex))
                },
                enabled = rows.size > 1,
            ) {
                Text(
                    stringResource(R.string.layout_editor_delete_row),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun trimWeight(weight: Double): String {
    val rounded = (weight * 100).toInt() / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/** 单个动作槽编辑器（无／文字／符号／命令）。 */
@Composable
private fun ActionEditor(
    label: String,
    action: JsonObject?,
    onChange: (JsonObject?) -> Unit,
) {
    val type = (action?.get("type") as? JsonPrimitive)?.contentOrNull
    val value = (action?.get("value") as? JsonPrimitive)?.contentOrNull ?: ""
    val commandName = (action?.get("action") as? JsonPrimitive)?.contentOrNull ?: ""
    var typeExpanded by remember { mutableStateOf(false) }
    var commandExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, modifier = Modifier.width(72.dp))
            Box {
                OutlinedButton(onClick = { typeExpanded = true }) {
                    Text(actionTypeLabel(type, commandName))
                }
                DropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_none)) },
                        onClick = { typeExpanded = false; onChange(null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_text)) },
                        onClick = {
                            typeExpanded = false
                            onChange(LayoutEditorModel.actionText(value))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_symbol)) },
                        onClick = {
                            typeExpanded = false
                            onChange(LayoutEditorModel.actionSymbol(value))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_command)) },
                        onClick = {
                            typeExpanded = false
                            onChange(LayoutEditorModel.actionCommand(COMMAND_OPTIONS.first().first))
                        },
                    )
                }
            }
        }
        when (type) {
            "text", "symbol" -> {
                OutlinedTextField(
                    shape = RoundedCornerShape(12.dp),
                    value = value,
                    onValueChange = {
                        onChange(
                            if (type == "text") LayoutEditorModel.actionText(it)
                            else LayoutEditorModel.actionSymbol(it),
                        )
                    },
                    label = { Text(stringResource(R.string.layout_editor_action_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            "command" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        OutlinedButton(onClick = { commandExpanded = true }) {
                            Text(commandDisplayName(commandName))
                        }
                        DropdownMenu(
                            expanded = commandExpanded,
                            onDismissRequest = { commandExpanded = false },
                        ) {
                            for ((name, display) in COMMAND_OPTIONS) {
                                DropdownMenuItem(
                                    text = { Text(display) },
                                    onClick = {
                                        commandExpanded = false
                                        onChange(
                                            LayoutEditorModel.actionCommand(
                                                name,
                                                if (name in COMMANDS_REQUIRING_VALUE) value else null,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (commandName in COMMANDS_REQUIRING_VALUE) {
                        OutlinedTextField(
                            shape = RoundedCornerShape(12.dp),
                            value = value,
                            onValueChange = {
                                onChange(LayoutEditorModel.actionCommand(commandName, it))
                            },
                            label = { Text(stringResource(R.string.layout_editor_action_value)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun actionTypeLabel(type: String?, commandName: String): String = when (type) {
    "text" -> stringResource(R.string.layout_editor_action_text)
    "symbol" -> stringResource(R.string.layout_editor_action_symbol)
    "command" -> commandDisplayName(commandName)
    else -> stringResource(R.string.layout_editor_action_none)
}

private fun commandDisplayName(name: String): String =
    COMMAND_OPTIONS.firstOrNull { it.first == name }?.second ?: name

/** 动作选择器常用命令（formatName → 显示名；其余命令走文本编辑器）。 */
private val COMMAND_OPTIONS = listOf(
    "enter" to "回车",
    "delete" to "删除",
    "space" to "空格",
    "caps" to "大写锁定",
    "cursor_left" to "光标左移",
    "cursor_right" to "光标右移",
    "literal_newline" to "换行",
    "hide_keyboard" to "收起键盘",
    "voice_input" to "语音输入",
    "toggle_ascii_mode" to "中英切换",
    "clear" to "清空",
    "undo" to "撤销",
    "switch_layout" to "切换布局",
    "switch_schema" to "切换方案",
    "commit_direct" to "直接提交",
)

/** 需填内容的命令（ValueRule.REQUIRED）。 */
private val COMMANDS_REQUIRING_VALUE = setOf("switch_layout", "switch_schema", "commit_direct")
