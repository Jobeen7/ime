package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.VoiceSettingsScreen
import com.jobeen.ime.ui.theme.ImeTheme

class VoiceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeMode = KeyboardManager.Theme.getMode(this)
        val autoDownload = intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false)

        setContent {
            ImeTheme(themeMode = themeMode) {
                VoiceSettingsScreen(
                    onBack = { finish() },
                    autoDownload = autoDownload,
                )
            }
        }
    }

    companion object {
        const val EXTRA_AUTO_DOWNLOAD = "auto_download"
    }
}
