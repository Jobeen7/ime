package com.jobeen.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.skin.library.SkinLibraryPrefs
import com.jobeen.ime.ui.screen.SkinDevScreen
import com.jobeen.ime.ui.theme.ImeTheme

/** 皮肤开发屏（第三组新增）：解析检视 / 校验报告 / 诊断日志 / 重载 / 导入。 */
class SkinDevActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImeTheme(themeMode = SkinLibraryPrefs.getColorMode(this).toThemeMode()) {
                SkinDevScreen(onBack = { finish() })
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SkinDevActivity::class.java)
    }
}
