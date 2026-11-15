// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jobeen.ime.R
import com.jobeen.ime.ui.MainActivity
import timber.log.Timber

/**
 * 系统通知渠道（WO52 13.1＋8.1）：
 * 应用自有 3 个渠道——remote_transfer（RemoteTransferService，
 * IMPORTANCE_LOW，通知 ID 1002）、web_server（WebServerService，
 * IMPORTANCE_LOW）与 script（WO52 8.1 $userNotification，
 * IMPORTANCE_DEFAULT，使用户看得见脚本通知）。
 * 另有 1 个第三方库自带（com.google.android.gms.availability），
 * 非应用自有，不建。
 *
 * 降级行为：Android 13+ 未授予 POST_NOTIFICATIONS 时，前台服务
 * 仍可跑（前台服务通知不受该权限门控，系统照常显示）；完成通知
 * 等普通通知静默不发（发前先查权限，见 [canPostNotifications]）。
 * 拒绝权限不拦服务、不弹重复提示。
 */
object NotificationChannels {
    const val REMOTE_TRANSFER = "remote_transfer"
    const val WEB_SERVER = "web_server"

    /** 脚本通知渠道（WO52 8.1 $userNotification）：IMPORTANCE_DEFAULT，使用户看得见脚本通知。 */
    const val SCRIPT = "script"

    /** RemoteTransferService 前台通知 ID（单子定值）。 */
    const val REMOTE_TRANSFER_NOTIFICATION_ID = 1002

    /** WebServerService 前台通知 ID（单子未定，取 1003，与 1002 区分）。 */
    const val WEB_SERVER_NOTIFICATION_ID = 1003

    /** 部署完成通知 ID（remote_transfer 渠道，与前台通知区分开）。 */
    const val DEPLOY_COMPLETE_NOTIFICATION_ID = 1010

    /** 同步完成通知 ID（remote_transfer 渠道，与前台通知区分开）。 */
    const val SYNC_COMPLETE_NOTIFICATION_ID = 1011

    /** 渠道定义（纯数据，可单测）：id 与重要性。 */
    data class ChannelSpec(val id: String, val importance: Int)

    /** 应用自有渠道全表：三条，remote_transfer 与 web_server 为 IMPORTANCE_LOW，script 为 IMPORTANCE_DEFAULT。 */
    fun channelSpecs(): List<ChannelSpec> = listOf(
        ChannelSpec(REMOTE_TRANSFER, NotificationManager.IMPORTANCE_LOW),
        ChannelSpec(WEB_SERVER, NotificationManager.IMPORTANCE_LOW),
        ChannelSpec(SCRIPT, NotificationManager.IMPORTANCE_DEFAULT),
    )

    /** POST_NOTIFICATIONS 运行时权限是否适用（Android 13+ 才有该权限）。 */
    fun isPermissionRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 33

    /**
     * 普通通知能否发出（纯判定，可单测）：13 以下恒可发；
     * 13+ 须已授予 POST_NOTIFICATIONS。服务仍可跑与否与此无关。
     */
    fun canPostNotifications(sdkInt: Int, granted: Boolean): Boolean =
        !isPermissionRequired(sdkInt) || granted

    /** 本进程是否已授予 POST_NOTIFICATIONS（13 以下恒 true）。 */
    fun isPostNotificationsGranted(context: Context): Boolean {
        if (!isPermissionRequired()) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 建渠道（幂等；已建的同 id 渠道不覆盖用户改过的设置）。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        val names = mapOf(
            REMOTE_TRANSFER to context.getString(R.string.notif_channel_remote_transfer),
            WEB_SERVER to context.getString(R.string.notif_channel_web_server),
            SCRIPT to context.getString(R.string.notif_channel_script),
        )
        for (spec in channelSpecs()) {
            if (manager.getNotificationChannel(spec.id) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(spec.id, names.getValue(spec.id), spec.importance),
                )
            }
        }
    }

    /** 完成通知（非常驻：可划掉，点按回设置主页）。 */
    fun completionNotification(
        context: Context,
        channelId: String,
        title: String,
        text: String,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
    }
}

/**
 * 同步/部署完成通知去重器（WO52 13.1；MEMORY 2026-11-12 待修复①
 * 合并验收）：同一完成事件（runKey＝单次操作实例＋结果）只发一次。
 * 调用方保证一处完成点只调一次；此处再挡一次——两路（设置页＋
 * 键盘）各调一次同一 runKey 时，第二路被静默拦下，不新增重复。
 * 投递动作由调用方以 lambda 传入，单测可注入计数 fake。
 */
class CompletionNotifier {
    private val notified = mutableSetOf<String>()

    /**
     * 同一 runKey 首次返回 true 并执行投递；重复返回 false，不投递。
     * 调用方须保证 runKey 在一次操作内稳定、跨操作不同。
     */
    @Synchronized
    fun notifyOnce(runKey: String, post: () -> Unit): Boolean {
        if (!notified.add(runKey)) return false
        post()
        return true
    }
}

/** 进程级完成通知器（唯一完成通知出口）。 */
object CompletionNotifications {
    private val notifier = CompletionNotifier()

    /**
     * 发完成通知：同一 runKey 只发一次；未授 POST_NOTIFICATIONS
     * 时静默跳过（服务与操作本身不受影响）。
     */
    fun post(
        context: Context,
        runKey: String,
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
    ): Boolean {
        if (!NotificationChannels.isPostNotificationsGranted(context)) {
            Timber.d("Completion notification skipped (no POST_NOTIFICATIONS): $runKey")
            return false
        }
        NotificationChannels.ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return false
        return notifier.notifyOnce(runKey) {
            manager.notify(
                notificationId,
                NotificationChannels.completionNotification(context, channelId, title, text),
            )
        }
    }
}
