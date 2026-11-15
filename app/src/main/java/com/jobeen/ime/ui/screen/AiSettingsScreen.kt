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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.ai.AiInstructionContext
import com.jobeen.ime.data.ai.AiInstructionRunner
import com.jobeen.ime.data.ai.AiPrefs
import com.jobeen.ime.data.ai.AiRepository
import com.jobeen.ime.data.ai.AiStreamEvent
import com.jobeen.ime.data.ai.isAllowedBaseUrl
import com.jobeen.ime.data.database.AIProvider
import com.jobeen.ime.data.database.AIProviderModel
import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.database.AiSeeds
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 命令面板管理页（WO52 9.1，设置一级入口 `ai`，标题“命令面板”）：
 * 服务商（自填 API Token）＋指令（直达剪贴板文本＋重置模板）＋
 * 提示词列表＋命令面板前缀配置。
 *
 * 411dp 排布：沿用 PhraseSettingsScreen 的家规——标题＋副标题行
 * （16sp／14sp，省略），开关行“标题＋一行说明＋右侧 Switch”，
 * 文本框全宽，无横向溢出。
 *
 * 已知范围：提示词只列不改（增删改提示词不在 9.1 单子内）；
 * 对话历史只存不聊（无完整对话 UI，数据层＋指令直达已接好）。
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AiRepository.get(context) }
    val runner = remember { AiInstructionRunner(repository) }

    var providers by remember { mutableStateOf<List<AIProvider>>(emptyList()) }
    var models by remember { mutableStateOf<Map<String, AIProviderModel?>>(emptyMap()) }
    var instructions by remember { mutableStateOf(AiSeeds.instructions) }
    var systemPrompts by remember { mutableStateOf(AiSeeds.systemPrompts) }
    var userPrompts by remember { mutableStateOf(AiSeeds.userPrompts) }
    var refreshTick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<AIProvider?>(null) }
    var confirmDelete by remember { mutableStateOf<AIProvider?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // C-L-17：运行前确认——待确认的指令＋点击时快照的剪贴板文本。
    var confirmRun by remember { mutableStateOf<AIInstruction?>(null) }
    var confirmRunInput by remember { mutableStateOf("") }
    var runName by remember { mutableStateOf("") }
    var runResult by remember { mutableStateOf("") }
    var runError by remember { mutableStateOf("") }
    var runErrorEvent by remember { mutableStateOf<AiStreamEvent.Error?>(null) }
    var running by remember { mutableStateOf(false) }
    val aiRunFailedText = stringResource(R.string.ai_run_failed)
    val aiMissingTokenText = stringResource(R.string.ai_missing_token)
    var aiPrefix by remember { mutableStateOf(AiPrefs.getCommandViewAiPrefix(context)) }
    var aiHistoryPrefix by remember { mutableStateOf(AiPrefs.getCommandViewAiHistoryPrefix(context)) }
    var historyLimit by remember { mutableStateOf(AiPrefs.getConversationHistoryLimit(context).toString()) }

    fun reload() {
        scope.launch {
            repository.ensureSeeded()
            val loadedProviders = repository.getProviders()
            val allModels = loadedProviders.flatMap { p ->
                repository.getModelsByProviderId(p.id)
            }
            // 每家服务商只展示默认模型（无默认时取第一个）。
            models = loadedProviders.associate { p ->
                p.id to (allModels.firstOrNull { it.id == p.defaultModelId }
                    ?: allModels.firstOrNull { it.providerId == p.id })
            }
            providers = loadedProviders
            instructions = repository.getInstructions()
            systemPrompts = repository.getSystemPrompts()
            userPrompts = repository.getUserPrompts()
        }
    }

    LaunchedEffect(refreshTick) { reload() }

    fun clipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }

    SettingsPageScaffold(
        title = stringResource(R.string.ai_title),
        onBack = onBack,
    ) {
        ScreenComponent.GroupCard {
            Text(
                text = stringResource(R.string.ai_providers),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            for (provider in providers) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = models[provider.id]?.modelID ?: provider.baseURL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { editing = provider }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    if (provider.builtInCanBeDelete) {
                        IconButton(onClick = { confirmDelete = provider }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
            }
            val newProviderName = stringResource(R.string.ai_new_provider)
            TextButton(onClick = {
                val fresh = AIProvider(
                    id = UUID.randomUUID().toString(),
                    name = newProviderName,
                    baseURL = "https://api.openai.com",
                    apiPath = "v1",
                )
                scope.launch {
                    repository.insertProvider(fresh)
                    refreshTick++
                    editing = fresh
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(text = stringResource(R.string.ai_provider_add))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        editing?.let { provider ->
            ScreenComponent.GroupCard {
                Text(
                    text = stringResource(R.string.ai_provider_edit),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                var name by remember(provider.id) { mutableStateOf(provider.name) }
                var baseUrl by remember(provider.id) { mutableStateOf(provider.baseURL) }
                var apiPath by remember(provider.id) { mutableStateOf(provider.apiPath) }
                var token by remember(provider.id) { mutableStateOf(provider.apiToken) }
                var timeout by remember(provider.id) { mutableStateOf(provider.requestTimeout.toString()) }
                var temperature by remember(provider.id) { mutableStateOf(provider.temperature?.toString().orEmpty()) }
                var enabled by remember(provider.id) { mutableStateOf(provider.isEnabled) }
                var modelId by remember(provider.id) { mutableStateOf(models[provider.id]?.modelID.orEmpty()) }
                var baseUrlError by remember(provider.id) { mutableStateOf(false) }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ai_provider_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it; baseUrlError = false },
                    label = { Text(stringResource(R.string.ai_provider_base_url)) },
                    isError = baseUrlError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (baseUrlError) {
                    Text(
                        text = stringResource(R.string.ai_base_url_scheme_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = apiPath, onValueChange = { apiPath = it },
                    label = { Text(stringResource(R.string.ai_provider_api_path)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(stringResource(R.string.ai_provider_token)) },
                    placeholder = { Text(stringResource(R.string.ai_provider_token_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.ai_provider_token_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelId, onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.ai_provider_model)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeout, onValueChange = { timeout = it },
                    label = { Text(stringResource(R.string.ai_provider_timeout)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = temperature, onValueChange = { temperature = it },
                    label = { Text(stringResource(R.string.ai_provider_temperature)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                ScreenComponent.SwitchRow(
                    title = stringResource(R.string.ai_provider_enabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { editing = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = {
                        // C-L-15：保存时校验 scheme——https 放行，http
                        // 只放行局域网；不合规则拒存并在地址框下标红提示。
                        if (!isAllowedBaseUrl(baseUrl.trim())) {
                            baseUrlError = true
                            return@TextButton
                        }
                        scope.launch {
                            val saved = provider.copy(
                                name = name.trim().ifBlank { provider.name },
                                baseURL = baseUrl.trim(),
                                apiPath = apiPath.trim(),
                                apiToken = token,
                                requestTimeout = timeout.toDoubleOrNull() ?: 60.0,
                                temperature = temperature.toDoubleOrNull(),
                                isEnabled = enabled,
                            )
                            repository.updateProvider(saved)
                            val trimmedModel = modelId.trim()
                            if (trimmedModel.isNotEmpty()) {
                                val existing = models[provider.id]
                                if (existing != null) {
                                    repository.updateModel(existing.copy(modelID = trimmedModel, displayName = trimmedModel))
                                } else {
                                    val row = AIProviderModel(
                                        id = UUID.randomUUID().toString(),
                                        providerId = provider.id,
                                        displayName = trimmedModel,
                                        modelID = trimmedModel,
                                    )
                                    repository.insertModel(row)
                                    repository.updateProvider(saved.copy(defaultModelId = row.id))
                                }
                            }
                            editing = null
                            refreshTick++
                        }
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        ScreenComponent.GroupCard {
            Text(
                text = stringResource(R.string.ai_instructions),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            for (instruction in instructions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = instruction.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = instruction.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = {
                            if (running) return@TextButton
                            // C-L-17：运行先弹确认框（目标服务商名＋将发送
                            // 内容前 200 字），确认后才发；剪贴板文本在
                            // 点击时快照，确认期间变了也不改已确认的内容。
                            confirmRunInput = clipboardText()
                            confirmRun = instruction
                        },
                        enabled = !running,
                    ) {
                        Text(stringResource(R.string.ai_run))
                    }
                }
            }
            if (runName.isNotEmpty()) {
                Text(
                    text = runName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 错误文案在组合作用域派生（collect 体内调不了 stringResource）。
                val runErrorText = runErrorEvent?.let { ev ->
                    ev.messageRes?.let { stringResource(it) }
                        ?: if (ev.code == "missing_token") aiMissingTokenText else ev.message
                } ?: runError
                if (runErrorText.isNotEmpty()) {
                    Text(
                        text = runErrorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (runResult.isNotEmpty()) {
                    Text(
                        text = runResult,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("ai", runResult))
                    }) {
                        Text(stringResource(R.string.ai_copy_result))
                    }
                } else if (running) {
                    Text(
                        text = stringResource(R.string.ai_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { confirmReset = true }) {
                Text(stringResource(R.string.ai_reset_instructions))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ScreenComponent.GroupCard {
            Text(
                text = stringResource(R.string.ai_system_prompts),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            for (prompt in systemPrompts) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(text = prompt.name, style = MaterialTheme.typography.bodyLarge)
                    if (prompt.purpose.isNotEmpty()) {
                        Text(
                            text = prompt.purpose,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.ai_user_prompts),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            for (prompt in userPrompts) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(text = prompt.name, style = MaterialTheme.typography.bodyLarge)
                    if (prompt.purpose.isNotEmpty()) {
                        Text(
                            text = prompt.purpose,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ScreenComponent.GroupCard {
            Text(
                text = stringResource(R.string.ai_prefix_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = aiPrefix,
                onValueChange = {
                    aiPrefix = it
                    AiPrefs.setCommandViewAiPrefix(context, it)
                },
                label = { Text(stringResource(R.string.ai_prefix)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = aiHistoryPrefix,
                onValueChange = {
                    aiHistoryPrefix = it
                    AiPrefs.setCommandViewAiHistoryPrefix(context, it)
                },
                label = { Text(stringResource(R.string.ai_history_prefix)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = historyLimit,
                onValueChange = {
                    historyLimit = it.filter { c -> c.isDigit() }
                    historyLimit.toIntOrNull()?.let { n ->
                        AiPrefs.setConversationHistoryLimit(context, n)
                    }
                },
                label = { Text(stringResource(R.string.ai_history_limit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    confirmDelete?.let { provider ->
        SettingsConfirmDialog(
            onDismissRequest = { confirmDelete = null },
            title = stringResource(R.string.ai_provider_delete),
            text = stringResource(R.string.ai_provider_delete_confirm, provider.name),
            confirmText = stringResource(R.string.delete),
            onConfirm = {
                scope.launch {
                    repository.deleteProvider(provider.id)
                    confirmDelete = null
                    refreshTick++
                }
            },
            dismissText = stringResource(R.string.cancel),
        )
    }
    if (confirmReset) {
        SettingsConfirmDialog(
            onDismissRequest = { confirmReset = false },
            title = stringResource(R.string.ai_reset_instructions),
            text = stringResource(R.string.ai_reset_instructions_confirm),
            confirmText = stringResource(R.string.clipboard_confirm),
            onConfirm = {
                scope.launch {
                    repository.resetInstructionsToSeed()
                    confirmReset = false
                    refreshTick++
                }
            },
            dismissText = stringResource(R.string.cancel),
        )
    }
    confirmRun?.let { instruction ->
        val targetProvider = providers.firstOrNull { it.id == instruction.providerID }
        SettingsConfirmDialog(
            onDismissRequest = { confirmRun = null },
            title = stringResource(R.string.ai_run),
            text = stringResource(
                R.string.ai_run_confirm,
                targetProvider?.name.orEmpty(),
                confirmRunInput.take(200),
            ),
            confirmText = stringResource(R.string.ai_run),
            onConfirm = {
                val text = confirmRunInput
                confirmRun = null
                runName = instruction.name
                runResult = ""
                runError = ""
                runErrorEvent = null
                running = true
                scope.launch {
                    val run = runner.run(
                        instructionId = instruction.id,
                        input = text,
                        context = AiInstructionContext(
                            pasteboardContent = text,
                        ),
                    )
                    if (run == null) {
                        runError = aiRunFailedText
                        running = false
                        return@launch
                    }
                    val acc = StringBuilder()
                    run.events.collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> {
                                acc.append(event.text)
                                runResult = acc.toString()
                            }
                            is AiStreamEvent.Error -> {
                                runErrorEvent = event
                            }
                            else -> Unit
                        }
                    }
                    running = false
                }
            },
            dismissText = stringResource(R.string.cancel),
        )
    }
}
