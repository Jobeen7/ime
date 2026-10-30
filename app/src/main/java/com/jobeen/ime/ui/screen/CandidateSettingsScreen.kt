package com.jobeen.ime.ui.screen

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SliderRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidateSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var predictionEnabled by remember {
        mutableStateOf(CandidateManager.isPredictionEnabled(context))
    }
    var rerankEnabled by remember {
        mutableStateOf(CandidateManager.isRerankEnabled(context))
    }
    var showIndex by remember {
        mutableStateOf(CandidateManager.isShowIndex(context))
    }
    var showComment by remember {
        mutableStateOf(CandidateManager.isShowComment(context))
    }
    var borderEnabled by remember {
        mutableStateOf(CandidateManager.isBorderEnabled(context))
    }
    var lineLimitEnabled by remember {
        mutableStateOf(CandidateManager.isLineLimitEnabled(context))
    }
    var maxLines by remember {
        mutableFloatStateOf(CandidateManager.getMaxLines(context).toFloat())
    }
    var autoShrinkEnabled by remember {
        mutableStateOf(CandidateManager.isAutoShrinkEnabled(context))
    }
    var fullDisplayEnabled by remember {
        mutableStateOf(CandidateManager.isFullDisplayEnabled(context))
    }
    var inlineSuggestionEnabled by remember {
        mutableStateOf(CandidateManager.isInlineSuggestionEnabled(context))
    }

    SettingsPageScaffold(
        title = stringResource(R.string.prediction_candidates),
        onBack = onBack,
    ) {
        // 候选页分组（2026-10-29 批量第 3 项，按参考分类重组）：
            // 候选内容（预测与增强）＋窗口显示（序号、注释、边框）。
            SettingsGroup(title = stringResource(R.string.candidate_content)) {
                SwitchRow(
                    title = stringResource(R.string.model_prediction_enable),
                    checked = predictionEnabled,
                    onCheckedChange = {
                        predictionEnabled = it
                        CandidateManager.setPredictionEnabled(context, it)
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.model_rerank_enable),
                    checked = rerankEnabled,
                    onCheckedChange = {
                        rerankEnabled = it
                        CandidateManager.setRerankEnabled(context, it)
                    },
                )
            }

            SettingsGroup(title = stringResource(R.string.candidate_window)) {
                SwitchRow(
                    title = stringResource(R.string.candidate_show_index),
                    checked = showIndex,
                    onCheckedChange = {
                        showIndex = it
                        CandidateManager.setShowIndex(context, it)
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_show_comment),
                    checked = showComment,
                    onCheckedChange = {
                        showComment = it
                        CandidateManager.setShowComment(context, it)
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_border),
                    checked = borderEnabled,
                    onCheckedChange = {
                        borderEnabled = it
                        CandidateManager.setBorderEnabled(context, it)
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_line_limit),
                    checked = lineLimitEnabled,
                    onCheckedChange = {
                        lineLimitEnabled = it
                        CandidateManager.setLineLimitEnabled(context, it)
                    },
                    showDivider = true,
                )
                SliderRow(
                    title = stringResource(R.string.candidate_max_lines),
                    value = maxLines,
                    valueLabel = "${maxLines.toInt()} 行",
                    range = 1f..10f,
                    enabled = lineLimitEnabled,
                    onValueChange = {
                        maxLines = it
                        CandidateManager.setMaxLines(context, it.toInt())
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_auto_shrink),
                    checked = autoShrinkEnabled,
                    onCheckedChange = {
                        autoShrinkEnabled = it
                        CandidateManager.setAutoShrinkEnabled(context, it)
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_full_display),
                    checked = fullDisplayEnabled,
                    onCheckedChange = {
                        fullDisplayEnabled = it
                        CandidateManager.setFullDisplayEnabled(context, it)
                    },
                    showDivider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_inline_suggestion),
                    checked = inlineSuggestionEnabled,
                    onCheckedChange = {
                        inlineSuggestionEnabled = it
                        CandidateManager.setInlineSuggestionEnabled(context, it)
                    },
                )
                Text(
                    text = stringResource(R.string.candidate_inline_suggestion_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}
