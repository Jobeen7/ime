package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.jobeen.ime.ui.screen.ScreenComponent.SliderRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var maxEntries by remember { mutableFloatStateOf(ClipboardManager.getMaxEntries(context).toFloat()) }
    var retentionDays by remember { mutableFloatStateOf(ClipboardManager.getRetentionDays(context).toFloat()) }
    var listeningEnabled by remember {
        mutableStateOf(ClipboardManager.isListeningEnabled(context))
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
            }


            SettingsGroup(title = stringResource(R.string.clipboard_history)) {
                Spacer(Modifier.height(4.dp))
                SliderRow(
                    title = stringResource(R.string.clipboard_max_entries),
                    value = maxEntries,
                    valueLabel = "${maxEntries.toInt()} 条",
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
                    valueLabel = "${retentionDays.toInt()} 天",
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
                )
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
