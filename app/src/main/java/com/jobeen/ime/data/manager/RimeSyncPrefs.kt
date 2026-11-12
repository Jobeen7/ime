package com.jobeen.ime.data.manager

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * RIME 同步偏好：目录同步的远端文件夹标识（UUID）、已同步文件集合、
 * 键盘 RIME 通知开关、待显示的键盘通知。
 *
 * UUID 按安装生成并持久化：首次读取即生成，之后不再变；重置应用设置
 * 会清掉全部偏好（含 UUID），下次读取生成新 UUID——旧远端文件夹随之
 * 废弃，不自动删除（重置后同步不再往旧文件夹写，也不再从它恢复）。
 *
 * 待显示通知是 FIFO 队列（多条按序投递、取后删除）：旧实现是单值槽，
 * 后一条直接覆盖前一条，键盘下次出现前若连发两条通知，前一条永久
 * 丢失。队列上限 10 条，超限丢最旧（最新部署／同步结果优先投递）。
 * 旧单值键 [KEY_PENDING_NOTICE] 只作迁移来源：首次读写时并入队列
 * 并清除，不再单独使用。
 */
object RimeSyncPrefs {
    const val PREFS_NAME = "rime_sync"
    private const val KEY_SYNC_FOLDER_UUID = "sync_folder_uuid"
    private const val KEY_NOTICE_ENABLED = "show_rime_notice"
    private const val KEY_PENDING_NOTICE = "pending_notice"
    private const val KEY_PENDING_NOTICES = "pending_notices"
    private const val MAX_PENDING_NOTICES = 10

    /** UUID 生成锁：读—生成—写必须原子，否则两路并发各生成一个 UUID。 */
    private val uuidLock = Any()

    /** 通知队列锁：读—改—写必须原子，否则并发投递／取走会互相覆盖。 */
    private val noticeLock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 同步文件夹名：UUID，首次读取时生成并持久化。 */
    fun syncFolderUuid(context: Context): String {
        val p = prefs(context)
        synchronized(uuidLock) {
            p.getString(KEY_SYNC_FOLDER_UUID, null)?.let { return it }
            val uuid = UUID.randomUUID().toString()
            // 同步提交：调用方拿到 UUID 后即以它命名远端文件夹，
            // apply 的异步落盘若在进程被杀前未完成，下次读取会再
            // 生成另一个 UUID、旧文件夹随之废弃——生成与返回必须
            // 在同一次持久化内完成。提交失败仍返回本次 UUID（内存
            // 已更新，本进程内读写一致；仅进程被杀才可能丢）。
            p.edit().putString(KEY_SYNC_FOLDER_UUID, uuid).commit()
            return uuid
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
        if (message.isBlank()) return
        synchronized(noticeLock) {
            val p = prefs(context)
            val queue = readNoticesLocked(p).toMutableList()
            queue.add(message)
            while (queue.size > MAX_PENDING_NOTICES) queue.removeAt(0)
            writeNoticesLocked(p, queue)
        }
    }

    /**
     * 输入法服务取走一条待显示通知：取队首一条、取后删除（只显示一次，
     * 与 ui 侧“FAB 触发的结果只 Toast 一次”同口径，不重复投递）；
     * 开关关时取走即丢弃，不显示。
     */
    fun drainNotice(context: Context): String? {
        val pending = synchronized(noticeLock) {
            val p = prefs(context)
            val queue = readNoticesLocked(p).toMutableList()
            if (queue.isEmpty()) return null
            val first = queue.removeAt(0)
            writeNoticesLocked(p, queue)
            first
        }
        if (pending.isBlank()) return null
        if (!isNoticeEnabled(context)) return null
        return pending
    }

    /**
     * 读通知队列（调用方须持 [noticeLock]）：旧单值键的遗留通知并入
     * 队首；队列内容损坏按空队列处理（通知是尽力而为的提示，不值得
     * 为此留脏数据阻塞后续投递）。
     */
    private fun readNoticesLocked(p: SharedPreferences): List<String> {
        val queue = ArrayList<String>()
        p.getString(KEY_PENDING_NOTICE, null)
            ?.takeIf { it.isNotBlank() }?.let { queue.add(it) }
        p.getString(KEY_PENDING_NOTICES, null)?.takeIf { it.isNotBlank() }?.let { stored ->
            decodeNotices(stored).filter { it.isNotBlank() }.forEach { queue.add(it) }
        }
        return queue
    }

    /**
     * 队列的纯字符串编码（不用 org.json：JVM 单测跑在 android.jar 桩上，
     * org.json 方法未 mock，直接调就抛；通知文本本就不必走 JSON）。
     * 格式：每条为 `<字符数>:<原文>` 依次拼接；原文可含任意字符（含冒号、
     * 换行、反斜杠），长度前缀保证无歧义。字符数按 UTF-16 代码单元计，
     * 与 substring 的取法一致。
     * 解码遇损坏内容返回空表（调用方按空队列处理，不抛）。
     */
    private fun encodeNotices(queue: List<String>): String =
        buildString {
            for (message in queue) {
                append(message.length).append(':').append(message)
            }
        }

    private fun decodeNotices(stored: String): List<String> {
        val queue = ArrayList<String>()
        var i = 0
        while (i < stored.length) {
            val colon = stored.indexOf(':', i)
            if (colon < 0) return emptyList()
            val len = stored.substring(i, colon).toIntOrNull() ?: return emptyList()
            if (len < 0 || colon + 1 + len > stored.length) return emptyList()
            queue.add(stored.substring(colon + 1, colon + 1 + len))
            i = colon + 1 + len
        }
        return queue
    }

    /**
     * 写通知队列（调用方须持 [noticeLock]）：旧单值键一并清除。
     * 同步提交：取走即显示，删除落盘必须先于 Toast——否则进程在
     * apply 刷盘前被杀，同一条通知下次启动会再次弹出（重复投递）。
     */
    private fun writeNoticesLocked(p: SharedPreferences, queue: List<String>) {
        val editor = p.edit()
        editor.remove(KEY_PENDING_NOTICE)
        if (queue.isEmpty()) {
            editor.remove(KEY_PENDING_NOTICES)
        } else {
            editor.putString(KEY_PENDING_NOTICES, encodeNotices(queue))
        }
        editor.commit()
    }
}
