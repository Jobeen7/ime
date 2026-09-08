package com.ninthsoft.ime.engine.rime.data

import android.content.res.AssetManager
import com.ninthsoft.ime.base.util.FileUtil
import com.ninthsoft.ime.base.util.ResourceUtil
import com.ninthsoft.ime.base.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DataManager {
    private const val DEFAULT_CUSTOM_FILE_NAME = "default.custom.yaml"

    private const val DATA_CHECKSUMS_NAME = "checksums.json"

    private const val SCHEMA_LIST_CUSTOM_PATCH = """
      patch:
        schema_list:
          - schema: luna_pinyin
          - schema: luna_pinyin_simp
    """

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataSum(raw: String): DataSum = json.decodeFromString<DataSum>(raw)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File = appContext.createDeviceProtectedStorageContext().dataDir

    private fun AssetManager.DataSum(): DataSum =
        open(DATA_CHECKSUMS_NAME).bufferedReader().use { it.readText() }
            .let { deserializeDataSum(it) }
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

    fun sync() = lock.withLock {
        val oldChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            oldChecksumsFile.runCatching { deserializeDataSum(bufferedReader().use { it.readText() }) }
                .getOrElse { DataSum("", emptyMap()) }

        try {
            val newChecksums = appContext.assets.DataSum()
            DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }.forEach {
                Timber.d("Diff: $it")
                when (it) {
                    is DataDiff.CreateFile,
                    is DataDiff.UpdateFile,
                        -> {
                        val destPath = sharedDataDir.resolveSibling(it.path).absolutePath
                        ResourceUtil.copyFile(it.path, destPath)
                    }

                    is DataDiff.DeleteDir,
                    is DataDiff.DeleteFile,
                        -> FileUtil.delete(sharedDataDir.resolve(it.path.substringAfterLast('/')))
                        .getOrThrow()
                }
            }

            ResourceUtil.copyFile(
                DATA_CHECKSUMS_NAME, dataDir.resolve(DATA_CHECKSUMS_NAME).absolutePath
            )

            val custom = userDataDir.resolve(DEFAULT_CUSTOM_FILE_NAME)
            if (!custom.exists()) {
                if (custom.createNewFile()) {
                    custom.writeText(SCHEMA_LIST_CUSTOM_PATCH.trimIndent())
                }
            }

            Timber.d("Synced!")
        } catch (e: Exception) {
            Timber.d("Sync not prepared!")
        }
    }
}