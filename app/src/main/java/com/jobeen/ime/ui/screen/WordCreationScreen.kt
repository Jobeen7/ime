// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog

/** 独立造词页状态（2026-10-29 批量第 5 项）：词语＋编码 → 导入当前词典 */
class WordCreationUiState {
    var word by mutableStateOf("")
    var code by mutableStateOf("")
    var dicts by mutableStateOf<List<String>?>(null)
    var selectedDict by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var result by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordCreationScreen(
    state: WordCreationUiState,
    onBack: () -> Unit,
    onSelectDict: (String) -> Unit,
    onImport: () -> Unit,
) {
    var showDictDialog by remember { mutableStateOf(false) }
    SettingsPageScaffold(
        title = stringResource(R.string.word_creation_title),
        onBack = onBack,
    ) {
            GroupCard {
                OutlinedTextField(
                    value = state.word,
                    onValueChange = { state.word = it },
                    label = { Text(stringResource(R.string.word_creation_word)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = state.code,
                    onValueChange = { state.code = it },
                    label = { Text(stringResource(R.string.word_creation_code)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                when (val dicts = state.dicts) {
                    null -> CircularProgressIndicator(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    else -> if (dicts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.word_creation_no_dict),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    } else if (dicts.size > 1) {
                        NavRow(
                            title = state.selectedDict.orEmpty(),
                            subtitle = stringResource(R.string.user_dict_switch_dict),
                            onClick = { if (!state.busy) showDictDialog = true },
                        )
                    } else {
                        Text(
                            text = state.selectedDict.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
                Button(
                    onClick = onImport,
                    enabled = !state.busy &&
                        state.word.isNotBlank() &&
                        state.code.isNotBlank() &&
                        state.selectedDict != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.word_creation_import))
                }
            }
            state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            state.result?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
    }

    if (showDictDialog) {
        val dicts = state.dicts.orEmpty()
        SingleChoiceDialog(
            title = stringResource(R.string.word_creation_dict),
            options = dicts,
            selectedIndex = dicts.indexOf(state.selectedDict),
            onSelect = { index ->
                onSelectDict(dicts[index])
                showDictDialog = false
            },
            onDismiss = { showDictDialog = false },
        )
    }
}
