package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.InputStatsScreen

class InputStatsActivity : ImeActivity() {
    @Composable
    override fun Content() {
        InputStatsScreen(onBack = { finish() })
    }
}
