package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.SchemaSettingsScreen

class SchemaSettingsActivity : ImeActivity() {
    @Composable
    override fun Content() {
        SchemaSettingsScreen(
            onBack = { finish() },
        )
    }

}
