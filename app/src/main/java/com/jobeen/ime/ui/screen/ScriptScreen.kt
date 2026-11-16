// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.database.ScriptItem
import com.jobeen.ime.data.database.ScriptSeeds
import com.jobeen.ime.data.database.ScriptTag
import com.jobeen.ime.data.database.ScriptVariable
import com.jobeen.ime.data.script.ScriptPrefs
import com.jobeen.ime.data.script.ScriptRepository
import com.jobeen.ime.script.AndroidScriptHost
import com.jobeen.ime.script.IsolatedScriptRunner
import com.jobeen.ime.script.ScriptInputs
import com.jobeen.ime.script.ScriptRunException
import com.jobeen.ime.script.ScriptRunResult
import com.jobeen.ime.script.ScriptVariableInjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 脚本管理页（WO52 8.1，设置一级入口 `script`，标题“脚本”）：
 * 列表＋编辑页＋变量管理＋命令面板脚本前缀配置。
 *
 * 411dp 排布：行标题＋副标题（标题＋一行说明＋右侧 Switch 的家规；
 * 开关行与列表行均为单行标题＋省略副标题，无横向溢出）。
 *
 * 已知范围：resultInsertState／closeWindowState 只原值透传，不设开关
 * （语义只确认到布尔，未确认 UI 文案与默认值口径）。
 * 变量注入：库里的 ScriptVariable 在试跑时以 `$key` 全局注入脚本
 * （敏感变量不注入，见 ScriptVariableInjection）；执行走隔离进程
 * （IsolatedScriptRunner），$http 须在脚本头部 `@connect` 声明域名。
 */
@Composable
fun ScriptScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ScriptRepository.get(context) }
    var scripts by remember { mutableStateOf<List<ScriptItem>>(emptyList()) }
    var variables by remember { mutableStateOf<List<ScriptVariable>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var prefix by rememberSaveable { mutableStateOf(ScriptPrefs.getCommandViewScriptPrefix(context)) }
    var refreshTick by remember { mutableStateOf(0) }
    // UI-2：自定义类型只存 saveable id，再由列表派生（旋转不丢正在编的条目）。
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = scripts.firstOrNull { it.id == editingId }
    var creating by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    // UI-1／L-37：Room 事务（ensureSeeded 内的 withTransaction）与 DAO
    // 读移出主线程——reload 改 suspend，直挂 LaunchedEffect，不再包 scope.launch。
    suspend fun reload() {
        withContext(Dispatchers.IO) {
            repository.ensureSeeded()
            scripts = repository.getScripts()
            variables = repository.getVariables()
        }
    }

    LaunchedEffect(refreshTick) { reload() }

    if (editing != null || creating) {
        ScriptEditPage(
            script = editing,
            repository = repository,
            onBack = { editingId = null; creating = false; refreshTick++ },
            onSaved = { editingId = null; creating = false; refreshTick++ },
            onDeleted = { editingId = null; creating = false; refreshTick++ },
        )
        return
    }

    SettingsPageScaffold(
        title = stringResource(R.string.script_title),
        onBack = onBack,
    ) {
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.script_search_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        ScreenComponent.GroupCard {
            Text(
                text = stringResource(R.string.script_prefix_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                value = prefix,
                onValueChange = {
                    prefix = it
                    ScriptPrefs.setCommandViewScriptPrefix(context, it)
                },
                placeholder = { Text(stringResource(R.string.script_prefix_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.script_prefix_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
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
                Text(stringResource(R.string.script_add))
            }
        }
        val shown = if (query.isBlank()) scripts else scripts.filter {
            it.name.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
        }
        if (shown.isEmpty()) {
            Text(
                text = stringResource(
                    if (query.isBlank()) R.string.script_empty else R.string.script_no_match,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        } else {
            ScreenComponent.GroupCard {
                shown.forEach { script ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = script.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = script.content.lineSequence().firstOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                repository.setFavorite(script.id, !script.isFavorite)
                                refreshTick++
                            }
                        }) {
                            Icon(
                                if (script.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = null,
                                tint = if (script.isFavorite) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editingId = script.id }) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                        IconButton(onClick = { confirmDeleteId = script.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ScriptVariablesSection(
            variables = variables,
            onAdd = { key, value, isSecure ->
                scope.launch {
                    repository.insertVariable(key, value, isSecure)
                    refreshTick++
                }
            },
            onDelete = { variable ->
                scope.launch {
                    repository.deleteVariable(variable.id)
                    refreshTick++
                }
            },
        )
    }

    val toDelete = scripts.firstOrNull { it.id == confirmDeleteId }
    if (toDelete != null) {
        SettingsConfirmDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = stringResource(R.string.script_delete),
            text = stringResource(R.string.script_delete_confirm, toDelete.name),
            confirmText = stringResource(R.string.script_delete),
            onConfirm = {
                confirmDeleteId = null
                scope.launch {
                    repository.deleteScript(toDelete.id)
                    refreshTick++
                }
            },
            dismissText = stringResource(R.string.script_cancel),
            confirmColor = MaterialTheme.colorScheme.error,
        )
    }
}

/** 变量管理区（列表页底部）：增删，敏感值打码显示。 */
@Composable
private fun ScriptVariablesSection(
    variables: List<ScriptVariable>,
    onAdd: (String, String, Boolean) -> Unit,
    onDelete: (ScriptVariable) -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var isSecure by rememberSaveable { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.script_variables_label),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.script_variables_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.script_variables_injected_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    ScreenComponent.GroupCard {
        variables.forEach { variable ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = variable.key,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (variable.isSecure) "••••••" else variable.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDelete(variable) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                value = key,
                onValueChange = { key = it },
                label = { Text("key") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                value = value,
                onValueChange = { value = it },
                label = { Text("value") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = isSecure, onCheckedChange = { isSecure = it })
                Text(
                    text = stringResource(R.string.sensitive_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    if (key.isBlank()) return@IconButton
                    onAdd(key.trim(), value, isSecure)
                    key = ""
                    value = ""
                    isSecure = false
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    }
}

/** 脚本编辑页（新建／编辑同一张表）：名称、源码、剪贴板门、标签、试跑。 */
@Composable
private fun ScriptEditPage(
    script: ScriptItem?,
    repository: ScriptRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { IsolatedScriptRunner(context) }
    // UI-2：编辑字段用 rememberSaveable(script?.id)——切另一条脚本时重置，不串草稿。
    var name by rememberSaveable(script?.id) { mutableStateOf(script?.name.orEmpty()) }
    var content by rememberSaveable(script?.id) { mutableStateOf(script?.content ?: ScriptSeeds.DEFAULT_NEW_SCRIPT_CONTENT) }
    var allowPasteboard by rememberSaveable(script?.id) { mutableStateOf(script?.allowPasteboard ?: false) }
    var allTags by remember { mutableStateOf<List<ScriptTag>>(emptyList()) }
    var assignedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var newTagName by rememberSaveable(script?.id) { mutableStateOf("") }
    var showDeleteConfirm by rememberSaveable(script?.id) { mutableStateOf(false) }
    var runInput by rememberSaveable(script?.id) { mutableStateOf("") }
    var runResult by remember { mutableStateOf<ScriptRunResult?>(null) }
    var runError by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    val scriptRunCancelledText = stringResource(R.string.script_run_cancelled)
    val scriptRunErrorFormat = stringResource(R.string.script_run_error)
    val scriptUnnamedText = stringResource(R.string.script_unnamed)

    fun reloadTags() {
        scope.launch {
            allTags = repository.getTags()
            assignedTagIds = script?.let { repository.getTagsForScript(it.id).map { tag -> tag.id }.toSet() }
                .orEmpty()
        }
    }

    LaunchedEffect(script?.id) { reloadTags() }

    fun save() {
        scope.launch {
            if (script == null) {
                repository.insertScript(
                    name = name.ifBlank { scriptUnnamedText },
                    content = content,
                    allowPasteboard = allowPasteboard,
                )
            } else {
                repository.updateScript(
                    script.copy(
                        name = name.ifBlank { script.name },
                        content = content,
                        allowPasteboard = allowPasteboard,
                        date = System.currentTimeMillis(),
                    ),
                )
            }
            onSaved()
        }
    }

    fun runScript() {
        runResult = null
        runError = null
        running = true
        runJob = scope.launch {
            try {
                val pasteboard = if (allowPasteboard) readClipboardText(context) else ""
                // $key 注入：试跑时取库里的全部变量备好（敏感变量在准备
                // 时剔除，不进隔离进程），随入参一起经 Binder 传进去。
                val prepared = ScriptVariableInjection.prepare(
                    repository.getVariables().map {
                        ScriptVariableInjection.VariableEntry(it.key, it.value, it.isSecure)
                    },
                )
                val result = runner.run(
                    source = content,
                    inputs = ScriptInputs(
                        searchText = runInput,
                        selectedText = "",
                        pasteboardContent = pasteboard,
                        variables = prepared.values,
                        skippedVariableLogLines = ScriptVariableInjection.skipLogLines(prepared),
                    ),
                    hostFactory = { logSink, onCompletion ->
                        AndroidScriptHost(
                            context,
                            logSink = logSink,
                            onHttpCompletionListener = onCompletion,
                        )
                    },
                )
                runResult = result
            } catch (e: CancellationException) {
                runError = scriptRunCancelledText
                throw e
            } catch (e: ScriptRunException) {
                runError = scriptRunErrorFormat.format(e.message.orEmpty())
            } catch (e: Exception) {
                runError = scriptRunErrorFormat.format(e.message.orEmpty())
            } finally {
                running = false
                runJob = null
            }
        }
    }

    SettingsPageScaffold(
        title = stringResource(
            if (script == null) R.string.script_add_title else R.string.script_edit_title,
        ),
        onBack = onBack,
    ) {
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.script_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.script_content_hint)) },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        ScreenComponent.GroupCard {
            ScreenComponent.SwitchRow(
                title = stringResource(R.string.script_allow_pasteboard_title),
                checked = allowPasteboard,
                onCheckedChange = { allowPasteboard = it },
            )
            Text(
                text = stringResource(R.string.script_allow_pasteboard_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Text(
                text = stringResource(R.string.script_http_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.script_tags_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            allTags.forEach { tag ->
                val assigned = tag.id in assignedTagIds
                FilterChip(
                    selected = assigned,
                    onClick = {
                        scope.launch {
                            val id = script?.id ?: return@launch
                            if (assigned) repository.unassignTag(id, tag.id)
                            else repository.assignTag(id, tag.id)
                            reloadTags()
                        }
                    },
                    label = { Text(tag.name) },
                    enabled = script != null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                value = newTagName,
                onValueChange = { newTagName = it },
                placeholder = { Text(stringResource(R.string.script_tags_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    if (newTagName.isBlank()) return@TextButton
                    scope.launch {
                        repository.insertTag(newTagName.trim())
                        newTagName = ""
                        reloadTags()
                    }
                },
            ) {
                Text(stringResource(R.string.script_add))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.script_run),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = runInput,
            onValueChange = { runInput = it },
            label = { Text(stringResource(R.string.script_run_input_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { runScript() },
                enabled = !running && content.isNotBlank(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.script_run))
            }
            if (running) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { runJob?.cancel() }) {
                    Text(stringResource(R.string.script_run_cancelled))
                }
            }
        }
        val result = runResult
        if (result != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.script_run_result),
                style = MaterialTheme.typography.titleMedium,
            )
            result.output.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            if (result.logs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.script_run_logs),
                    style = MaterialTheme.typography.titleMedium,
                )
                result.logs.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
            if (result.insertedTexts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.script_run_inserted),
                    style = MaterialTheme.typography.titleMedium,
                )
                result.insertedTexts.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.script_run_elapsed, result.elapsedSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        val error = runError
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { save() },
                enabled = content.isNotBlank(),
            ) {
                Text(stringResource(R.string.script_save))
            }
            Spacer(Modifier.width(8.dp))
            if (script != null) {
                TextButton(
                    onClick = { showDeleteConfirm = true },
                ) {
                    Text(
                        stringResource(R.string.script_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm && script != null) {
        SettingsConfirmDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.script_delete),
            text = stringResource(R.string.script_delete_confirm, script.name),
            confirmText = stringResource(R.string.script_delete),
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    repository.deleteScript(script.id)
                    onDeleted()
                }
            },
            dismissText = stringResource(R.string.script_cancel),
            confirmColor = MaterialTheme.colorScheme.error,
        )
    }
}

/** 读一次系统剪贴板文本（试跑时 $pasteboardContent 的来源；空剪贴板得 ""）。 */
private fun readClipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return ""
    val clip: ClipData = runCatching { cm.primaryClip }.getOrNull() ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0)?.text?.toString().orEmpty()
}
