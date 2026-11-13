package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.SchemeUpdateScreen

class SchemeUpdateActivity : ImeActivity() {
    @Composable
    override fun Content() {
        SchemeUpdateScreen(onBack = { finish() })
    }
}
