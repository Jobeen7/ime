package com.jobeen.ime.data.manager

import android.content.Context
import java.util.UUID

/**
 * RIME 同步偏好：目录同步的远端文件夹标识（UUID）、已同步文件集合、
 * 键盘 RIME 通知开关、待显示的键盘通知。
 *
 * UUID 按安装生成并持久化：首次读取即生成，之后不再变；重置应用设置
 * 会清掉全部偏好（含 UUID），下次读取生成新 UUID——旧远端文件夹随之
 * 废弃，不自动删除（重置后同步不再往旧文件夹写，也不再从它恢复）。
 */
object RimeSyncPrefs {
    const val PREFS_NAME = "rime_sync"
    private const val KEY_SYNC_FOLDER_UUID = "sync_folder_uuid"
    private const val KEY_NOTICE_ENABLED = "show_rime_notice"
    private const val KEY_PENDING_NOTICE = "pending_notice"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 同步文件夹名：UUID，首次读取时生成并持久化。 */
    fun syncFolderUuid(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_SYNC_FOLDER_UUID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_SYNC_FOLDER_UUID, it).apply()
        }
    }

    /** 同步文件夹名合法当且仅当单段：非空，且不含 /、\ 与空白字符。 */
    fun isValidSyncFolderName(name: String): Boolean {
        if (name.isBlank()) return false
        return name.none { it == '/' || it == '\\' || it.isWhitespace() }
    }

    /**
     * 改写同步文件夹名（RIME 页编辑用）：合法才保存并返回 true，
     * 非法不保存返回 false。只改本机偏好，不动远端旧文件夹；
     * 下次目录同步即以新文件夹为合并对象（词典先 GET 合并再 PUT，
     * 不覆盖丢词；方案文件同步走共享 schemes/，不受文件夹名影响）。
     */
    fun setSyncFolderUuid(context: Context, value: String): Boolean {
        val name = value.trim()
        if (!isValidSyncFolderName(name)) return false
        prefs(context).edit().putString(KEY_SYNC_FOLDER_UUID, name).apply()
        return true
    }

    /** 键盘显示 RIME 通知开关，默认开（只管部署/同步结果通知，不管语音提示）。 */
    fun isNoticeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTICE_ENABLED, true)

    fun setNoticeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTICE_ENABLED, enabled).apply()
    }

    /** 设置侧投递一条待显示通知（键盘下次出现时由输入法服务取走显示）。 */
    fun postNotice(context: Context, message: String) {
        prefs(context).edit().putString(KEY_PENDING_NOTICE, message).apply()
    }

    /** 输入法服务取走待显示通知：开关关时直接丢弃；取后清空（只显示一次）。 */
    fun drainNotice(context: Context): String? {
        val p = prefs(context)
        val pending = p.getString(KEY_PENDING_NOTICE, null)
        if (pending != null) {
            p.edit().remove(KEY_PENDING_NOTICE).apply()
        }
        if (pending.isNullOrBlank()) return null
        if (!isNoticeEnabled(context)) return null
        return pending
    }
}
