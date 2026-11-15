package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.base.webserver.WebServerService

/**
 * Wi-Fi 传输设置页（WO52 4.4）：密码、启停、局域网地址。
 * 密码只存 SHA-256（见 WifiTransferAuth），未设密码时服务拒绝启动。
 */
@Composable
fun WebServerScreen(
    onBack: () -> Unit,
    hasPassword: Boolean,
    onSavePassword: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val state by WebServerService.state.collectAsState()
    var password by remember { mutableStateOf("") }

    SettingsPageScaffold(
        title = stringResource(R.string.web_server_title),
        onBack = onBack,
    ) {
        ScreenComponent.GroupCard {
            Text(
                text = stringResource(R.string.web_server_password_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.web_server_password_label)) },
                placeholder = {
                    Text(
                        if (hasPassword) {
                            stringResource(R.string.web_server_password_set)
                        } else {
                            stringResource(R.string.web_server_password_hint)
                        },
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onSavePassword(password); password = "" },
                enabled = password.length >= 4,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.web_server_password_save))
            }
            Text(
                text = stringResource(R.string.web_server_password_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        ScreenComponent.GroupCard {
            Text(
                text = stringResource(R.string.web_server_service_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (state.running) {
                Text(
                    text = stringResource(R.string.web_server_running),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                state.address?.let { address ->
                    SelectionContainer {
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.web_server_stop))
                }
            } else {
                Text(
                    text = stringResource(
                        if (hasPassword) {
                            R.string.web_server_stopped
                        } else {
                            R.string.web_server_stopped_no_password
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onStart,
                    enabled = hasPassword,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.web_server_start))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.web_server_usage_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
