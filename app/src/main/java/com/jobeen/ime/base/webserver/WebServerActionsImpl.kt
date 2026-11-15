package com.jobeen.ime.base.webserver

import android.content.Context
import android.os.StatFs
import com.jobeen.ime.BuildConfig
import com.jobeen.ime.data.UserFiles
import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.RimeEngine
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.store.SkinLibraryLayout
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

/**
 * Wi-Fi 传输路由的应用能力生产实现（WO52 4.4）。
 *
 * - deploy：与 FAB 同口径的引擎部署（deployAndAwaitIfChanged，
 *   超时随目录体积缩放）；成功只认 COMPLETED。
 * - sync：WebDavSync.syncAllDicts（现有同步，不另写）。
 * - applySkin：只收 Jime 格式 skin.json＋manifest.json——
 *   先过 SkinValidator.validate（与导入同口径），再写盘进
 *   skins/<name>/gen-1/（SkinLibraryLayout 口径，与皮肤库
 *   索引一致）；目标路径含符号链接皮肤目录时拒绝。
 * - switchUserDataDir：映 SchemeDirs 多目录切换——校验目录名、
 *   指针切过去、重新部署。注意：设置页切换另有 enabled 方案
 *   镜像与方案文件修补两步，本口不做（目标目录无
 *   default.custom.yaml 时部署按共享层回落，与空目录同口径）；
 *   目标目录不存在 → INVALID_DIR。
 */
class WebServerActionsImpl(
    private val context: Context,
) : WebServerActions {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun deploy(): Boolean {
        val engine = EngineFactory.current() as? RimeEngine ?: return false
        val timeout = deployTimeoutForCurrentDir()
        return runCatching {
            engine.deployAndAwaitIfChanged(timeout) == DeployAwaitResult.COMPLETED
        }.getOrDefault(false)
    }

    override suspend fun sync(): Boolean {
        val session = RimeDaemon.createSession(SESSION_NAME)
        return try {
            WebDavSync.syncAllDicts(session).isSuccess
        } catch (e: Exception) {
            Timber.w(e, "WebServer sync failed")
            false
        } finally {
            runCatching { RimeDaemon.destroySession(SESSION_NAME) }
        }
    }

    override fun applySkin(name: String, skinJson: String, manifestJson: String): Result<Unit> {
        if (!isValidSkinName(name)) return Result.failure(IllegalArgumentException("invalid_name"))
        val manifest = runCatching {
            json.parseToJsonElement(manifestJson).jsonObject
        }.getOrNull() ?: return Result.failure(IllegalArgumentException("bad_manifest"))
        if (manifest["name"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("missing_manifest_name"))
        }
        val skinTree = runCatching {
            json.parseToJsonElement(skinJson).jsonObject
        }.getOrNull() ?: return Result.failure(IllegalArgumentException("bad_skin"))
        val fallbacks = SkinSnapshotFactory.geometryFallbacks(context)
        val validation = SkinValidator.validate(
            skinTree, BuildConfig.VERSION_CODE, fallbacks, { null },
        )
        if (validation is SkinValidator.Result.Rejected) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val extRoot = context.getExternalFilesDir(null)
            ?: return Result.failure(IllegalStateException("no_external_files"))
        val skinLibraryRoot = UserFiles.skinLibraryDir(extRoot)
        val targetDir = applySkinTargetDir(skinLibraryRoot, name)
        if (WebServerPaths.isSkinSymlink(skinLibraryRoot, targetDir)) {
            return Result.failure(IllegalArgumentException("symlinked_skin_dir"))
        }
        return runCatching {
            targetDir.mkdirs()
            File(targetDir, "skin.json").writeText(skinJson)
            File(targetDir, "manifest.json").writeText(manifestJson)
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    override suspend fun switchUserDataDir(dir: String): SwitchUserDataResult {
        if (!SchemeDirs.isValidDirName(dir)) return SwitchUserDataResult.INVALID_DIR
        val targetDir = File(DataManager.schemeDirsRoot, dir)
        if (!targetDir.isDirectory) return SwitchUserDataResult.INVALID_DIR
        SchemeDirs.setCurrentDirName(context, dir)
        val engine = EngineFactory.current() as? RimeEngine
            ?: return SwitchUserDataResult.DEPLOY_FAILED
        val timeout = SchemePackageImport.deployTimeoutMs(dirTotalBytes(targetDir))
        val result = runCatching {
            engine.deployAndAwaitIfChanged(timeout)
        }.getOrDefault(DeployAwaitResult.FAILED)
        return if (result == DeployAwaitResult.COMPLETED) {
            SwitchUserDataResult.OK
        } else {
            SwitchUserDataResult.DEPLOY_FAILED
        }
    }

    override fun staticAsset(relativePath: String): ByteArray? {
        if (relativePath.contains("..")) return null
        return runCatching {
            context.assets.open("webserver/$relativePath").use { it.readBytes() }
        }.getOrNull()
    }

    /** 总用量（/api/usage 的 total）：external files 所在分区总字节数。 */
    fun totalBytes(): Long {
        val extRoot = context.getExternalFilesDir(null) ?: return 0L
        return runCatching {
            val stat = StatFs(extRoot.path)
            stat.blockCountLong * stat.blockSizeLong
        }.getOrDefault(0L)
    }

    private fun deployTimeoutForCurrentDir(): Long {
        val dir = File(
            DataManager.schemeDirsRoot,
            SchemeDirs.currentDirName(context),
        )
        return SchemePackageImport.deployTimeoutMs(dirTotalBytes(dir))
    }

    private fun dirTotalBytes(dir: File): Long =
        runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    private fun isValidSkinName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    companion object {
        private const val SESSION_NAME = "web-server"

        /**
         * C-L-07：Wi-Fi 传皮肤落盘目标——走 SkinLibraryLayout 口径
         * `skins/<name>/gen-1/`（与皮肤库索引一致），不再直接写
         * `skin_library/<name>/`。name 已过 isValidSkinName（只含
         * 字母数字／.／_／-，拒 "."／".."），gen-1 固定，不会越界写盘。
         */
        fun applySkinTargetDir(skinLibraryRoot: File, name: String): File =
            SkinLibraryLayout(skinLibraryRoot).skinGenDir(name, 1)
    }
}
