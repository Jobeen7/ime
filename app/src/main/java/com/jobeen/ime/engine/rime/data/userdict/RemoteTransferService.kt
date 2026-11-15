package com.jobeen.ime.engine.rime.data.userdict

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jobeen.ime.R
import com.jobeen.ime.base.notify.NotificationChannels
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 远程传输前台服务（WO52 4.5）。
 *
 * - 前台 dataSync，通知 id 1002，渠道 remote_transfer
 *   （IMPORTANCE_LOW，见 [NotificationChannels]），START_STICKY，
 *   不可绑定（onBind 返回 null）。
 * - 后端走现有 WebDavSync（不另写传输协议）；与周期同步的互斥
 *   靠 WebDavSync.syncMutex（两路都经它），另加执行器 jobMutex
 *   保证本服务一次只跑一路。
 * - 进度通知：运行中为持续通知（进行中），结束时同一 id 更新
 *   为结果文案；词条数取自 WebDavSync 返回值，传输中无字节级
 *   进度可报（WebDavSync 只在结束时返回总数）。
 * - 入口：用户词典页手动上传／下载（见 UserDictActivity）。
 *   FAB 同步与周期同步不动，不走本服务。
 */
class RemoteTransferService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runner: RemoteTransferRunner

    override fun onCreate() {
        super.onCreate()
        val session = RimeDaemon.createSession(SESSION_NAME)
        runner = RemoteTransferRunner(WebDavTransferBackend(session))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val dict = intent?.getStringExtra(EXTRA_DICT)
        if ((action != ACTION_UPLOAD && action != ACTION_DOWNLOAD) || dict.isNullOrEmpty()) {
            Timber.w("RemoteTransferService: bad intent action=$action dict=$dict")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val kind = if (action == ACTION_UPLOAD) {
            RemoteTransferKind.UPLOAD
        } else {
            RemoteTransferKind.DOWNLOAD
        }
        val remoteFile = intent.getStringExtra(EXTRA_REMOTE_FILE)
        startForeground(
            NotificationChannels.REMOTE_TRANSFER_NOTIFICATION_ID,
            runningNotification(kind, dict),
        )
        serviceScope.launch {
            val done = runner.run(kind, dict, remoteFile)
            notifyResult(done)
            stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { RimeDaemon.destroySession(SESSION_NAME) }
        super.onDestroy()
    }

    private fun runningNotification(kind: RemoteTransferKind, dict: String): Notification {
        val text = if (kind == RemoteTransferKind.UPLOAD) {
            getString(R.string.user_dict_uploading)
        } else {
            getString(R.string.user_dict_downloading_from, dict)
        }
        return NotificationCompat.Builder(this, NotificationChannels.REMOTE_TRANSFER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_remote_transfer_title))
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun notifyResult(done: RemoteTransferState) {
        val text = when (done.status) {
            RemoteTransferStatus.SUCCESS -> if (done.kind == RemoteTransferKind.UPLOAD) {
                getString(
                    R.string.user_dict_uploaded_count,
                    done.count ?: 0,
                    WebDavSync.remoteFileName(done.dict),
                )
            } else {
                val count = done.count ?: 0
                if (count == 0) {
                    getString(R.string.user_dict_no_words_in_file)
                } else {
                    getString(R.string.user_dict_downloaded_imported, count)
                }
            }
            RemoteTransferStatus.FAILED ->
                getString(R.string.user_dict_op_failed_title)
            RemoteTransferStatus.RUNNING -> return
        }
        val notification = NotificationCompat.Builder(
            this,
            NotificationChannels.REMOTE_TRANSFER,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_remote_transfer_title))
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NotificationChannels.REMOTE_TRANSFER_NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_UPLOAD = "com.jobeen.ime.action.REMOTE_TRANSFER_UPLOAD"
        const val ACTION_DOWNLOAD = "com.jobeen.ime.action.REMOTE_TRANSFER_DOWNLOAD"
        const val EXTRA_DICT = "dict"
        const val EXTRA_REMOTE_FILE = "remote_file"
        private const val SESSION_NAME = "remote-transfer"

        fun startUpload(context: Context, dict: String) {
            val intent = Intent(context, RemoteTransferService::class.java).apply {
                action = ACTION_UPLOAD
                putExtra(EXTRA_DICT, dict)
            }
            context.startForegroundService(intent)
        }

        fun startDownload(context: Context, dict: String, remoteFile: String) {
            val intent = Intent(context, RemoteTransferService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_DICT, dict)
                putExtra(EXTRA_REMOTE_FILE, remoteFile)
            }
            context.startForegroundService(intent)
        }
    }
}
