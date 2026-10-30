package com.jobeen.ime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import com.jobeen.ime.base.CrashTrap
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
        CrashTrap.install(this)
        // 诊断日志基建（详设十三章）：崩溃仍由 CrashTrap 单点落盘，
        // 本设施记 v3 子系统的结构化元数据日志，供日志页导出
        com.jobeen.ime.skin.diag.DiagLog.init(this)
        // 导出链路抢先装：AppStartup 在 Default 线程异步装缓冲，
        // 而皮肤库启动对账在 IO 线程同时跑——对账失败的 DiagLog
        // 若抢在缓冲安装前发出，只落文件、进不了导出 zip。
        // 此处主线程同步装一次（install 自带 installed 幂等，
        // AppStartup.setupLogger 的二次调用是空操作），关掉该竞态窗。
        com.jobeen.ime.base.log.AppLogBuffer.install(this, enableLogging = true)
        // 性能埋点（2026-10-29 item-1 B）：落盘目录锚定＋进程
        // 启动时刻（冷启动起点，首个键盘窗口挂接时结算）。
        com.jobeen.ime.skin.perf.PerfMonitor.configure(filesDir)
        com.jobeen.ime.skin.perf.PerfMonitor.noteProcessStart(
            android.os.Process.getStartElapsedRealtime(),
        )
        // 输入统计（2026-10-29 item-1 C）：存储目录锚定＋读回
        // 已有数据（本地存储，不上传）。
        com.jobeen.ime.input.stats.InputStats.configure(filesDir)
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
