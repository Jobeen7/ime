package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SwitchRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionSettingsScreen(onBack: () -> Unit) {
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
    var borderless by remember {
        mutableStateOf(CandidateManager.isBorderless(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.prediction_candidates),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup(title = stringResource(R.string.model_prediction)) {
                SwitchRow(
                    title = stringResource(R.string.model_prediction_enable),
                    checked = predictionEnabled,
                    onCheckedChange = {
                        predictionEnabled = it
                        CandidateManager.setPredictionEnabled(context, it)
                    },
                )
            }

            SettingsGroup(title = stringResource(R.string.model_enhance)) {
                SwitchRow(
                    title = stringResource(R.string.model_rerank_enable),
                    checked = rerankEnabled,
                    onCheckedChange = {
                        rerankEnabled = it
                        CandidateManager.setRerankEnabled(context, it)
                    },
                )
            }

            SettingsGroup(title = stringResource(R.string.candidate_layout)) {
                SwitchRow(
                    title = stringResource(R.string.candidate_show_index),
                    checked = showIndex,
                    onCheckedChange = {
                        showIndex = it
                        CandidateManager.setShowIndex(context, it)
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_show_comment),
                    checked = showComment,
                    onCheckedChange = {
                        showComment = it
                        CandidateManager.setShowComment(context, it)
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_borderless),
                    checked = borderless,
                    onCheckedChange = {
                        borderless = it
                        CandidateManager.setBorderless(context, it)
                    },
                )
            }
        }
    }
}
