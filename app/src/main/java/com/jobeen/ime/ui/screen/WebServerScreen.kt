package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.base.webserver.WebServerService

/**
 * Wi-Fi 传输页（2026-11-16 简化，按元书 iOS 模式）：
 * 只留三样——地址条（点一下复制）＋手机-WiFi-电脑图＋5 条一行说明。
 * 无启停按钮：进页即运行、离页即停，由 WebServerActivity 的
 * onResumeExtra/onPause 触发。
 * 删掉的：密码设置卡、证书指纹（含换发按钮）、“通知已关闭”提示、
 * 底部整段使用说明。传输层（HTTPS／密码／证书／指纹）一律不动；
 * 未设密码时服务拒绝启动是传输层原有行为（见 WebServerService.onCreate），
 * 本页只显示“服务未启动”。
 */
@Composable
fun WebServerScreen(
    onBack: () -> Unit,
) {
    val state by WebServerService.state.collectAsState()
    val context = LocalContext.current
    val address = state.address
    val addressCopiedText = stringResource(R.string.wifi_address_copied)

    SettingsPageScaffold(
        title = stringResource(R.string.web_server_title),
        onBack = onBack,
    ) {
        ScreenComponent.GroupCard {
            if (address != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("wifi-address", address))
                            ToastUtil.showToast(context, addressCopiedText)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                    )
                }
                Text(
                    text = stringResource(R.string.wifi_address_copy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.web_server_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        // 手机-WiFi-电脑图（装饰性示意，无文案）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = Icons.Filled.Wifi,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = Icons.Filled.Computer,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }
        ScreenComponent.GroupCard {
            // 5 条一行说明（每条 maxLines=1，大字号下截断不断行）。
            listOf(
                stringResource(R.string.wifi_usage_1),
                stringResource(R.string.wifi_usage_2),
                stringResource(R.string.wifi_usage_3),
                stringResource(R.string.wifi_usage_4),
                stringResource(R.string.wifi_usage_5),
            ).forEachIndexed { index, line ->
                Text(
                    text = "${index + 1}. $line",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}
