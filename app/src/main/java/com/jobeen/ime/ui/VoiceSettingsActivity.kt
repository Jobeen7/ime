package com.jobeen.ime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.VoiceSettingsScreen
import com.jobeen.ime.ui.theme.ImeTheme
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.AppAppearanceMode

class VoiceSettingsActivity : ComponentActivity() {
    // 返回即刷新：创建时记下的外观模式；onResume 比对，变了才重建。
    private var createdAppearanceMode: AppAppearanceMode? = null
    /**
     * 返回即刷新：从外观页返回时，若应用外观模式已变则重建本页，
     * 主题立即生效；模式未变不重建；重建后新模式即记录值，不循环。
     */
    override fun onResume() {
        super.onResume()
        val recordedAppearanceMode = createdAppearanceMode
        if (recordedAppearanceMode != null &&
            AppAppearance.appearanceModeChanged(recordedAppearanceMode, AppAppearance.getMode(this))
        ) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAppearanceMode = AppAppearance.getMode(this)
        enableEdgeToEdge()

        val themeMode = AppAppearance.themeMode(this)
        val autoDownload = intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false)

        setContent {
            ImeTheme(themeMode = themeMode, dynamicColor = AppAppearance.useDynamicColor(this)) {
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
        }
    }

    companion object {
        const val EXTRA_AUTO_DOWNLOAD = "auto_download"
    }
}
