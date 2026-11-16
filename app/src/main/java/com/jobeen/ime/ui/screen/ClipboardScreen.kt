package com.jobeen.ime.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.ClipboardEntryLocator
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.jobeen.ime.ui.screen.ScreenComponent.SliderRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(onBack: () -> Unit, initialClipboardId: Long = -1L) {
    val context = LocalContext.current

    // clipboard?id= 落点（WO52 4.2 遗留项，镜像 phrase?id=）：
    // 只带 id 进页时才读历史并定位；不带 id 时本页与原来完全一致。
    var deepLinkEntries by remember { mutableStateOf<List<ClipboardManager.Entry>?>(null) }
    val targetIndex = deepLinkEntries
        ?.let { ClipboardEntryLocator.indexOf(it, initialClipboardId) } ?: -1
    LaunchedEffect(initialClipboardId) {
        if (initialClipboardId > 0) {
            deepLinkEntries = ClipboardManager.getEntriesOrNull(context)
        }
    }

    var maxEntries by remember { mutableFloatStateOf(ClipboardManager.getMaxEntries(context).toFloat()) }
    var retentionDays by remember { mutableFloatStateOf(ClipboardManager.getRetentionDays(context).toFloat()) }
    var listeningEnabled by remember {
        mutableStateOf(ClipboardManager.isListeningEnabled(context))
    }
    var promptEnabled by remember {
        mutableStateOf(ClipboardManager.isPromptEnabled(context))
    }
    var autoExtractCode by remember {
        mutableStateOf(ClipboardManager.isAutoExtractCodeEnabled(context))
    }
    var dedupEnabled by remember {
        mutableStateOf(ClipboardManager.isDedupEnabled(context))
    }
    var outputRule by remember {
        mutableStateOf(ClipboardManager.getOutputRule(context))
    }
    var returnAfterPaste by remember {
        mutableStateOf(ClipboardManager.isReturnAfterPasteEnabled(context))
    }
    var showOutputRuleDialog by remember { mutableStateOf(false) }

    SettingsPageScaffold(
        title = stringResource(R.string.clipboard_manager),
        onBack = onBack,
        // 目标行经 rowAnchor 注册同名锚点，脚手架等锚点出现后滚到该条。
        focusTitle = if (targetIndex >= 0) "clipboard_entry_$initialClipboardId" else null,
    ) {
        SettingsGroup(title = stringResource(R.string.clipboard_listening)) {
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = stringResource(R.string.clipboard_listening),
                    checked = listeningEnabled,
                    onCheckedChange = {
                        listeningEnabled = it
                        ClipboardManager.setListeningEnabled(context, it)
                    },
                )
                Text(
                    text = stringResource(R.string.clipboard_listening_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    title = stringResource(R.string.clipboard_prompt_bar),
                    checked = promptEnabled,
                    onCheckedChange = {
                        promptEnabled = it
                        ClipboardManager.setPromptEnabled(context, it)
                    },
                    enabled = listeningEnabled,
                    showDivider = true,
                )
                if (!listeningEnabled) {
                    Text(
                        text = stringResource(R.string.clipboard_banner_requires_listener),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }


            SettingsGroup(title = stringResource(R.string.clipboard_history)) {
                Spacer(Modifier.height(4.dp))
                SliderRow(
                    title = stringResource(R.string.clipboard_max_entries),
                    value = maxEntries,
                    defaultValue = 100f,
                    valueLabel = stringResource(R.string.clipboard_max_entries_value, maxEntries.toInt()),
                    range = 20f..100f,
                    // 拖动只更新本地显示：setMaxEntries 会立即按新上限裁剪历史，
                    // 逐帧调用会把途经低点的裁剪全打出去（拖回高位也恢复不了）
                    onValueChange = { maxEntries = it },
                    onValueChangeFinished = {
                        ClipboardManager.setMaxEntries(context, maxEntries.toInt())
                    },
                    showDivider = true,
                )
                SliderRow(
                    title = stringResource(R.string.clipboard_retention_days),
                    value = retentionDays,
                    defaultValue = 30f,
                    valueLabel = stringResource(R.string.clipboard_retention_days_value, retentionDays.toInt()),
                    range = 1f..365f,
                    onValueChange = {
                        retentionDays = it
                        ClipboardManager.setRetentionDays(context, it.toInt())
                    },
                )
            }


            SettingsGroup(title = stringResource(R.string.clipboard_rules)) {
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = stringResource(R.string.clipboard_dedup),
                    checked = dedupEnabled,
                    onCheckedChange = {
                        dedupEnabled = it
                        ClipboardManager.setDedupEnabled(context, it)
                    },
                    showDivider = true,
                )
                NavRow(
                    title = stringResource(R.string.clipboard_output_rule),
                    subtitle = when (outputRule) {
                        ClipboardManager.OutputRule.AS_IS ->
                            stringResource(R.string.clipboard_output_as_is)
                        ClipboardManager.OutputRule.TRIM ->
                            stringResource(R.string.clipboard_output_trim)
                        ClipboardManager.OutputRule.NEWLINE_TO_SPACE ->
                            stringResource(R.string.clipboard_output_newline)
                    },
                    onClick = { showOutputRuleDialog = true },
                )
                SwitchRow(
                    title = stringResource(R.string.clipboard_return_after_paste),
                    checked = returnAfterPaste,
                    onCheckedChange = {
                        returnAfterPaste = it
                        ClipboardManager.setReturnAfterPasteEnabled(context, it)
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.clipboard_auto_extract_code),
                    checked = autoExtractCode,
                    onCheckedChange = {
                        autoExtractCode = it
                        ClipboardManager.setAutoExtractCodeEnabled(context, it)
                    },
                    // 前置提示（WO52 5.2 依赖链：监听 → 提示条 → 自动提取）：
                    // 任一前置未开时开关不可用，并显示缺哪一项。
                    enabled = listeningEnabled && promptEnabled,
                )
                Text(
                    text = stringResource(R.string.clipboard_auto_extract_code_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!listeningEnabled) {
                    Text(
                        text = stringResource(R.string.clipboard_banner_requires_listener),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!promptEnabled) {
                    Text(
                        text = stringResource(R.string.clipboard_auto_extract_code_requires_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.clipboard_dedup_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.clipboard_return_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // clipboard?id= 落点：只带 id 进页时出现。列表顺序与键盘
            // 面板同一来源（getEntriesOrNull）；目标行高亮＋滚到该条；
            // 读完仍无该 id 时给明确提示、不崩溃；读库失败（null）
            // 与“真无此条”不区分——两者都不是可定位的条目。
            if (initialClipboardId > 0) {
                val entries = deepLinkEntries
                if (entries != null) {
                    SettingsGroup(title = stringResource(R.string.clipboard_history)) {
                        Spacer(Modifier.height(4.dp))
                        if (targetIndex < 0) {
                            Text(
                                text = stringResource(R.string.clipboard_entry_not_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            entries.forEachIndexed { index, entry ->
                                val isTarget = index == targetIndex
                                Text(
                                    text = entry.text,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = if (isTarget) {
                                        Modifier
                                            .fillMaxWidth()
                                            .rowAnchor("clipboard_entry_$initialClipboardId")
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                MaterialTheme.shapes.medium,
                                            )
                                            .padding(12.dp)
                                    } else {
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 6.dp)
                                    },
                                )
                            }
                        }
                    }
                }
            }
    }

    if (showOutputRuleDialog) {
        val rules = listOf(
            ClipboardManager.OutputRule.AS_IS,
            ClipboardManager.OutputRule.TRIM,
            ClipboardManager.OutputRule.NEWLINE_TO_SPACE,
        )
        val labels = listOf(
            stringResource(R.string.clipboard_output_as_is),
            stringResource(R.string.clipboard_output_trim),
            stringResource(R.string.clipboard_output_newline),
        )
        SingleChoiceDialog(
            title = stringResource(R.string.clipboard_output_rule),
            options = labels,
            selectedIndex = rules.indexOf(outputRule),
            onSelect = { index ->
                outputRule = rules[index]
                ClipboardManager.setOutputRule(context, rules[index])
                showOutputRuleDialog = false
            },
            onDismiss = { showOutputRuleDialog = false },
        )
    }
}
