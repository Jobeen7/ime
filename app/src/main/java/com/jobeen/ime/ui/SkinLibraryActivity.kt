package com.jobeen.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.SkinLibraryScreen
import com.jobeen.ime.ui.theme.ImeTheme

/** 皮肤库管理（列表/应用/回退/导入/导出/同步/编辑入口）。 */
class SkinLibraryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImeTheme(themeMode = KeyboardManager.Theme.getMode(this)) {
                SkinLibraryScreen(onBack = { finish() })
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SkinLibraryActivity::class.java)
    }
}
