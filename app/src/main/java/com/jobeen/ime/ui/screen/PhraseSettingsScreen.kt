// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.database.PhraseVariable
import com.jobeen.ime.data.manager.PhraseManager
import kotlinx.coroutines.launch

/**
 * 常用语管理页（WO52 6.1，设置一级入口 `phrase`，标题“常用语”）：
 * 列表＋编辑页；`phrase?id=` deep link 的落点是本页的编辑态
 * （id 由 [PhraseActivity] 经 [initialPhraseId] 传入，路由本身由
 * 第 5 组 4.2 接，本组只做到“详情页可按 id 打开”）。
 *
 * 411dp 排布：行标题＋副标题（标题＋一行说明＋右侧 Switch 的家规；
 * 开关行与列表行均为单行标题＋省略副标题，无横向溢出）。
 */
@Composable
fun PhraseSettingsScreen(
    onBack: () -> Unit,
    initialPhraseId: Long = -1L,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phrases by remember { mutableStateOf<List<PhraseManager.Phrase>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var shortcutEnabled by remember { mutableStateOf(PhraseManager.isShortcutEnabled(context)) }
    var autoSortEnabled by remember { mutableStateOf(PhraseManager.isAutoSortEnabled(context)) }
    var refreshTick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<PhraseManager.Phrase?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PhraseManager.Phrase?>(null) }

    fun reload() {
        scope.launch {
            phrases = if (query.isBlank()) PhraseManager.getAll(context)
            else PhraseManager.search(context, query.trim())
        }
    }

    LaunchedEffect(refreshTick, query) { reload() }
    // deep link 落点：带 id 进页即打开该条的编辑页；id 查不到时留在列表。
    LaunchedEffect(initialPhraseId) {
        if (initialPhraseId > 0) {
            PhraseManager.getById(context, initialPhraseId)?.let { editing = it }
        }
    }

    if (editing != null || creating) {
        PhraseEditPage(
            phrase = editing,
            onBack = { editing = null; creating = false; refreshTick++ },
            onSaved = { editing = null; creating = false; refreshTick++ },
            onDeleted = { editing = null; creating = false; refreshTick++ },
        )
        return
    }

    SettingsPageScaffold(
        title = stringResource(R.string.phrase_title),
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.phrase_search_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        ScreenComponent.GroupCard {
            ScreenComponent.SwitchRow(
                title = stringResource(R.string.phrase_shortcut_title),
                checked = shortcutEnabled,
                onCheckedChange = {
                    shortcutEnabled = it
                    PhraseManager.setShortcutEnabled(context, it)
                },
            )
            Text(
                text = stringResource(
                    if (shortcutEnabled) R.string.phrase_shortcut_on_info
                    else R.string.phrase_shortcut_off_info,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            ScreenComponent.SwitchRow(
                title = stringResource(R.string.phrase_auto_sort_title),
                checked = autoSortEnabled,
                onCheckedChange = {
                    autoSortEnabled = it
                    PhraseManager.setAutoSortEnabled(context, it)
                    refreshTick++
                },
            )
            Text(
                text = stringResource(R.string.phrase_auto_sort_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.phrase_add))
            }
        }
        if (phrases.isEmpty()) {
            Text(
                text = stringResource(
                    if (query.isBlank()) R.string.phrase_empty else R.string.phrase_no_match,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        } else {
            ScreenComponent.GroupCard {
                phrases.forEach { phrase ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = phrase.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(phrase.text)
                                    if (phrase.shortcut.isNotEmpty()) append(" · ${phrase.shortcut}")
                                    if (phrase.category.isNotEmpty()) append(" · ${phrase.category}")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                PhraseManager.setPinned(context, phrase.id, !phrase.pinned)
                                refreshTick++
                            }
                        }) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = null,
                                tint = if (phrase.pinned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editing = phrase }) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                        IconButton(onClick = { confirmDelete = phrase }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    val toDelete = confirmDelete
    if (toDelete != null) {
        SettingsConfirmDialog(
            onDismissRequest = { confirmDelete = null },
            title = stringResource(R.string.phrase_delete),
            text = stringResource(R.string.phrase_delete_confirm, toDelete.label),
            confirmText = stringResource(R.string.phrase_delete),
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    PhraseManager.delete(context, toDelete.id)
                    refreshTick++
                }
            },
            dismissText = stringResource(R.string.phrase_cancel),
            confirmColor = MaterialTheme.colorScheme.error,
        )
    }
}

/** 常用语编辑页（新建／编辑同一张表）：内容、标签、输入码、分类、标签、变量。 */
@Composable
private fun PhraseEditPage(
    phrase: PhraseManager.Phrase?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(phrase?.text.orEmpty()) }
    var label by remember { mutableStateOf(phrase?.label.orEmpty()) }
    var shortcut by remember { mutableStateOf(phrase?.shortcut.orEmpty()) }
    var category by remember { mutableStateOf(phrase?.category.orEmpty()) }
    var tags by remember { mutableStateOf(phrase?.tags.orEmpty()) }
    val variables = remember {
        mutableStateListOf<VariableDraft>().also { list ->
            phrase?.variables?.forEach { list.add(VariableDraft(it.key, it.value, it.isSecure)) }
        }
    }
    var shortcutError by remember { mutableStateOf<Int?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun save() {
        val error = PhraseManager.validateShortcut(shortcut.trim().lowercase())
        shortcutError = error
        if (error != null) return
        scope.launch {
            val vars = variables.filter { it.key.isNotBlank() }
                .map { PhraseVariable(it.key.trim(), it.value, it.isSecure) }
            if (phrase == null) {
                PhraseManager.insert(
                    context, text, label,
                    shortcut = shortcut.trim().lowercase(),
                    category = category, tags = tags, variables = vars,
                )
            } else {
                PhraseManager.update(
                    context,
                    phrase.copy(
                        text = text, label = label,
                        shortcut = shortcut.trim().lowercase(),
                        category = category, tags = tags, variables = vars,
                    ),
                )
            }
            onSaved()
        }
    }

    SettingsPageScaffold(
        title = stringResource(
            if (phrase == null) R.string.phrase_add_title else R.string.phrase_edit_title,
        ),
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.phrase_text_hint)) },
            placeholder = { Text(stringResource(R.string.phrase_input_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text(stringResource(R.string.phrase_label_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = shortcut,
            onValueChange = {
                shortcut = it.lowercase()
                shortcutError = null
            },
            label = { Text(stringResource(R.string.phrase_shortcut_label)) },
            placeholder = { Text(stringResource(R.string.phrase_shortcut_hint)) },
            singleLine = true,
            isError = shortcutError != null,
            supportingText = shortcutError?.let { { Text(stringResource(it)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = category,
            onValueChange = { category = it },
            label = { Text(stringResource(R.string.phrase_category_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            label = { Text(stringResource(R.string.phrase_tags_label)) },
            placeholder = { Text(stringResource(R.string.phrase_tags_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.phrase_variables_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.phrase_variables_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        variables.forEachIndexed { index, draft ->
            VariableRow(
                draft = draft,
                onRemove = { variables.removeAt(index) },
            )
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = { variables.add(VariableDraft("", "", false)) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.phrase_add))
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { save() },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.phrase_save))
            }
            Spacer(Modifier.width(8.dp))
            if (phrase != null) {
                TextButton(
                    onClick = { showDeleteConfirm = true },
                ) {
                    Text(
                        stringResource(R.string.phrase_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm && phrase != null) {
        SettingsConfirmDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.phrase_delete),
            text = stringResource(R.string.phrase_delete_confirm, phrase.label),
            confirmText = stringResource(R.string.phrase_delete),
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    PhraseManager.delete(context, phrase.id)
                    onDeleted()
                }
            },
            dismissText = stringResource(R.string.phrase_cancel),
            confirmColor = MaterialTheme.colorScheme.error,
        )
    }
}

private class VariableDraft(
    key: String,
    value: String,
    isSecure: Boolean,
) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
    var isSecure by mutableStateOf(isSecure)
}

@Composable
private fun VariableRow(
    draft: VariableDraft,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft.key,
            onValueChange = { draft.key = it },
            label = { Text("key") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = draft.value,
            onValueChange = { draft.value = it },
            label = { Text("value") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Checkbox(checked = draft.isSecure, onCheckedChange = { draft.isSecure = it })
            Text(
                text = stringResource(R.string.sensitive_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = null)
        }
    }
}
