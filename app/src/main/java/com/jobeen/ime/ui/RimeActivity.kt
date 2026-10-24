package com.jobeen.ime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.ui.screen.RimeScreen
import com.jobeen.ime.ui.theme.ImeTheme
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * RIME 页宿主：与设置 home 的 FAB 共用同一套部署/同步动作
 * （部署 = RimeApi.deploy；同步 = WebDavSync.syncSchemeDir 目录同步）。
 */
class RimeActivity : ComponentActivity() {

    private val rimeSession by lazy { RimeDaemon.createSession("rime") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode = remember {
                mutableIntStateOf(KeyboardManager.Theme.getMode(this))
            }
            var deployRunning by remember { mutableStateOf(false) }
            var syncRunning by remember { mutableStateOf(false) }
            ImeTheme(themeMode = themeMode.intValue) {
                RimeScreen(
                    onBack = { finish() },
                    deployRunning = deployRunning,
                    onDeploy = {
                        if (deployRunning) return@RimeScreen
                        deployRunning = true
                        lifecycleScope.launch {
                            runCatching {
                                rimeSession.runOnReady { deploy() }
                            }.onSuccess {
                                ToastUtil.showToast(getString(R.string.fab_deploy_done))
                                RimeSyncPrefs.postNotice(
                                    this@RimeActivity,
                                    getString(R.string.fab_deploy_done),
                                )
                            }.onFailure {
                                Timber.w(it, "RIME deploy failed")
                                ToastUtil.showToast(getString(R.string.fab_deploy_failed))
                                RimeSyncPrefs.postNotice(
                                    this@RimeActivity,
                                    getString(R.string.fab_deploy_failed),
                                )
                            }
                            deployRunning = false
                        }
                    },
                    syncRunning = syncRunning,
                    onSync = {
                        if (syncRunning) return@RimeScreen
                        if (!UserDictPrefs.isConfigured()) {
                            ToastUtil.showToast(
                                getString(R.string.fab_sync_not_configured),
                            )
                            startActivity(
                                Intent(
                                    this@RimeActivity,
                                    UserDictActivity::class.java,
                                ),
                            )
                            return@RimeScreen
                        }
                        syncRunning = true
                        lifecycleScope.launch {
                            try {
                                val result = WebDavSync.syncSchemeDir(rimeSession)
                                result.onSuccess { r ->
                                    val msg = if (r.failedFiles.isEmpty()) {
                                        getString(
                                            R.string.fab_dir_sync_success,
                                            r.uploaded,
                                            r.downloaded,
                                        )
                                    } else {
                                        getString(
                                            R.string.fab_dir_sync_partial,
                                            r.uploaded,
                                            r.downloaded,
                                            r.failedFiles.size,
                                        )
                                    }
                                    ToastUtil.showToast(msg)
                                    RimeSyncPrefs.postNotice(this@RimeActivity, msg)
                                }.onFailure {
                                    Timber.w(it, "RIME dir sync failed")
                                    ToastUtil.showToast(getString(R.string.fab_sync_failed))
                                    RimeSyncPrefs.postNotice(
                                        this@RimeActivity,
                                        getString(R.string.fab_sync_failed),
                                    )
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "RIME dir sync failed")
                                ToastUtil.showToast(
                                    getString(R.string.fab_sync_engine_not_ready),
                                )
                            } finally {
                                syncRunning = false
                            }
                        }
                    },
                    onOpenManage = {
                        startActivity(
                            Intent(this@RimeActivity, SchemaSettingsActivity::class.java)
                                .putExtra(SchemaSettingsActivity.EXTRA_OPEN_MANAGE, true),
                        )
                    },
                )
            }
        }
    }
}
