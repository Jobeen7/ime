package com.jobeen.ime.base.speech.cloud

import timber.log.Timber

/**
 * 内置云端语音插件：应用自带 assets 脚本，首次使用前种子安装到插件库，
 * 无需用户导入。身份即 builtin.* 命名空间（用户导入禁占，见契约保留 id）。
 * 种子安装不走用户导入确认口，但插件在管理界面仍受同一套门约束：
 * 隐私告知＋opt-in 默认关＋逐项域名／能力清单＋密钥只显已填／未填，
 * 未开启／未填密钥一律静默离线。
 */
object BuiltinPlugins {
    data class Spec(val id: String, val assetPath: String)

    val SPECS = listOf(
        Spec("builtin.doubao", "cloud_plugins/builtin.doubao/plugin.js"),
        Spec("builtin.nls", "cloud_plugins/builtin.nls/plugin.js"),
        Spec("builtin.tencent", "cloud_plugins/builtin.tencent/plugin.js"),
    )

    /** 已下架的内置插件：种子安装时连同残留一并清除（目录＋首选＋设置＋密钥）。 */
    val REMOVED = listOf("builtin.qwen")

    fun isBuiltin(id: String): Boolean = id.startsWith("builtin.")

    fun pruneRemoved(store: PluginStore, secrets: SecretStore?) {
        for (id in REMOVED) {
            if (store.get(id) != null) {
                store.uninstall(id, secrets)
            } else {
                // 目录已清但密钥残留时一并清掉；无残留时为无操作。
                runCatching { secrets?.deleteForPlugin(id) }
                    .onFailure {
                        Timber.w("cloudVoice prune secrets failed plugin=%s err=%s", id, it.javaClass.simpleName)
                    }
            }
        }
    }

    /**
     * 种子安装全部内置插件：单个失败只记元数据日志，不影响其余项，
     * 永不抛异常（调用点在 UI／路由路径上）。
     * [readAsset] 由调用方提供（生产读 assets，单测读内存）。
     */
    fun ensure(
        store: PluginStore,
        readAsset: (String) -> ByteArray?,
        secrets: SecretStore? = null,
    ): List<PluginStore.SeedResult> {
        pruneRemoved(store, secrets)
        val out = ArrayList<PluginStore.SeedResult>()
        for (spec in SPECS) {
            val bytes = runCatching { readAsset(spec.assetPath) }.getOrNull()
            if (bytes == null) {
                Timber.w("cloudVoice builtin asset missing id=%s", spec.id)
                continue
            }
            val result = runCatching { store.seedBuiltin(bytes) }.getOrNull()
            if (result == null) {
                Timber.w("cloudVoice builtin seed failed id=%s", spec.id)
                continue
            }
            out.add(result)
        }
        return out
    }
}
