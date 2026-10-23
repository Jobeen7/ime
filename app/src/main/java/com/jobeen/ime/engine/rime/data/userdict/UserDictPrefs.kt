package com.jobeen.ime.engine.rime.data.userdict

import android.content.Context
import com.jobeen.ime.base.util.SecretStore
import com.jobeen.ime.base.util.appContext
import timber.log.Timber

/**
 * 用户词典 WebDAV 同步的配置存储。
 *
 * 密码经 [SecretStore]（Android Keystore，AES-GCM）加密后存入私有
 * SharedPreferences，落盘无明文。Keystore 不可用时 setter 不落盘并记录
 * 日志，getter 返回空，绝不回退明文存储；存量若不是密文形态（远古版
 * 明文），getter 按无密码处理（清点 Q18 定论，不再读路径回写迁移）。
 * 备份/还原不带本文件整表（设备本地状态，见 BackupManager）。
 */
object UserDictPrefs {
    private const val PREFS_NAME = "user_dict_sync"

    private const val KEY_SERVER = "webdav_server"
    private const val KEY_USERNAME = "webdav_username"
    private const val KEY_PASSWORD = "webdav_password"
    private const val KEY_SYNC_PATH = "webdav_sync_path"
    private const val KEY_ALLOW_HTTP = "webdav_allow_http"

    /**
     * 每文件的最近成功上传/下载时间（仅供设置页「上次同步」显示，
     * 不参与任何同步决策）。键名用独立前缀 `sync_time␟`（␟ 是单元
     * 分隔符 U+001F，不可能出现在词典文件名里）。ETag 基准记账已随
     * 清点 S1 定论整套退场（不再有 sync_etag_ 键的读写；存量键留在
     * prefs 里无人读，本文件整表又是设备本地状态、不随备份走）。
     */
    private const val KEY_SYNC_UPLOAD_TIME_PREFIX = "sync_time\u001Fup\u001F"
    private const val KEY_SYNC_DOWNLOAD_TIME_PREFIX = "sync_time\u001Fdown\u001F"

    /** 最近一次任意下载成功并完成合并的时刻（仅供「上次下载」显示，不是同步基准） */
    private const val KEY_LAST_ANY_DOWNLOAD_TIME = "last_any_download_time"

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var server: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() {
            val stored = prefs.getString(KEY_PASSWORD, "").orEmpty()
            if (stored.isEmpty()) return ""
            if (SecretStore.isEncrypted(stored)) {
                // 密钥丢失/损坏时解密失败：返回空而不是把密文当密码用
                return SecretStore.decrypt(stored).orEmpty()
            }
            // 非密文存量（远古版明文）：按无密码处理，不再读路径回写
            // 迁移（清点 Q18 定论——该分支对现行设备不可达，getter 的
            // 写副作用一并消除）；用户重新填一次密码即恢复
            return ""
        }
        set(value) {
            if (value.isEmpty()) {
                prefs.edit().remove(KEY_PASSWORD).apply()
                return
            }
            val encrypted = SecretStore.encrypt(value)
            if (encrypted == null) {
                // Keystore 不可用：宁可不保存也不写明文
                Timber.w("WebDAV password not saved: Keystore unavailable")
                return
            }
            prefs.edit().putString(KEY_PASSWORD, encrypted).apply()
        }

    var syncPath: String
        get() = prefs.getString(KEY_SYNC_PATH, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SYNC_PATH, value.trim()).apply()

    /**
     * 显示用（设置页「上次上传/下载」）：全部词典 per-file 时间的
     * 最大值，即最近一次成功同步的时刻；无任何记录为 0。只读聚合，
     * 不是同步决策的依据。旧全局键回落已随清点 D17 删除（per-file
     * 口径运行多版，回落支不可达）。
     */
    val lastUploadTime: Long
        get() = maxPerFileTime(KEY_SYNC_UPLOAD_TIME_PREFIX)

    val lastDownloadTime: Long
        get() {
            // 跨词典下载不建 per-file 记录，但「上次下载」显示应更新：
            // 聚合里并入任意下载的最近时刻
            return maxOf(
                maxPerFileTime(KEY_SYNC_DOWNLOAD_TIME_PREFIX),
                prefs.getLong(KEY_LAST_ANY_DOWNLOAD_TIME, 0L),
            )
        }

    /** 任意一次下载合并成功后记一笔（仅供「上次下载」显示，永不作同步基准） */
    fun noteAnyDownload(timeMs: Long) {
        if (timeMs > prefs.getLong(KEY_LAST_ANY_DOWNLOAD_TIME, 0L)) {
            prefs.edit().putLong(KEY_LAST_ANY_DOWNLOAD_TIME, timeMs).apply()
        }
    }

    private fun maxPerFileTime(prefix: String): Long {
        var max = 0L
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix)) {
                val t = (value as? Number)?.toLong() ?: continue
                if (t > max) max = t
            }
        }
        return max
    }

    /** 某个远端文件最近一次成功上传的时刻（仅显示用）；从未成功为 0 */
    fun lastUploadTimeFor(fileName: String): Long =
        prefs.getLong(KEY_SYNC_UPLOAD_TIME_PREFIX + fileName, 0L)

    fun setLastUploadTime(fileName: String, timeMs: Long) {
        prefs.edit().putLong(KEY_SYNC_UPLOAD_TIME_PREFIX + fileName, timeMs).apply()
    }

    /** 某个远端文件最近一次成功下载并合并的时刻（仅显示用）；从未成功为 0 */
    fun lastDownloadTimeFor(fileName: String): Long =
        prefs.getLong(KEY_SYNC_DOWNLOAD_TIME_PREFIX + fileName, 0L)

    fun setLastDownloadTime(fileName: String, timeMs: Long) {
        prefs.edit().putLong(KEY_SYNC_DOWNLOAD_TIME_PREFIX + fileName, timeMs).apply()
    }

    /** 高级选项：是否允许明文 HTTP 的 WebDAV 服务器（默认不允许，凭据与词典会明文传输）。 */
    var allowHttp: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_HTTP, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_HTTP, value).apply()

    fun isConfigured(): Boolean =
        server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()

    /** 形如 https://dav.jianguoyun.com/dav/，末尾统一不带斜杠 */
    fun normalizedServer(): String = server.trim().trimEnd('/')

    /** 同步子路径，形如 rime_sync，首尾统一不带斜杠；为空表示服务器根目录 */
    fun normalizedSyncPath(): String = syncPath.trim().trim('/')

    /** WebDAV 同步目录的完整 URL，上传/下载/列表都在该目录下进行 */
    fun syncDirUrl(): String {
        val base = normalizedServer()
        val path = normalizedSyncPath()
        return if (path.isEmpty()) base else "$base/$path"
    }
}
