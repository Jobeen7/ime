package com.jobeen.ime.skin.script

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.ImeTheme

/**
 * App-level script management host: lists/adds/removes/toggles scripts.
 *
 * Registered in AndroidManifest.xml (`.skin.script.SkinScriptActivity`);
 * launch via [MainActivity.openSkinScripts]. The management Screen
 * itself needs no other host changes.
 */
class SkinScriptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SkinScriptStore(filesDir)
        setContent {
            ImeTheme(
                themeMode = AppAppearance.themeMode(this@SkinScriptActivity),
                dynamicColor = AppAppearance.useDynamicColor(this@SkinScriptActivity),
            ) {
                SkinScriptScreen(
                    store = store,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SkinScriptActivity::class.java)
    }
}
