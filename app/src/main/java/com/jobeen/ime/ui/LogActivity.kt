package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.LogScreen
import com.jobeen.ime.ui.theme.ImeTheme

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImeTheme(themeMode = KeyboardManager.Theme.getMode(this)) {
                LogScreen(onBack = { finish() })
            }
        }
    }
}
