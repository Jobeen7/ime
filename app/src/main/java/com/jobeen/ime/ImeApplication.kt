package com.jobeen.ime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import com.jobeen.ime.engine.AppStartup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class ImeApplication : Application() {
    enum class AppState { Starting, ResourcePreparing, EngineStarting, Finished }

    val applicationScope = MainScope() + CoroutineName(javaClass.name)
    private val _state = MutableStateFlow(AppState.Starting)
    val state: StateFlow<AppState> = _state.asStateFlow()

    companion object {
        private var instance: ImeApplication? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("ime application is not created!")
    }

    fun notifyState(state: AppState) {
        _state.value = state
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (isMainProcess()) {
            // 兜底：AppStartup 内部每步已独立捕获，这里再防一次未预料的异常直接杀进程
            val handler = CoroutineExceptionHandler { _, e ->
                android.util.Log.e("ImeApplication", "AppStartup crashed", e)
            }
            applicationScope.launch(Dispatchers.Default + handler) {
                cleanupLegacySpeechFiles()
                AppStartup.initialize(this@ImeApplication)
            }
        }
    }

    /** 一次性清理已移除的语音热词残留文件；文件不存在时 delete 即 no-op，后续启动无副作用 */
    private fun cleanupLegacySpeechFiles() {
        runCatching {
            val speechDir = java.io.File(filesDir, "speech")
            listOf(
                "hotwords.txt",
                "decode_mode.txt",
                "engine_status.txt",
                "hotwords_degraded.txt",
                "bpe.vocab",
                "migrated_bpe_v1.txt",
            ).forEach { java.io.File(speechDir, it).delete() }
            java.io.File(com.jobeen.ime.data.App.speechModelDir, "bpe.model").delete()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存紧张时释放简繁转换词表（数 MB 级），下次转换按需重建
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching { com.jobeen.ime.base.util.TraditionalConverter.releaseCaches() }
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
        }
        return processName == packageName
    }
}
