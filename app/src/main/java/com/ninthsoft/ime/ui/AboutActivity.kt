package com.ninthsoft.ime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.ui.screen.AboutScreen
import com.ninthsoft.ime.ui.theme.ImeTheme

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeMode = KeyboardManager.Theme.getMode(this)

        setContent {
            ImeTheme(themeMode = themeMode) {
                AboutScreen(
                    onBack = { finish() },
                    onOpenLogs = {
                        startActivity(Intent(this@AboutActivity, LogActivity::class.java))
                    },
                )
            }
        }
    }
}
