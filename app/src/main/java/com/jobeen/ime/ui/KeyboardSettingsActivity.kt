package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.KeyboardSettingsScreen

class KeyboardSettingsActivity : ImeActivity() {
    override val useEdgeToEdge = false

    @Composable
    override fun Content() {
        KeyboardSettingsScreen(
            onBack = { finish() },
        )
    }
}
