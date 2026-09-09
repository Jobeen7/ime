package com.ninthsoft.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.ui.screen.CandidateSettingsScreen
import com.ninthsoft.ime.ui.theme.ImeTheme

class PredictionSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeMode = KeyboardManager.Theme.getMode(this)

        setContent {
            ImeTheme(themeMode = themeMode) {
                CandidateSettingsScreen(onBack = { finish() })
            }
        }
    }
}
