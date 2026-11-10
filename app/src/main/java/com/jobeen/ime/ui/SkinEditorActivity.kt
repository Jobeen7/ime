// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.ui.screen.SkinEditorScreen
import com.jobeen.ime.ui.theme.ImeTheme

/** App 内置皮肤文件编辑器（W19-7）：编辑当前生效皮肤的 skin.json／布局文件。 */
class SkinEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialFilePath = intent.getStringExtra(EXTRA_FILE_PATH)
        setContent {
            ImeTheme {
                SkinEditorScreen(
                    initialFilePath = initialFilePath,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"

        fun intent(context: Context, filePath: String): Intent =
            Intent(context, SkinEditorActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
    }
}
