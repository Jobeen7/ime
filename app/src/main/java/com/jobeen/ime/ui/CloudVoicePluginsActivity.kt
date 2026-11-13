package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.CloudVoicePluginsScreen

class CloudVoicePluginsActivity : ImeActivity() {
    @Composable
    override fun Content() {
        val targetPluginId = intent.getStringExtra(EXTRA_TARGET_PLUGIN_ID)

        CloudVoicePluginsScreen(
            onBack = { finish() },
            targetPluginId = targetPluginId,
        )
    }

    companion object {
        /** 从服务商对话框点在线服务商带入的目标插件 id（可空＝普通打开）。 */
        const val EXTRA_TARGET_PLUGIN_ID = "target_plugin_id"
    }
}
