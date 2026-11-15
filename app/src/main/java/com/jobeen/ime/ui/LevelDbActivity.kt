// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.LevelDbBrowser
import com.jobeen.ime.engine.rime.data.userdict.RimeSessionUserDictEngine
import com.jobeen.ime.ui.screen.LevelDbScreen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * LevelDB 查看页宿主（WO52 12-⑭，设置一级入口 `leveldb`）。
 *
 * 打开时创建 Rime 会话（与用户词典页同口径，按名共享）；浏览一律经
 * [LevelDbBrowser] 走现有 `RimeApi` 路径（列表／热导出），只读不写，
 * 不直连 LevelDB JNI。
 */
class LevelDbActivity : ImeActivity() {

    private var session: RimeSession? = null
    private var browserState by mutableStateOf<LevelDbBrowser?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            val s = RimeDaemon.createSession(SESSION_NAME)
            session = s
            val browser = LevelDbBrowser(
                engine = RimeSessionUserDictEngine(s),
                tempDir = { File(cacheDir, "leveldb") },
            )
            browserState = browser
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            val inFlight = lifecycleScope.coroutineContext[Job]?.children?.toList().orEmpty()
            CoroutineScope(Dispatchers.IO).launch {
                inFlight.joinAll()
                runCatching { RimeDaemon.destroySession(SESSION_NAME) }
            }
        }
        session = null
    }

    @Composable
    override fun Content() {
        LevelDbScreen(
            browser = browserState,
            onBack = { finish() },
        )
    }

    companion object {
        private const val SESSION_NAME = "leveldb"
    }
}
