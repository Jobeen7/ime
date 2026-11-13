package com.jobeen.ime.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.AboutScreen

class AboutActivity : ImeActivity() {
    @Composable
    override fun Content() {
        AboutScreen(
            onBack = { finish() },
            onOpenLogs = {
                startActivity(Intent(this@AboutActivity, LogActivity::class.java))
            },
            onOpenDiagnostics = {
                startActivity(Intent(this@AboutActivity, SkinDiagnosticsActivity::class.java))
            },
        )
    }
}
