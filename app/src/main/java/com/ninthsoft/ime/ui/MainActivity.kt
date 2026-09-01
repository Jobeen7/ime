package com.ninthsoft.ime.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.ui.screen.MainScreen
import com.ninthsoft.ime.ui.theme.ImeTheme

class MainActivity : ComponentActivity() {

    private val initLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!isImeConfigured()) {
            setupImeLauncher.launch(Intent(this, SetupActivity::class.java))
        }
    }

    private val setupImeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initState = ImeApplication.getInstance().state.value
        if (initState != ImeApplication.AppState.Finished) {
            initLauncher.launch(Intent(this, InitActivity::class.java))
        } else if (!isImeConfigured()) {
            setupImeLauncher.launch(Intent(this, SetupActivity::class.java))
        }

        setContent {
            var themeMode by remember { mutableIntStateOf(KeyboardManager.Theme.getMode(this@MainActivity)) }

            ImeTheme(themeMode = themeMode) {
                MainScreen(
                    currentThemeMode = themeMode,
                    onThemeModeChanged = { mode ->
                        KeyboardManager.Theme.setMode(this@MainActivity, mode)
                        themeMode = mode
                    },
                    onOpenImeSetup = {
                        setupImeLauncher.launch(
                            Intent(this@MainActivity, SetupActivity::class.java)
                        )
                    },
                    onOpenSchemaSettings = {
                        startActivity(
                            Intent(this@MainActivity, SchemaSettingsActivity::class.java)
                        )
                    },
                    onOpenKeyboardSettings = {
                        startActivity(
                            Intent(this@MainActivity, KeyboardSettingsActivity::class.java)
                        )
                    },
                    onOpenClipboard = {
                        startActivity(
                            Intent(this@MainActivity, ClipboardActivity::class.java)
                        )
                    },
                    onOpenModelSettings = {
                        startActivity(
                            Intent(this@MainActivity, PredictionSettingsActivity::class.java)
                        )
                    },
                    onOpenVoiceSettings = {
                        startActivity(
                            Intent(this@MainActivity, VoiceSettingsActivity::class.java)
                        )
                    },
                    onOpenFiles = {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                DocumentsContract.buildRootUri(
                                    AppFilesDocumentsProvider.AUTHORITY,
                                    AppFilesDocumentsProvider.ROOT_ID,
                                ),
                            ),
                        )
                    },
                    onOpenAbout = {
                        startActivity(
                            Intent(this@MainActivity, AboutActivity::class.java)
                        )
                    }
                )
            }
        }
    }

    private fun isImeConfigured(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val ourComponent = ComponentName(this, ImeInputMethodService::class.java)
        val isEnabled = imm.enabledInputMethodList.any {
            it.packageName == ourComponent.packageName && it.serviceName == ourComponent.className
        }
        val currentId =
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ourId = ourComponent.flattenToShortString()
        return isEnabled && currentId == ourId
    }
}
