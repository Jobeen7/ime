package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.LogScreen

class LogActivity : ImeActivity() {
    @Composable
    override fun Content() {
        LogScreen(onBack = { finish() })
    }
}
