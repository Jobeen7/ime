package com.jobeen.ime.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import timber.log.Timber

/**
 * 外部调用面（WO52 4.1）：供其他应用（须与 Jime 同签名）触发
 * 部署／同步／写剪贴板。
 *
 * 校验（对端外部调用面无校验是缺陷，不照搬）：
 * - 自定义 signature 级权限
 *   `com.jobeen.ime.permission.EXTERNAL_ACTION`：Activity 声明
 *   android:permission，未授权调用方由系统直接拒绝启动
 *   （调用方收 SecurityException，本 Activity 不会被唤起，
 *   故无应用内日志可记——拒绝发生在系统层）；
 * - action 必须为三者之一，否则静默丢弃＋记日志；
 * - SET_CLIPBOARD 的 extra "text" 缺失或超 4096 字符拒绝。
 *
 * 部署／同步走现有路径（FabActions.deploy／FabActions.sync，
 * 不另写部署逻辑），在进程级 appScope 里跑；处理完即 finish()。
 * 同步未配置时 FabActions.sync 已弹提示，本口不另开设置页
 * （外部调用无界面可跳）。
 */
class ExternalActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = ExternalActionDispatcher.resolve(
            intent?.action,
            intent?.getStringExtra(EXTRA_TEXT),
        )
        if (resolved == null) {
            Timber.w("ExternalAction: rejected intent action=${intent?.action}")
            finish()
            return
        }
        when (resolved) {
            is ExternalActionDispatcher.Resolved.Deploy -> {
                FabActions.deploy(applicationContext, appScope)
            }
            is ExternalActionDispatcher.Resolved.Sync -> {
                FabActions.sync(
                    applicationContext,
                    appScope,
                    sessionProvider = { RimeDaemon.createSession(SESSION_NAME) },
                    onNotConfigured = { },
                )
            }
            is ExternalActionDispatcher.Resolved.SetClipboard -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("text", resolved.text))
            }
        }
        finish()
    }

    companion object {
        const val ACTION_DEPLOY = "com.jobeen.ime.action.DEPLOY"
        const val ACTION_SYNC = "com.jobeen.ime.action.SYNC"
        const val ACTION_SET_CLIPBOARD = "com.jobeen.ime.action.SET_CLIPBOARD"
        const val EXTRA_TEXT = "text"

        /** SET_CLIPBOARD 的 text 上限（单子定值 4096 字符）。 */
        const val MAX_CLIPBOARD_TEXT_LENGTH = 4096

        const val PERMISSION_EXTERNAL_ACTION = "com.jobeen.ime.permission.EXTERNAL_ACTION"

        private const val SESSION_NAME = "external-action"
    }
}

object ExternalActionDispatcher {
    sealed interface Resolved {
        data object Deploy : Resolved
        data object Sync : Resolved
        data class SetClipboard(val text: String) : Resolved
    }

    /** 纯校验：action 不符、text 缺失／超限 → null（丢弃）。 */
    fun resolve(action: String?, text: String?): Resolved? {
        return when (action) {
            ExternalActionActivity.ACTION_DEPLOY -> Resolved.Deploy
            ExternalActionActivity.ACTION_SYNC -> Resolved.Sync
            ExternalActionActivity.ACTION_SET_CLIPBOARD -> {
                val value = text ?: return null
                if (value.length > ExternalActionActivity.MAX_CLIPBOARD_TEXT_LENGTH) return null
                Resolved.SetClipboard(value)
            }
            else -> null
        }
    }
}
