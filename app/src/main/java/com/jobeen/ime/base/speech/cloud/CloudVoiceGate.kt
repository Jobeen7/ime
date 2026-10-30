package com.jobeen.ime.base.speech.cloud

import android.content.Context
import android.net.ConnectivityManager

/**
 * 云端语音路由门：离线永远默认，云端只在用户显式选好插件且
 * 全部前置条件通过时才走。任一条件不满足即回离线或提示，
 * 绝不静默把“选了云端却不可用”当成离线录下来。
 *
 * 判定顺序（自上而下，首个不满足即定）：
 * 未开启 → 未选插件 → 插件缺失 → 超时停用 → 密钥未填 → 无网络。
 */
enum class CloudFailure {
    NOT_OPTED_IN,
    NO_PREFERRED,
    PLUGIN_MISSING,
    BUDGET_DISABLED,
    SECRET_MISSING,
    NETWORK_UNAVAILABLE,
}

sealed interface CloudVoiceGateResult {
    data object Cloud : CloudVoiceGateResult
    data class Offline(val reason: CloudFailure) : CloudVoiceGateResult
}

object CloudVoiceGate {
    fun decide(
        optIn: Boolean,
        preferredId: String?,
        pluginInstalled: Boolean,
        budgetDisabled: Boolean,
        secretsMissing: Boolean,
        networkAvailable: Boolean,
    ): CloudVoiceGateResult {
        if (!optIn) return CloudVoiceGateResult.Offline(CloudFailure.NOT_OPTED_IN)
        if (preferredId == null) return CloudVoiceGateResult.Offline(CloudFailure.NO_PREFERRED)
        if (!pluginInstalled) return CloudVoiceGateResult.Offline(CloudFailure.PLUGIN_MISSING)
        if (budgetDisabled) return CloudVoiceGateResult.Offline(CloudFailure.BUDGET_DISABLED)
        if (secretsMissing) return CloudVoiceGateResult.Offline(CloudFailure.SECRET_MISSING)
        if (!networkAvailable) return CloudVoiceGateResult.Offline(CloudFailure.NETWORK_UNAVAILABLE)
        return CloudVoiceGateResult.Cloud
    }

    /** 按下录音键那一刻的网络可用性：仅判有无网络，不判厂商可达。 */
    fun networkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        return manager.activeNetwork != null
    }
}
