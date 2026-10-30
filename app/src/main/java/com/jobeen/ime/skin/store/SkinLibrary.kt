package com.jobeen.ime.skin.store

import com.jobeen.ime.data.App
import com.jobeen.ime.skin.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 皮肤库门面：把 [SkinLibraryLayout] / [SkinLibraryStore] /
 * [LibraryReconciler] 按详设 §2.1 的根目录（App.skinLibraryDir）接好。
 * 全部实例懒加载——App 的目录口径依赖 Application 已创建，此处不
 * 在类初始化期触碰。
 */
object SkinLibrary {
    val layout: SkinLibraryLayout by lazy { SkinLibraryLayout(App.skinLibraryDir) }
    val store: SkinLibraryStore by lazy { SkinLibraryStore(layout) }
    val reconciler: LibraryReconciler by lazy { LibraryReconciler(layout, store) }

    /**
     * 启动对账入口（详设 §2.8）：主进程冷启动时异步跑一遍对账，
     * 只动 skin_library 目录；失败只记日志，绝不阻塞启动。
     */
    fun bootstrapIn(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { reconciler.reconcile() }
                .onFailure { DiagLog.e("store", "reconcile_failed", throwable = it) }
        }
    }
}
