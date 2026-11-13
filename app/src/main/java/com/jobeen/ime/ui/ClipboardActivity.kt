package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.ClipboardScreen

class ClipboardActivity : ImeActivity() {
    @Composable
    override fun Content() {
        ClipboardScreen(onBack = { finish() })
    }
}
