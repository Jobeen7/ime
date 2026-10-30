// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.UserDictManager
import com.jobeen.ime.ui.screen.WordCreationScreen
import com.jobeen.ime.ui.screen.WordCreationUiState
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.AppAppearanceMode
import com.jobeen.ime.ui.theme.ImeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 独立造词页（2026-10-29 批量第 5 项）：
 * 词语＋编码 → 走与词典页导入同一合并口径（临时文件＋分块导入，
 * 经引擎线程串行，不与查词/学词并发访问同一词库），导入后立即生效。
 * 本页自带独立引擎会话（不复用词典页会话，两页可同时在返回栈）。
 */
class WordCreationActivity : ComponentActivity() {
    // 返回即刷新：创建时记下的外观模式；onResume 比对，变了才重建。
    private var createdAppearanceMode: AppAppearanceMode? = null

    private val uiState = WordCreationUiState()
    private var session: RimeSession? = null
    private var loadJob: Job? = null

    /**
     * 返回即刷新：从外观页返回时，若应用外观模式已变则重建本页，
     * 主题立即生效；模式未变不重建；重建后新模式即记录值，不循环。
     */
    override fun onResume() {
        super.onResume()
        val recordedAppearanceMode = createdAppearanceMode
        if (recordedAppearanceMode != null &&
            AppAppearance.appearanceModeChanged(recordedAppearanceMode, AppAppearance.getMode(this))
        ) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAppearanceMode = AppAppearance.getMode(this)
        enableEdgeToEdge()

        val themeMode = AppAppearance.themeMode(this)
        setContent {
            ImeTheme(themeMode = themeMode, dynamicColor = AppAppearance.useDynamicColor(this)) {
                WordCreationScreen(
                    state = uiState,
                    onBack = { finish() },
                    onSelectDict = { uiState.selectedDict = it },
                    onImport = { doImport() },
                )
            }
        }

        loadDictList()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { RimeDaemon.destroySession(SESSION_NAME) }
            }
        }
        session = null
    }

    private fun loadDictList() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = RimeDaemon.createSession(SESSION_NAME)
                session = s
                val ready = withTimeoutOrNull(60_000L) {
                    s.runOnReady { }
                    true
                }
                if (ready != true) {
                    throw IllegalStateException("Rime 启动超时")
                }
                val dicts = s.runOnReady { getUserDictList() }
                withContext(Dispatchers.Main) {
                    uiState.dicts = dicts
                    uiState.selectedDict = dicts.firstOrNull()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to load user dict list for word creation")
                withContext(Dispatchers.Main) {
                    uiState.dicts = emptyList()
                    ToastUtil.showToast("词典列表加载失败")
                }
            }
        }
    }

    private fun doImport() {
        val word = uiState.word.trim()
        val code = uiState.code.trim()
        val dict = uiState.selectedDict ?: return
        if (word.isEmpty() || code.isEmpty()) {
            uiState.error = getString(R.string.word_creation_empty)
            return
        }
        if (word.any { it == '\t' || it == '\n' || it == '\r' } ||
            code.any { it == '\t' || it == '\n' || it == '\r' }
        ) {
            uiState.error = getString(R.string.word_creation_invalid)
            return
        }
        uiState.busy = true
        uiState.error = null
        uiState.result = null
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = session
                    ?: throw IllegalStateException("Rime session not established")
                val tempFile = UserDictManager.stageImportFile(
                    "$word\t$code\n".byteInputStream(),
                    "word-$dict.txt",
                ).getOrThrow()
                val count = try {
                    s.runOnReady {
                        UserDictManager.importUserDictInChunks(tempFile) { chunk ->
                            importUserDictLive(dict, chunk.absolutePath)
                        }
                    }
                } finally {
                    tempFile.delete()
                }
                withContext(Dispatchers.Main) {
                    uiState.busy = false
                    when {
                        count < 0 -> uiState.error = getString(R.string.word_creation_failed)
                        count == 0 -> uiState.error = getString(R.string.word_creation_none)
                        else -> {
                            uiState.result = getString(
                                R.string.word_creation_imported, word, code,
                            )
                            uiState.word = ""
                            uiState.code = ""
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Word creation import failed")
                withContext(Dispatchers.Main) {
                    uiState.busy = false
                    uiState.error = getString(R.string.word_creation_failed)
                }
            }
        }
    }

    companion object {
        private const val SESSION_NAME = "word-creation"
    }
}
