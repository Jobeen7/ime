package com.jobeen.ime.base.speech.cloud

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import timber.log.Timber
import java.io.File

/**
 * 云端语音插件库：脚本文件＋元数据＋沙箱目录＋偏好，一处归口。
 *
 * 目录形态（filesDir 下）：
 * - cloud_plugins/<id>/plugin.js（脚本原文）
 * - cloud_plugins/<id>/meta.json（导入时取到的元数据）
 * - cloud_plugins/<id>/files/（插件自有沙箱目录，files 桥接只到这里）
 *
 * 导入授权门：新装与任何清单变化都必须经用户确认（对话框由 UI 层
 * 按 [ImportResult.NeedsConfirmation] 展示）；只有清单完全相同
 * 的重导入才静默升级（只换脚本与元数据文件）。
 */
class PluginStore(
    private val filesDir: File,
    private val prefs: PrefsStore,
) {
    data class InstalledPlugin(
        val meta: PluginMeta,
        val scriptFile: File,
        val sandboxDir: File,
    )

    sealed interface ImportResult {
        data class Installed(val meta: PluginMeta, val upgraded: Boolean) : ImportResult
        data class NeedsConfirmation(
            val meta: PluginMeta,
            val diff: PluginMetaDiff?,
        ) : ImportResult

        data class Failed(val error: PluginImportError) : ImportResult
    }

    fun pluginsDir(): File = File(filesDir, "cloud_plugins")

    fun pluginDir(id: String): File = File(pluginsDir(), id)

    fun sandboxDir(id: String): File = File(pluginDir(id), "files")

    fun list(): List<PluginMeta> {
        val dir = pluginsDir()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.mapNotNull { readMeta(it) }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    fun get(id: String): InstalledPlugin? {
        val dir = pluginDir(id)
        val meta = readMeta(dir) ?: return null
        val script = File(dir, SCRIPT_FILE)
        if (!script.isFile) return null
        return InstalledPlugin(meta, script, sandboxDir(id))
    }

    /**
     * 导入第一步：只读元数据运行＋校验，不写任何文件。
     * 返回 [ImportResult.NeedsConfirmation] 时由 UI 层展示确认对话框，
     * 用户确认后再调 [confirmInstall] 落盘。
     */
    fun importScript(scriptBytes: ByteArray): ImportResult {
        val meta = when (val inspected = inspectScript(scriptBytes)) {
            is InspectResult.Ok -> inspected.meta
            is InspectResult.Failed -> return ImportResult.Failed(inspected.error)
        }
        val existing = get(meta.id)
        if (existing == null) return ImportResult.NeedsConfirmation(meta, null)
        val diff = PluginContract.diff(existing.meta, meta)
        if (diff.isIdentical) {
            writeInstall(meta, scriptBytes)
            return ImportResult.Installed(meta, upgraded = true)
        }
        return ImportResult.NeedsConfirmation(meta, diff)
    }

    sealed interface SeedResult {
        data class Seeded(val meta: PluginMeta) : SeedResult
        data class Updated(val meta: PluginMeta, val reselectNeeded: Boolean) : SeedResult
        data object Unchanged : SeedResult
        data class Failed(val error: PluginImportError) : SeedResult
    }

    /**
     * 内置插件种子安装（不走用户导入确认口）：只接受 builtin.* 身份，
     * 缺失即装；脚本或元数据变化即升级；域名／能力／设置／凭证头变化
     * 且该插件正被首选时清首选（清单变化不静默沿用旧授权，用户须在
     * 新清单下重新选择）。调用方须保证脚本来自应用自带 assets。
     */
    fun seedBuiltin(scriptBytes: ByteArray): SeedResult {
        val meta = when (val inspected = inspectScript(scriptBytes, allowReservedIds = true)) {
            is InspectResult.Ok -> inspected.meta
            is InspectResult.Failed -> return SeedResult.Failed(inspected.error)
        }
        if (!meta.id.startsWith("builtin.")) {
            return SeedResult.Failed(PluginImportError.INVALID_ID)
        }
        val existing = get(meta.id)
        if (existing == null) {
            writeInstall(meta, scriptBytes)
            return SeedResult.Seeded(meta)
        }
        val sameScript = runCatching {
            existing.scriptFile.readBytes().contentEquals(scriptBytes)
        }.getOrDefault(false)
        if (sameScript && existing.meta == meta) return SeedResult.Unchanged
        val diff = PluginContract.diff(existing.meta, meta)
        val metaChanged = diff.hostsAdded.isNotEmpty() || diff.hostsRemoved.isNotEmpty() ||
            diff.capsAdded.isNotEmpty() || diff.capsRemoved.isNotEmpty() ||
            diff.settingsAdded.isNotEmpty() || diff.settingsRemoved.isNotEmpty() ||
            diff.authAdded.isNotEmpty() || diff.authRemoved.isNotEmpty()
        val wasPreferred = getPreferred() == meta.id
        writeInstall(meta, scriptBytes)
        if (metaChanged && wasPreferred) setPreferred(null)
        // 脚本／元数据已更新：旧脚本的停用标记不再适用新脚本，清一次；
        // 新脚本再失败仍会重标记（安全）。未更新（Unchanged）不清除。
        if (isBudgetDisabled(meta.id)) setBudgetDisabled(meta.id, false)
        return SeedResult.Updated(meta, metaChanged && wasPreferred)
    }

    /** 只读元数据运行＋校验（导入与种子安装共用，不写任何文件）。 */
    private sealed interface InspectResult {
        data class Ok(val meta: PluginMeta) : InspectResult
        data class Failed(val error: PluginImportError) : InspectResult
    }

    private fun inspectScript(
        scriptBytes: ByteArray,
        allowReservedIds: Boolean = false,
    ): InspectResult {
        if (scriptBytes.size > PluginContract.MAX_SCRIPT_BYTES) {
            return InspectResult.Failed(PluginImportError.TOO_LARGE)
        }
        val source = scriptBytes.toString(Charsets.UTF_8)
        val meta = when (val run = runMetadataOnly(source)) {
            is MetaRunResult.Ok -> run.meta
            is MetaRunResult.NoRegister -> return InspectResult.Failed(PluginImportError.NO_PLUGIN)
            is MetaRunResult.Malformed -> return InspectResult.Failed(PluginImportError.LOAD_FAILED)
        }
        val error = PluginContract.validate(meta, allowReservedIds)
        if (error != null) return InspectResult.Failed(error)
        return InspectResult.Ok(meta)
    }

    /** 导入第二步：用户已确认，落盘脚本＋元数据（幂等覆盖）。 */
    fun confirmInstall(meta: PluginMeta, scriptBytes: ByteArray): InstalledPlugin {
        writeInstall(meta, scriptBytes)
        return get(meta.id) ?: error("install did not persist")
    }

    /**
     * 卸载：清脚本目录（含沙箱文件）＋首选＋插件设置＋密钥别名。
     * 各项各自幂等，任一项失败不影响其余项（只记元数据日志）。
     */
    fun uninstall(id: String, secrets: SecretStore?) {
        runCatching { pluginDir(id).deleteRecursively() }
            .onFailure {
                Timber.w("cloudVoice uninstall dir failed plugin=%s err=%s", id, it.javaClass.simpleName)
            }
        if (getPreferred() == id) setPreferred(null)
        val settingsPrefix = SETTINGS_PREF_PREFIX + id + "_"
        for (key in prefs.keys()) {
            if (key.startsWith(settingsPrefix)) prefs.remove(key)
        }
        if (isBudgetDisabled(id)) setBudgetDisabled(id, false)
        runCatching { secrets?.deleteForPlugin(id) }
            .onFailure {
                Timber.w("cloudVoice uninstall secrets failed plugin=%s err=%s", id, it.javaClass.simpleName)
            }
    }

    fun getPreferred(): String? = prefs.getString(KEY_PREFERRED, null)

    fun setPreferred(id: String?) {
        prefs.putString(KEY_PREFERRED, id)
    }

    fun isOptIn(): Boolean = prefs.getBoolean(KEY_OPT_IN, false)

    fun setOptIn(value: Boolean) {
        prefs.putBoolean(KEY_OPT_IN, value)
    }

    fun isBudgetDisabled(id: String): Boolean =
        prefs.getBoolean(KEY_BUDGET_DISABLED_PREFIX + id, false)

    fun setBudgetDisabled(id: String, value: Boolean) {
        prefs.putBoolean(KEY_BUDGET_DISABLED_PREFIX + id, value)
    }

    /** 插件非密钥设置（密钥字段不在此口，见 SecretStore）。 */
    fun getSettings(id: String): Map<String, String> {
        val meta = get(id)?.meta ?: return emptyMap()
        val prefix = SETTINGS_PREF_PREFIX + id + "_"
        val out = LinkedHashMap<String, String>()
        for (decl in meta.settings) {
            if (decl.type == SettingType.SECRET) continue
            val value = prefs.getString(prefix + decl.key, null) ?: decl.defaultValue
            out[decl.key] = value
        }
        return out
    }

    fun setSetting(id: String, key: String, value: String) {
        prefs.putString(SETTINGS_PREF_PREFIX + id + "_" + key, value)
    }

    private fun writeInstall(meta: PluginMeta, scriptBytes: ByteArray) {
        val dir = pluginDir(meta.id)
        dir.mkdirs()
        File(dir, SCRIPT_FILE).writeBytes(scriptBytes)
        File(dir, META_FILE).writeText(json.encodeToString(meta))
        sandboxDir(meta.id).mkdirs()
    }

    private fun readMeta(dir: File): PluginMeta? {
        val file = File(dir, META_FILE)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<PluginMeta>(file.readText()) }
            .onFailure {
                Timber.w("cloudVoice meta unreadable dir=%s err=%s", dir.name, it.javaClass.simpleName)
            }
            .getOrNull()
    }

    private sealed interface MetaRunResult {
        data class Ok(val meta: PluginMeta) : MetaRunResult
        data object NoRegister : MetaRunResult
        data object Malformed : MetaRunResult
    }

    /**
     * 仅读元数据运行：只给 register 捕获，不给任何桥接（net／cb／files
     * 一律不存在），预算 1000ms。脚本在此阶段做不了任何事，
     * 只能声明元数据。
     */
    private fun runMetadataOnly(source: String): MetaRunResult {
        return try {
            RhinoSession.create().use { session ->
                session.eval(source, "plugin-meta", METADATA_BUDGET_MS)
                val arg = session.readRegisterArg() ?: return MetaRunResult.NoRegister
                val meta = metaFromRegisterArg(arg) ?: return MetaRunResult.Malformed
                MetaRunResult.Ok(meta)
            }
        } catch (e: Exception) {
            // 只记异常类名，绝不记脚本源码与原始报错文本。
            Timber.w("cloudVoice metadata run failed err=%s", e.javaClass.simpleName)
            MetaRunResult.Malformed
        }
    }

    companion object {
        /** 插件偏好 SharedPreferences 名（管理界面与键盘路由共用同一份）。 */
        const val PREFS_NAME = "cloud_voice"

        const val SCRIPT_FILE = "plugin.js"
        const val META_FILE = "meta.json"

        private const val METADATA_BUDGET_MS = 1000L
        private const val KEY_PREFERRED = "preferred_plugin"
        private const val KEY_OPT_IN = "opt_in"
        private const val KEY_BUDGET_DISABLED_PREFIX = "budget_disabled_"
        private const val SETTINGS_PREF_PREFIX = "plugin_settings_"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * register 参数形态（Jime 插件契约，单文件自包含）：
         * { id, version, hosts[], features[{type, sampleRate?}],
         *   settings[{key, type, label, options?, defaultValue?}]?,
         *   auth[{header, field, prefix?}]? }
         * 能力 = features 的 type 集合；voice.asr 的 sampleRate 缺省 16000。
         */
        internal fun metaFromRegisterArg(arg: Any?): PluginMeta? {
            val obj = arg as? NativeObject ?: return null
            val id = asJsString(ScriptableObject.getProperty(obj, "id")) ?: return null
            val version = asJsString(ScriptableObject.getProperty(obj, "version")) ?: return null
            val hostsRaw = ScriptableObject.getProperty(obj, "hosts") as? NativeArray ?: return null
            val hosts = arrayToStrings(hostsRaw) ?: return null
            val featuresRaw = ScriptableObject.getProperty(obj, "features") as? NativeArray
                ?: return null
            val capabilities = mutableListOf<String>()
            var sampleRate = SUPPORTED_SAMPLE_RATE
            for (i in 0 until featuresRaw.size) {
                val feature = featuresRaw[i] as? NativeObject ?: return null
                val type = asJsString(ScriptableObject.getProperty(feature, "type")) ?: return null
                capabilities.add(type)
                if (type == PluginContract.CAPABILITY_VOICE_ASR) {
                    val rate = ScriptableObject.getProperty(feature, "sampleRate")
                    if (rate is Number) sampleRate = rate.toInt()
                }
            }
            val settings = mutableListOf<PluginSettingDecl>()
            val settingsRaw = ScriptableObject.getProperty(obj, "settings")
            if (settingsRaw is NativeArray) {
                for (i in 0 until settingsRaw.size) {
                    val decl = settingsRaw[i] as? NativeObject ?: return null
                    val key = asJsString(ScriptableObject.getProperty(decl, "key")) ?: return null
                    val typeRaw = asJsString(ScriptableObject.getProperty(decl, "type"))
                        ?: return null
                    val type = runCatching { SettingType.valueOf(typeRaw.uppercase()) }
                        .getOrNull() ?: return null
                    val label = asJsString(ScriptableObject.getProperty(decl, "label"))
                        ?: return null
                    val optionsRaw = ScriptableObject.getProperty(decl, "options")
                    val options = if (optionsRaw is NativeArray) {
                        arrayToStrings(optionsRaw) ?: return null
                    } else {
                        emptyList()
                    }
                    val defaultValue =
                        asJsString(ScriptableObject.getProperty(decl, "defaultValue")) ?: ""
                    settings.add(PluginSettingDecl(key, type, label, options, defaultValue))
                }
            }
            val auth = mutableListOf<AuthHeaderDecl>()
            val authRaw = ScriptableObject.getProperty(obj, "auth")
            if (authRaw is NativeArray) {
                for (i in 0 until authRaw.size) {
                    val decl = authRaw[i] as? NativeObject ?: return null
                    val header = asJsString(ScriptableObject.getProperty(decl, "header"))
                        ?: return null
                    val field = asJsString(ScriptableObject.getProperty(decl, "field"))
                        ?: return null
                    val prefix = asJsString(ScriptableObject.getProperty(decl, "prefix")) ?: ""
                    auth.add(AuthHeaderDecl(header, field, prefix))
                }
            }
            return PluginMeta(
                id = id,
                version = version,
                hosts = hosts,
                capabilities = capabilities,
                sampleRate = sampleRate,
                settings = settings,
                authHeaders = auth,
            )
        }

        private fun arrayToStrings(array: NativeArray): List<String>? {
            val out = ArrayList<String>()
            for (i in 0 until array.size) {
                val item = asJsString(array[i]) ?: return null
                out.add(item)
            }
            return out
        }

        /**
         * 脚本字符串读取：拼接结果是 ConsString（CharSequence，非 String），
         * 一律按 CharSequence 取（见 PluginHost.asJsString 同款说明）。
         */
        private fun asJsString(value: Any?): String? =
            (value as? CharSequence)?.toString()
    }
}
