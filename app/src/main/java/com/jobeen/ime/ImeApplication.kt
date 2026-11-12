package com.jobeen.ime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import com.jobeen.ime.base.CrashTrap
import com.jobeen.ime.engine.AppStartup
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class ImeApplication : Application() {
    /**
     * 启动状态机：Failed 为失败态（A-S1）。AppStartup 各步失败只记日志、
     * 启动协程本身抛错／被取消时状态曾永久卡在 Starting，读 state 的
     * InitActivity／MainActivity 无恢复路径；置 Failed 后页面至少能
     * 观测到失败（超时分支按 != Finished 处理，见 InitActivity）。
     * 注意：AppStartup 内部单步失败仍由其 runCatching 消化，本态只
     * 覆盖 launch 层面的抛错／取消；单步失败进 Failed 需 AppStartup
     * 回报结果，见跨包事项（未改 engine 包）。
     */
    enum class AppState { Starting, ResourcePreparing, EngineStarting, Finished, Failed }

    /**
     * 应用级作用域（A-L3 残留说明）：仍公开——engine/AppStartup、
     * base/AppScopeUtil、ui/SchemeUpdateScreen 都直接用它，私有化需
     * 跨包改调用方，本批不动。但启动协程不再挂在它下面（见 startupScope），
     * 外部误取消本作用域不再连带杀掉启动。
     */
    val applicationScope = MainScope() + CoroutineName(javaClass.name)

    /**
     * 启动专用作用域（A-L3 根因修复）：与公开的 applicationScope 分离，
     * 外部代码拿不到它、取消不了它；进程存活期间无需取消，进程被杀时
     * 随进程消失（onTerminate 在真机上基本不执行，不依赖它做清理）。
     */
    private val startupScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ImeApplication.startup"))

    private val _state = MutableStateFlow(AppState.Starting)
    val state: StateFlow<AppState> = _state.asStateFlow()

    companion object {
        // A-L1：@Volatile 保证 onCreate（主线程写）对任意线程读的可见性。
        // getInstance() 在 onCreate 前调用仍抛错（原有口径不变）——
        // ContentProvider.onCreate 跑在 Application.onCreate 之前，
        // 任何在 Provider 或静态初始化里调 getInstance() 的代码仍会
        // 在启动时崩溃，调用方不得提前调（现两个 Provider 均未调）。
        @Volatile
        private var instance: ImeApplication? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("ime application is not created!")

        /**
         * API 24–27 进程名解析（A-M1 纯函数，可单测）：
         * 先查 runningAppProcesses（按 pid 命中）；查不到（列表为 null
         * 或无本 pid）时读 /proc/self/cmdline（首个 NUL 前的串即进程名），
         * 不再像旧实现那样直接默认 packageName——旧默认会把 :speech
         * 进程在系统进程列表不可用时误判为主进程，导致 AppStartup 在
         * 语音进程重复执行。两者都拿不到返回 null，调用方按主进程处理
         * （宁可 :speech 多跑一次启动，也不让主进程误判为非主进程而
         * 永不初始化）。
         *
         * @param rawCmdline 原始 /proc/self/cmdline 内容（可含 NUL 及后续参数）
         */
        internal fun resolveProcessNamePreP(
            pid: Int,
            runningAppProcesses: List<Pair<Int, String>>?,
            rawCmdline: () -> String?,
        ): String? {
            runningAppProcesses?.firstOrNull { it.first == pid }?.let { return it.second }
            return rawCmdline()
                ?.substringBefore('\u0000')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        private fun readProcSelfCmdline(): String? =
            runCatching { File("/proc/self/cmdline").readText() }.getOrNull()
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
        // 输入统计（2026-10-29 item-1 C）：A-L2——configure 内含
        // loadLocked() 同步读盘，改到 IO 线程做。读侧对未配置态有空
        // 守卫（statsDir 为空时 flush／scheduleFlush 直接返回，计数
        // 只走内存 map），故后台配置不丢行为、不改口径；键盘窗口在
        // Application.onCreate 返回后才可能出现，配置远在首键前完成。
        applicationScope.launch(Dispatchers.IO) {
            com.jobeen.ime.input.stats.InputStats.configure(filesDir)
        }
        if (isMainProcess()) {
            // 兜底：AppStartup 内部每步已独立捕获，这里再防一次未预料的异常直接杀进程
            val handler = CoroutineExceptionHandler { _, e ->
                android.util.Log.e("ImeApplication", "AppStartup crashed", e)
            }
            startupScope.launch(handler) {
                try {
                    AppStartup.initialize(this@ImeApplication)
                } catch (e: Throwable) {
                    // A-S1：launch 层面抛错／取消时置 Failed，不再永久卡 Starting。
                    // CancellationException 也重抛（取消语义不改写）；其余异常重抛
                    // 后由 handler 记日志（与旧行为一致）。
                    notifyState(AppState.Failed)
                    throw e
                }
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
            resolveProcessNamePreP(
                pid,
                am.runningAppProcesses?.map { it.pid to it.processName },
                Companion::readProcSelfCmdline,
            ) ?: packageName
        }
        return processName == packageName
    }
}
