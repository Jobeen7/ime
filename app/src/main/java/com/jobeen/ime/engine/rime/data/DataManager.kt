package com.jobeen.ime.engine.rime.data

import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.data.manager.SchemeDirs
import java.io.File

object DataManager {
    val sharedDataDir = File(appContext.getExternalFilesDir(null), "shared").also { it.mkdirs() }

    /**
     * 方案目录根：原 user/，现为各方案目录的父目录（多目录模型，
     * Lee 2026-10-25 定）。存量平铺内容首次读取时迁进初始目录。
     */
    val schemeDirsRoot: File
        get() = File(appContext.getExternalFilesDir(null), "user").also { it.mkdirs() }

    /**
     * 引擎 user 目录 = 根 / 当前目录名。迁移初始化只跑一次（once-guard）：
     * 首次调用跑 [SchemeDirs.ensureMigrated]，成功置位后后续调用只做
     * 标志位检查；迁移未成功（未置位）时下次调用重试，不把失败永久缓存。
     * 注意守卫范围只含迁移初始化：当前目录名每调用仍现读偏好（目录
     * 切换改偏好后必须立即生效，缓存解析结果会冻住切换），目标目录
     * 的 mkdirs 每调用仍跑（目录被删时重建空目录、引擎回落内置 Lite）。
     */
    @Volatile
    private var userDataDirMigrated = false

    val userDataDir: File
        get() {
            val root = schemeDirsRoot
            if (!userDataDirMigrated) {
                synchronized(this) {
                    if (!userDataDirMigrated) {
                        SchemeDirs.ensureMigrated(root)
                        if (SchemeDirs.isMigrated()) userDataDirMigrated = true
                    }
                }
            }
            return File(root, SchemeDirs.currentDirName()).also { it.mkdirs() }
        }
    val prebuiltDataDir = File(sharedDataDir, "build")
    val stagingDir get() = File(userDataDir, "build")

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }
}
