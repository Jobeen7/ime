package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.KeyboardSettingsScreen
import com.jobeen.ime.ui.theme.ImeTheme

class KeyboardSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode = remember { mutableIntStateOf(KeyboardManager.Theme.getMode(this)) }
            ImeTheme(themeMode = themeMode.intValue) {
                KeyboardSettingsScreen(onBack = { finish() })
            }
        }
    }
}
