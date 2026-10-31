package com.jobeen.ime.skin.store

import com.jobeen.ime.data.App
import com.jobeen.ime.skin.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 皮肤库门面：把 [SkinLibraryLayout] / [SkinLibraryStore] /
 * [LibraryReconciler] 按详设 §2.1 的根目录（App.skinLibraryDir）接好。
 * 全部实例懒加载——App 的目录口径依赖 Application 已创建，此处不
 * 在类初始化期触碰。
 *
 * 启动对账接线：库内首次触 layout 即触发一次单飞对账（见
 * [ensureBootstrapped]），快照首次加载前等对账收敛（见
 * [awaitBootstrap]，上限 3 秒、超时放行）。对账失败只记日志、
 * 最多重试一次，绝不阻塞启动、绝不把损坏索引静默当空库
 * （损坏索引由对账从磁盘重建，见 LibraryReconciler）。
 */
object SkinLibrary {
    val layout: SkinLibraryLayout by lazy {
        SkinLibraryLayout(App.skinLibraryDir).also { ensureBootstrapped() }
    }
    val store: SkinLibraryStore by lazy { SkinLibraryStore(layout) }
    val reconciler: LibraryReconciler by lazy { LibraryReconciler(layout, store) }

    /**
     * 启动对账入口（详设 §2.8）：主进程冷启动时异步跑一遍对账，
     * 只动 skin_library 目录；失败只记日志，绝不阻塞启动。
     */
    fun bootstrapIn(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runReconcileOnce()
        }
    }

    /** 进程内单飞启动作用域（SupervisorJob：子任务失败不牵连其它任务）。 */
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapLock = Any()
    private var bootstrapJob: Job? = null
    private var bootstrapSucceeded = false
    private var bootstrapAttempts = 0

    /** 启动对账尝试上限：初次 + 1 次重试。 */
    private const val BOOTSTRAP_MAX_ATTEMPTS = 2

    /** 重试前延迟：给瞬时 IO 错误（如存储刚就绪）留恢复窗口。 */
    private const val BOOTSTRAP_RETRY_DELAY_MS = 5_000L

    /** 快照首次加载等待对账收敛的上限：超时放行，对账继续后台跑。 */
    const val BOOTSTRAP_AWAIT_TIMEOUT_MS = 3_000L

    /**
     * 单次对账（共享给显式入口与单飞启动）：成功返回 true；失败记
     * reconcile_failed 日志并返回 false，永不抛（调用方按返回值
     * 决定是否重试，启动路径绝不因对账失败崩进程）。
     */
    private suspend fun runReconcileOnce(): Boolean =
        runCatching { reconciler.reconcile() }
            .onFailure { DiagLog.e("store", "reconcile_failed", throwable = it) }
            .isSuccess

    /**
     * 触发进程级单飞启动对账（幂等）：已成功、已在跑、或已耗尽
     * 尝试次数时直接返回；否则在 IO 作用域异步跑一遍，失败 5 秒后
     * 最多重试一次。只动 skin_library 目录；与并发导入／应用串行化
     * 靠 store 写锁＋事务 staging（见 LibraryReconciler 与
     * SkinLibraryStore），本处不另加锁。
     */
    fun ensureBootstrapped() {
        synchronized(bootstrapLock) {
            if (bootstrapSucceeded) return
            if (bootstrapJob?.isActive == true) return
            if (bootstrapAttempts >= BOOTSTRAP_MAX_ATTEMPTS) return
            bootstrapAttempts++
            bootstrapJob = bootstrapScope.launch {
                if (runReconcileOnce()) {
                    synchronized(bootstrapLock) { bootstrapSucceeded = true }
                } else {
                    synchronized(bootstrapLock) {
                        if (bootstrapAttempts < BOOTSTRAP_MAX_ATTEMPTS) {
                            bootstrapAttempts++
                            bootstrapJob = bootstrapScope.launch {
                                delay(BOOTSTRAP_RETRY_DELAY_MS)
                                if (runReconcileOnce()) {
                                    synchronized(bootstrapLock) { bootstrapSucceeded = true }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 快照首次加载前等待启动对账收敛（suspend）：先确保已触发，
     * 再 join 当前任务；若首任务失败且已换重试任务，继续等重试
     * 任务；全程上限见 [BOOTSTRAP_AWAIT_TIMEOUT_MS]，超时或等待
     * 异常一律放行（快照按现有 store 状态加载，对账继续后台跑，
     * 下次失效／重启时再收敛）。永不抛。
     */
    suspend fun awaitBootstrap() {
        try {
            withTimeoutOrNull(BOOTSTRAP_AWAIT_TIMEOUT_MS) {
                var joined: Job? = null
                while (true) {
                    val job = synchronized(bootstrapLock) {
                        ensureBootstrapped()
                        if (bootstrapSucceeded) null else bootstrapJob
                    } ?: break
                    // 已等过该任务且未成功、无新任务时不再空转。
                    if (job === joined) break
                    joined = job
                    job.join()
                }
            }
        } catch (e: Exception) {
            // 等待被取消等异常一律放行，不阻塞快照加载。
        }
    }
}
