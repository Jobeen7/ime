package com.jobeen.ime.base.speech.cloud

import kotlinx.serialization.Serializable

/** 本轮唯一支持的录音采样率：录音器固定 16kHz，插件声明其他值即不兼容。 */
const val SUPPORTED_SAMPLE_RATE = 16000

/**
 * 云端语音插件元数据（Jime 原生命名；与任何第三方插件格式无关）。
 *
 * 插件是一段自包含脚本文件：导入时只以仅读方式运行它取 [PluginMeta]，
 * 会话时每插件每会话独立引擎实例运行。元数据是授权门：hosts／能力／
 * 密钥字段的任何变化都必须经用户重新确认（见 [PluginMetaDiff]）。
 */
@Serializable
data class PluginMeta(
    val id: String,
    val version: String,
    val hosts: List<String>,
    val capabilities: List<String>,
    val sampleRate: Int = SUPPORTED_SAMPLE_RATE,
    val settings: List<PluginSettingDecl> = emptyList(),
    val authHeaders: List<AuthHeaderDecl> = emptyList(),
)

/** 插件声明的设置项：值只写插件自有命名空间，绝不动全局设置。 */
@Serializable
data class PluginSettingDecl(
    val key: String,
    val type: SettingType,
    val label: String,
    val options: List<String> = emptyList(),
    val defaultValue: String = "",
)

@Serializable
enum class SettingType { TEXT, SECRET, SELECT, SWITCH, NUMBER }

/**
 * 宿主代附凭证头声明：脚本声明“需要”，宿主附加“值”。
 * 脚本全程见不到密钥值；[field] 必须指向一个 SECRET 设置项。
 */
@Serializable
data class AuthHeaderDecl(
    val header: String,
    val field: String,
    val prefix: String = "",
)

/** 导入失败原因（只作原因码，不携带脚本源码与任何秘密）。 */
enum class PluginImportError {
    TOO_LARGE,
    LOAD_FAILED,
    NO_PLUGIN,
    NO_VOICE_FEATURE,
    INVALID_ID,
    RESERVED_ID,
    NO_HOSTS,
    INVALID_HOSTS,
    INVALID_SETTINGS,
    INVALID_AUTH,
    UNSUPPORTED_SAMPLE_RATE,
}

/** 新旧元数据差异：用于升级确认对话框展示。 */
data class PluginMetaDiff(
    val hostsAdded: List<String> = emptyList(),
    val hostsRemoved: List<String> = emptyList(),
    val capsAdded: List<String> = emptyList(),
    val capsRemoved: List<String> = emptyList(),
    val settingsAdded: List<String> = emptyList(),
    val settingsRemoved: List<String> = emptyList(),
    val authAdded: List<String> = emptyList(),
    val authRemoved: List<String> = emptyList(),
) {
    /** 是否与旧清单完全相同：只有完全相同才可静默升级。 */
    val isIdentical: Boolean get() = this == PluginMetaDiff()
}

object PluginContract {
    /** 脚本文件上限 1MB（设计值）。 */
    const val MAX_SCRIPT_BYTES = 1024 * 1024

    /** 插件 id：首字符字母数字，后续字母数字／点／下划线／横线，总长 ≤64。 */
    private val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    /** 设置键：小写字母开头，后续小写字母数字下划线，总长 ≤32。 */
    private val SETTING_KEY_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")

    /** 精确域名：至少两段、末段全字母（拒 IP 与单段主机名）、无通配／端口／路径。 */
    private val DOMAIN_REGEX =
        Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,}$")

    /** 能力名字符集。 */
    private val CAPABILITY_REGEX = Regex("^[a-z0-9._-]{1,64}$")

    /** 头字段名 token。 */
    private val HEADER_REGEX = Regex("^[A-Za-z0-9-]+$")

    /** 宿主代附头禁用的头名（脚本自带头另有更严的禁转表，见网络垫片）。 */
    private val AUTH_FORBIDDEN_HEADERS = setOf(
        "host", "content-length", "content-type", "connection",
        "transfer-encoding", "proxy-authorization", "upgrade",
        "cookie", "set-cookie",
    )

    /** 内置保留 id：离线语音的身份与内置命名空间，插件不得冒名。 */
    fun isReservedId(id: String): Boolean =
        id == "offline" || id.startsWith("builtin.")

    /** 校验导入取到的元数据；返回 null 表示通过，否则为失败原因。
     * [allowReservedIds] 仅供内置种子安装（脚本来自应用自带 assets）：
     * 用户导入口仍拒绝保留 id，冒名 builtin.* 无法经导入进入。 */
    fun validate(meta: PluginMeta, allowReservedIds: Boolean = false): PluginImportError? {
        if (!ID_REGEX.matches(meta.id)) return PluginImportError.INVALID_ID
        if (!allowReservedIds && isReservedId(meta.id)) return PluginImportError.RESERVED_ID
        if (meta.version.isBlank() || meta.version.length > 32) {
            return PluginImportError.INVALID_ID
        }
        if (meta.hosts.isEmpty()) return PluginImportError.NO_HOSTS
        if (meta.hosts.size > 32) return PluginImportError.INVALID_HOSTS
        val normalized = meta.hosts.map { it.lowercase() }
        if (normalized.toSet().size != normalized.size) return PluginImportError.INVALID_HOSTS
        if (normalized.any { !DOMAIN_REGEX.matches(it) }) return PluginImportError.INVALID_HOSTS
        if (!meta.capabilities.contains(CAPABILITY_VOICE_ASR)) {
            return PluginImportError.NO_VOICE_FEATURE
        }
        if (meta.capabilities.any { !CAPABILITY_REGEX.matches(it) }) {
            return PluginImportError.NO_VOICE_FEATURE
        }
        if (meta.sampleRate != SUPPORTED_SAMPLE_RATE) {
            return PluginImportError.UNSUPPORTED_SAMPLE_RATE
        }
        val settingError = validateSettings(meta.settings)
        if (settingError != null) return settingError
        val authError = validateAuth(meta.authHeaders, meta.settings)
        if (authError != null) return authError
        return null
    }

    private fun validateSettings(settings: List<PluginSettingDecl>): PluginImportError? {
        if (settings.size > 32) return PluginImportError.INVALID_SETTINGS
        val keys = settings.map { it.key }
        if (keys.toSet().size != keys.size) return PluginImportError.INVALID_SETTINGS
        for (decl in settings) {
            if (!SETTING_KEY_REGEX.matches(decl.key)) return PluginImportError.INVALID_SETTINGS
            if (decl.label.isBlank() || decl.label.length > 64) {
                return PluginImportError.INVALID_SETTINGS
            }
            if (decl.type == SettingType.SELECT) {
                if (decl.options.isEmpty() || decl.options.size > 32 ||
                    decl.options.any { it.isBlank() || it.length > 64 }
                ) {
                    return PluginImportError.INVALID_SETTINGS
                }
            }
            if (decl.defaultValue.length > 512) return PluginImportError.INVALID_SETTINGS
        }
        return null
    }

    private fun validateAuth(
        authHeaders: List<AuthHeaderDecl>,
        settings: List<PluginSettingDecl>,
    ): PluginImportError? {
        if (authHeaders.size > 8) return PluginImportError.INVALID_AUTH
        val secretKeys = settings.filter { it.type == SettingType.SECRET }.map { it.key }.toSet()
        val seenHeaders = mutableSetOf<String>()
        for (decl in authHeaders) {
            if (!HEADER_REGEX.matches(decl.header)) return PluginImportError.INVALID_AUTH
            if (!seenHeaders.add(decl.header.lowercase())) return PluginImportError.INVALID_AUTH
            if (decl.header.lowercase() in AUTH_FORBIDDEN_HEADERS) {
                return PluginImportError.INVALID_AUTH
            }
            if (decl.field !in secretKeys) return PluginImportError.INVALID_AUTH
            if (decl.prefix.length > 16) return PluginImportError.INVALID_AUTH
        }
        return null
    }

    /** 新旧元数据差异（hosts 已统一小写比对）。 */
    fun diff(old: PluginMeta, new: PluginMeta): PluginMetaDiff {
        val oldHosts = old.hosts.map { it.lowercase() }.toSet()
        val newHosts = new.hosts.map { it.lowercase() }.toSet()
        val oldCaps = old.capabilities.toSet()
        val newCaps = new.capabilities.toSet()
        val oldSettings = old.settings.map { it.key }.toSet()
        val newSettings = new.settings.map { it.key }.toSet()
        val oldAuth = old.authHeaders.map { it.header.lowercase() }.toSet()
        val newAuth = new.authHeaders.map { it.header.lowercase() }.toSet()
        return PluginMetaDiff(
            hostsAdded = (newHosts - oldHosts).sorted(),
            hostsRemoved = (oldHosts - newHosts).sorted(),
            capsAdded = (newCaps - oldCaps).sorted(),
            capsRemoved = (oldCaps - newCaps).sorted(),
            settingsAdded = (newSettings - oldSettings).sorted(),
            settingsRemoved = (oldSettings - newSettings).sorted(),
            authAdded = (newAuth - oldAuth).sorted(),
            authRemoved = (oldAuth - newAuth).sorted(),
        )
    }

    const val CAPABILITY_VOICE_ASR = "voice.asr"
}
