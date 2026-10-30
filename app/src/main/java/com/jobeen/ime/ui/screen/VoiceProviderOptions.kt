// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.base.speech.cloud.PluginMeta

/**
 * 语音服务商选项纯逻辑：服务商对话框行＋页内提示判定，不含 Android API，可单测。
 *
 * 选择模型与键盘路由同口径（见 KeyboardWindowView.tryStartCloudVoice）：
 * 首选插件 id 为空＝走本地离线模型；非空＝走该云端插件（仍受开启开关、
 * 密钥、预算、网络门控）。本文件只算“选了什么、状态如何”，不改路由；
 * 密钥只算“有／无”元数据，绝不读值。
 */
enum class VoiceProviderStatus {
    LOCAL_READY,
    LOCAL_NOT_DOWNLOADED,
    PLUGIN_READY,
    PLUGIN_SECRETS_MISSING,
}

data class VoiceProviderRow(
    val key: String,
    val isLocal: Boolean,
    val pluginId: String?,
    val selected: Boolean,
    val status: VoiceProviderStatus,
)

enum class VoiceProviderHint {
    NONE,
    NO_USABLE_ENGINE,
    PLUGIN_UNAVAILABLE,
    CLOUD_NOT_ENABLED,
    PLUGIN_SECRETS_MISSING,
}

/** 对话框行：本地行在首，其后为调用方传入顺序的已装插件。 */
fun voiceProviderRows(
    modelReady: Boolean,
    plugins: List<PluginMeta>,
    preferredId: String?,
    secretsMissingIds: Set<String>,
): List<VoiceProviderRow> {
    val rows = mutableListOf(
        VoiceProviderRow(
            key = "local",
            isLocal = true,
            pluginId = null,
            selected = preferredId == null,
            status = if (modelReady) VoiceProviderStatus.LOCAL_READY
            else VoiceProviderStatus.LOCAL_NOT_DOWNLOADED,
        ),
    )
    plugins.forEach { meta ->
        rows += VoiceProviderRow(
            key = meta.id,
            isLocal = false,
            pluginId = meta.id,
            selected = preferredId == meta.id,
            status = if (meta.id in secretsMissingIds) VoiceProviderStatus.PLUGIN_SECRETS_MISSING
            else VoiceProviderStatus.PLUGIN_READY,
        )
    }
    return rows
}

/**
 * 对话框分组：在线组在前、本地离线组在后（参考对话框的分组顺序），
 * 组内保持 voiceProviderRows 的原顺序；数据层顺序本身不变，只影响呈现。
 */
fun partitionProviderDialogRows(
    rows: List<VoiceProviderRow>,
): Pair<List<VoiceProviderRow>, List<VoiceProviderRow>> {
    val online = rows.filter { !it.isLocal }
    val local = rows.filter { it.isLocal }
    return online to local
}

/** 已装插件中缺密钥（任一声明凭证字段未存）的 id 集合。 */
fun pluginSecretsMissingIds(
    plugins: List<PluginMeta>,
    hasSecret: (pluginId: String, field: String) -> Boolean,
): Set<String> {
    return plugins.filter { meta ->
        meta.authHeaders.any { !hasSecret(meta.id, it.field) }
    }.map { it.id }.toSet()
}

/**
 * 引擎行标签：只写 Jime 元数据与实现能证明的能力，不搬对方标签集。
 * 本地＝流式 Zipformer 按住说话会话；内置云端插件＝WebSocket 流式实现；
 * 用户导入插件的传输方式由脚本自定、元数据未声明，故不标流式；
 * 方言／准确率等对方标签在 Jime 插件元数据中不存在，一律不写。
 */
enum class VoiceProviderTag {
    LOCAL,
    ONLINE,
    STREAMING,
    BUILTIN,
    CUSTOM,
}

/** 标签派生：本地行［本地，流式］；内置插件［在线，流式，内置］；用户插件［在线，自定义］。 */
fun voiceProviderTags(isLocal: Boolean, isBuiltin: Boolean): List<VoiceProviderTag> {
    return if (isLocal) {
        listOf(VoiceProviderTag.LOCAL, VoiceProviderTag.STREAMING)
    } else if (isBuiltin) {
        listOf(VoiceProviderTag.ONLINE, VoiceProviderTag.STREAMING, VoiceProviderTag.BUILTIN)
    } else {
        listOf(VoiceProviderTag.ONLINE, VoiceProviderTag.CUSTOM)
    }
}

/**
 * 页内提示判定（优先级即列出顺序）：
 * 无可用引擎（未选云端且本地未下载）＞已选插件不在已装列表（被删／过期首选）
 * ＞已选云端但开启开关关着＞已选插件缺密钥；其余为无提示。
 */
fun voiceProviderHint(
    preferredId: String?,
    modelReady: Boolean,
    optIn: Boolean,
    secretsMissingIds: Set<String>,
    preferredInstalled: Boolean,
): VoiceProviderHint = when {
    preferredId == null && !modelReady -> VoiceProviderHint.NO_USABLE_ENGINE
    preferredId != null && !preferredInstalled -> VoiceProviderHint.PLUGIN_UNAVAILABLE
    preferredId != null && !optIn -> VoiceProviderHint.CLOUD_NOT_ENABLED
    preferredId != null && preferredId in secretsMissingIds -> VoiceProviderHint.PLUGIN_SECRETS_MISSING
    else -> VoiceProviderHint.NONE
}
