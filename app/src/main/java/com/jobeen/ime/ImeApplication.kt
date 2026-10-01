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
                AppStartup.initialize(this@ImeApplication)
            }
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
