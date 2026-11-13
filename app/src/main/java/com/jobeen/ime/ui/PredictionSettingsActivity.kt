package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.CandidateSettingsScreen

class PredictionSettingsActivity : ImeActivity() {
    @Composable
    override fun Content() {
        CandidateSettingsScreen(onBack = { finish() })
    }
}
