package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R

/** 备份与还原页的 UI 状态与动作：文件读写与加解密在 BackupActivity 中完成 */
class BackupUiState {
    var busyText: String? by mutableStateOf(null)
    var showCreatePasswordDialog: Boolean by mutableStateOf(false)
    var showRestorePasswordDialog: Boolean by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    state: BackupUiState,
    onBack: () -> Unit,
    onCreateBackup: (password: String) -> Unit,
    onPickRestoreFile: () -> Unit,
    onRestoreWithPassword: (password: String) -> Unit,
) {
    val barFontSize = 18.sp
    SettingsPageScaffold(
        title = stringResource(R.string.backup_title),
        onBack = onBack,
    ) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = LocalMonetCardColor.current,
                ),
            ) {
                Text(
                    text = stringResource(R.string.backup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { state.showCreatePasswordDialog = true },
                enabled = state.busyText == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_create))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onPickRestoreFile,
                enabled = state.busyText == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_restore_from_file))
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.backup_restore_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.busyText?.let {
                Spacer(Modifier.height(20.dp))
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
    }

    if (state.showCreatePasswordDialog) {
        var pw by androidx.compose.runtime.remember { mutableStateOf("") }
        var pw2 by androidx.compose.runtime.remember { mutableStateOf("") }
        var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
        val tooShort = stringResource(R.string.backup_password_too_short)
        val mismatch = stringResource(R.string.backup_password_mismatch)
        SettingsDialog(
            onDismissRequest = { state.showCreatePasswordDialog = false },
            title = stringResource(R.string.backup_create),
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it; error = null },
                        label = { Text(stringResource(R.string.backup_password)) },
                        supportingText = { Text(stringResource(R.string.backup_password_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pw2,
                        onValueChange = { pw2 = it; error = null },
                        label = { Text(stringResource(R.string.backup_password_confirm)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.backup_cancel),
                    onClick = { state.showCreatePasswordDialog = false },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.backup_confirm_create),
                    onClick = {
                        when {
                            pw.length < 8 -> error = tooShort
                            pw != pw2 -> error = mismatch
                            else -> {
                                state.showCreatePasswordDialog = false
                                onCreateBackup(pw)
                            }
                        }
                    },
                ),
            ),
        )
    }

    if (state.showRestorePasswordDialog) {
        var pw by androidx.compose.runtime.remember { mutableStateOf("") }
        SettingsDialog(
            onDismissRequest = { state.showRestorePasswordDialog = false },
            title = stringResource(R.string.backup_restore_from_file),
            body = {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.backup_cancel),
                    onClick = { state.showRestorePasswordDialog = false },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.backup_confirm_restore),
                    onClick = {
                        state.showRestorePasswordDialog = false
                        onRestoreWithPassword(pw)
                    },
                    enabled = pw.isNotEmpty(),
                ),
            ),
        )
    }
}
