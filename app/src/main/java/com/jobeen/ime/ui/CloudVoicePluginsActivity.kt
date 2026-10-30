package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.ui.screen.CloudVoicePluginsScreen
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.AppAppearanceMode
import com.jobeen.ime.ui.theme.ImeTheme

class CloudVoicePluginsActivity : ComponentActivity() {
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
        val targetPluginId = intent.getStringExtra(EXTRA_TARGET_PLUGIN_ID)

        setContent {
            ImeTheme(themeMode = themeMode, dynamicColor = AppAppearance.useDynamicColor(this)) {
                CloudVoicePluginsScreen(
                    onBack = { finish() },
                    targetPluginId = targetPluginId,
                )
            }
        }
    }

    companion object {
        /** 从服务商对话框点在线服务商带入的目标插件 id（可空＝普通打开）。 */
        const val EXTRA_TARGET_PLUGIN_ID = "target_plugin_id"
    }
}
