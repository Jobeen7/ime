package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.ui.screen.ClipboardScreen
import com.jobeen.ime.ui.theme.ImeTheme
import com.jobeen.ime.data.manager.KeyboardManager

class ClipboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeMode = KeyboardManager.Theme.getMode(this)

        setContent {
            ImeTheme(themeMode = themeMode) {
                ClipboardScreen(onBack = { finish() })
            }
        }
    }
}
