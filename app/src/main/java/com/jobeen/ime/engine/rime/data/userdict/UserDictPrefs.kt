package com.jobeen.ime.engine.rime.data.userdict

import android.content.Context
import com.jobeen.ime.base.util.SecretStore
import com.jobeen.ime.base.util.appContext
import timber.log.Timber

/**
 * 用户词典 WebDAV 同步的配置存储。
 *
 * 密码经 [SecretStore]（Android Keystore，AES-GCM）加密后存入私有
 * SharedPreferences，落盘无明文。旧版本遗留的明文密码在首次读取时自动
 * 加密回写迁移；Keystore 不可用时 setter 不落盘并记录日志，getter 返回空，
 * 绝不回退明文存储。备份/还原走 BackupManager 的特殊通道（备份包整体
 * 加密，密码以明文进包、还原时在本机重新加密），不经过本文件的原始值。
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
        get() {
            val stored = prefs.getString(KEY_PASSWORD, "").orEmpty()
            if (stored.isEmpty()) return ""
            if (SecretStore.isEncrypted(stored)) {
                // 密钥丢失/损坏时解密失败：返回空而不是把密文当密码用
                return SecretStore.decrypt(stored).orEmpty()
            }
            // 存量明文：立即加密回写完成迁移，本次返回原值不影响使用
            val encrypted = SecretStore.encrypt(stored)
            if (encrypted != null) {
                prefs.edit().putString(KEY_PASSWORD, encrypted).apply()
            } else {
                Timber.w("WebDAV password stored in plaintext could not be migrated (Keystore unavailable)")
            }
            return stored
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
