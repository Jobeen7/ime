package com.jobeen.ime.engine.rime.data

import com.jobeen.ime.base.util.appContext
import java.io.File

object DataManager {
    val sharedDataDir = File(appContext.getExternalFilesDir(null), "shared").also { it.mkdirs() }
    val userDataDir
        get() = File(appContext.getExternalFilesDir(null), "user").also { it.mkdirs() }
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
