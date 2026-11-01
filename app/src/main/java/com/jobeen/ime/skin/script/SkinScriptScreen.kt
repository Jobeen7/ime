package com.jobeen.ime.skin.script

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-level script management UI: list scripts with add/remove/toggle,
 * plus a test run (onEvent "test") to prove a script loads and runs.
 *
 * This screen manages scripts ONLY. It does not wire any key action to
 * any script: no runScript/openScript/#toggleScriptView action exists
 * here, and test runs go through [SkinScriptRunner.runScript] on a
 * background dispatcher (never the main thread — see the runner doc).
 * The runner result value is displayed as opaque data, not interpreted
 * as an action; interpretation belongs to the later wiring wave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinScriptScreen(
    store: SkinScriptStore,
    onBack: () -> Unit,
) {
    var scripts by remember { mutableStateOf(store.list()) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newSource by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var runResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    val runner = remember { SkinScriptRunner(store) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scripts = store.list()
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skin_script_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = newId,
                    onValueChange = { newId = it },
                    label = { Text(stringResource(R.string.skin_script_id)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.skin_script_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newSource,
                    onValueChange = { newSource = it },
                    label = { Text(stringResource(R.string.skin_script_source)) },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        when (val result = store.add(newId, newName, newSource)) {
                            is SkinScriptAddResult.Added -> {
                                addError = null
                                newId = ""
                                newName = ""
                                newSource = ""
                                refresh()
                            }
                            else -> addError = addFailureText(result)
                        }
                    },
                ) {
                    Text(stringResource(R.string.skin_script_add))
                }
                addError?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.skin_script_add_failed, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (scripts.isEmpty()) {
                item { Text(stringResource(R.string.skin_script_empty)) }
            }
            items(scripts, key = { it.id }) { meta ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            meta.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.skin_script_enabled))
                        Switch(
                            checked = meta.enabled,
                            onCheckedChange = { enabled ->
                                store.setEnabled(meta.id, enabled)
                                refresh()
                            },
                        )
                    }
                    Text(meta.id, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.Default) {
                                        runner.runScript(meta.id, "test")
                                    }
                                    runResult = meta.id to runResultText(result)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.skin_script_run_test))
                        }
                        TextButton(
                            onClick = {
                                store.remove(meta.id)
                                if (runResult?.first == meta.id) runResult = null
                                refresh()
                            },
                        ) {
                            Text(stringResource(R.string.skin_script_remove))
                        }
                    }
                    if (runResult?.first == meta.id) {
                        Text(
                            stringResource(
                                R.string.skin_script_run_result,
                                runResult?.second ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun addFailureText(result: SkinScriptAddResult): String = when (result) {
    is SkinScriptAddResult.Added -> ""
    SkinScriptAddResult.InvalidId -> "ID 不合法"
    SkinScriptAddResult.InvalidName -> "名称不合法"
    SkinScriptAddResult.EmptySource -> "源码为空"
    SkinScriptAddResult.TooLarge -> "源码过大"
    SkinScriptAddResult.AlreadyExists -> "ID 已存在"
    SkinScriptAddResult.WriteFailed -> "写入失败"
}

private fun runResultText(result: SkinScriptResult): String = when (result) {
    is SkinScriptResult.Ok -> result.value ?: "(无返回值)"
    is SkinScriptResult.Skipped -> "未运行：${result.reason}"
    is SkinScriptResult.Failed -> "失败：${result.failure}"
}
