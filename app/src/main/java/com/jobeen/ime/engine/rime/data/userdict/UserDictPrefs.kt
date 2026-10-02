package com.jobeen.ime.engine.rime.data.userdict

import android.content.Context
import com.jobeen.ime.base.util.appContext

/**
 * 用户词典 WebDAV 同步的配置存储。
 *
 * 说明：密码以明文存放在应用私有 SharedPreferences 中（MODE_PRIVATE，仅本应用可读），
 * 与市面上多数输入法/笔记类 App 的 WebDAV 实现一致。如需更高安全级别，后续可迁移到
 * EncryptedSharedPreferences。
 */
object UserDictPrefs {
    private const val PREFS_NAME = "user_dict_sync"

    private const val KEY_SERVER = "webdav_server"
    private const val KEY_USERNAME = "webdav_username"
    private const val KEY_PASSWORD = "webdav_password"
    private const val KEY_SYNC_PATH = "webdav_sync_path"
    private const val KEY_LAST_UPLOAD = "last_upload_time"
    private const val KEY_LAST_DOWNLOAD = "last_download_time"
    private const val KEY_ALLOW_HTTP = "webdav_allow_http"
    private const val KEY_SYNC_ETAG_PREFIX = "sync_etag_"

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
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var syncPath: String
        get() = prefs.getString(KEY_SYNC_PATH, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SYNC_PATH, value.trim()).apply()

    var lastUploadTime: Long
        get() = prefs.getLong(KEY_LAST_UPLOAD, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPLOAD, value).apply()

    var lastDownloadTime: Long
        get() = prefs.getLong(KEY_LAST_DOWNLOAD, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_DOWNLOAD, value).apply()

    /** 高级选项：是否允许明文 HTTP 的 WebDAV 服务器（默认不允许，凭据与词典会明文传输）。 */
    var allowHttp: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_HTTP, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_HTTP, value).apply()

    /**
     * 上次同步时某个远端文件的 ETag（按文件名分别记录）。
     * 上传前用它判断远端是否被其他设备改过，避免静默覆盖。
     */
    fun syncETag(fileName: String): String? =
        prefs.getString(KEY_SYNC_ETAG_PREFIX + fileName, null)?.takeIf { it.isNotBlank() }

    fun setSyncETag(fileName: String, etag: String?) {
        val editor = prefs.edit()
        if (etag.isNullOrBlank()) editor.remove(KEY_SYNC_ETAG_PREFIX + fileName)
        else editor.putString(KEY_SYNC_ETAG_PREFIX + fileName, etag)
        editor.apply()
    }

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
