package com.jobeen.ime.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.VoiceSettingsScreen

class VoiceSettingsActivity : ImeActivity() {
    @Composable
    override fun Content() {
        val autoDownload = intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false)

        VoiceSettingsScreen(
            onBack = { finish() },
            autoDownload = autoDownload,
            onOpenCloudVoicePlugins = { pluginId ->
                startActivity(
                    Intent(this, CloudVoicePluginsActivity::class.java).apply {
                        if (pluginId != null) {
                            putExtra(CloudVoicePluginsActivity.EXTRA_TARGET_PLUGIN_ID, pluginId)
                        }
                    },
                )
            },
        )
    }

    companion object {
        const val EXTRA_AUTO_DOWNLOAD = "auto_download"
    }
}
