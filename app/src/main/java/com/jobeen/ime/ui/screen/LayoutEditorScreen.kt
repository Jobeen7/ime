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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
                shape = MaterialTheme.shapes.large,
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
                                        .clip(MaterialTheme.shapes.small)
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
                                            MaterialTheme.shapes.small,
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

