package com.jobeen.ime.base.update

import androidx.core.content.edit
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.base.ngram.DownloadGate
import com.jobeen.ime.base.ngram.DownloadRecord
import com.jobeen.ime.base.ngram.DownloadResult
import com.jobeen.ime.base.ngram.UpdateImportGate
import com.jobeen.ime.base.ngram.deployBlocksUpdate
import com.jobeen.ime.base.ngram.gramFileInstallLock
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.withLock

/**
 * 万象输入方案在线更新管理器（多目录模型：更新跟随当前方案）。
 *
 * 下载地址（Jobeen 指定）：
 * - 方案包：https://github.com/amzxyz/rime-wanxiang/releases/download/{tag}/{变体包名}
 *   变体包名经官方 Releases 页核实：Lite=rime-wanxiang-lite.zip、
 *   Base=rime-wanxiang-base.zip、Pure=rime-wanxiang-pure.zip；
 *   Pro 因辅助码类型（pro-*-fuzhu.zip 的 * 段）无法从目录推断，暂不支持。
 * - 语法模型：https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram
 *   （全变体共用，仍落共享目录；非万象目录不显示模型行）。
 *
 * 更新目标（[UpdateTarget]）：当前目录自带 dicts/ 时更新该目录，
 * 否则更新共享目录（内置 Lite 的词库就在共享层）；非万象目录无官方
 * 更新源，不自动检查，只展示本地版本与词库指纹。
 *
 * 安全策略（修改时必须遵守）：
 * 1. 只更新纯数据文件：dicts/ 下的词库 + 语法模型 .gram。
 *    App 内置的 *.schema.yaml 经过深度定制（UI 元数据 layout/kind、能力开关 options、
 *    自定义 filter 链），绝不能被上游覆盖；lua/ 与定制 schema 深度集成，
 *    opencc/ App 未使用，均不更新。
 * 2. 目录目标只写该目录（dicts/ 整体 staging＋rename 切换），绝不碰
 *    *.userdb、*.userdict.txt、default.custom.yaml、自定义短语等用户数据；
 *    共享目标只写 sharedDataDir，绝不碰各用户目录。
 * 3. 下载用 .part 临时文件，成功后原子替换；词库先完整解压到与现用目录
 *    同级的 staging 目录校验，再整目录 rename 切换，不逐文件搬移。
 * 4. 全部网络与文件 IO 在 Dispatchers.IO，绝不阻塞主线程；下载为流式，
 *    不把整个文件载入内存（语法模型约 400MB）。
 * 5. 词库与语法模型紧耦合（上游 RIME-LMDG 说明），必须配套更新，不单独更新其中之一。
 * 6. 版本与词库指纹记录按目标分键：共享目标沿用旧裸键（存量直接沿用，
 *    无需迁移）；目录目标用 `dir:<目录名>:<键>` 前缀，App 升版清理
 *    （[ensureVersionHousekeeping]）只清共享侧裸键，不动目录记录
 *    （目录记录描述的是用户导入的包，不随 App 版本变）。
 */
object WanxiangUpdateManager {

    private const val SCHEMA_REPO = "amzxyz/rime-wanxiang"
    private const val GRAM_REPO = "amzxyz/RIME-LMDG"
    private const val GRAM_TAG = "LTS"

    /** 语法模型固定下载地址（LTS 标签恒指向最新） */
    private const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"
    private const val GRAM_FILE_NAME = "wanxiang-lts-zh-hans.gram"
    private const val SCHEMA_ZIP_NAME = "rime-wanxiang-lite.zip"

    /** 方案包下载地址模式：变体对应官方包名（见类注释核实结果） */
    private fun schemaUrl(tag: String, assetName: String): String =
        "https://github.com/amzxyz/rime-wanxiang/releases/download/$tag/$assetName"
    private const val SCHEMA_BASE_ZIP_NAME = "rime-wanxiang-base.zip"
    private const val SCHEMA_PURE_ZIP_NAME = "rime-wanxiang-pure.zip"

    /** 更新目标：内置 Lite 走共享目录；导入目录走该目录本身。 */
    sealed interface UpdateTarget {
        data object Shared : UpdateTarget
        data class Dir(val dirName: String) : UpdateTarget
    }

    /** 更新源：万象变体对应官方包名；非万象无源；Pro 辅助码类型无法推断。 */
    sealed interface UpdateSource {
        data class Wanxiang(val variant: String, val assetName: String) : UpdateSource
        data object NoSource : UpdateSource
        data object UnsupportedVariant : UpdateSource
    }

    /**
     * 当前更新目标：当前目录自带 dicts/ 即该目录，否则共享目录
     * （内置 Lite 的词库在共享层；空目录引擎回落共享 Lite，同口径）。
     */
    fun resolveUpdateTarget(
        context: android.content.Context = com.jobeen.ime.base.util.appContext,
    ): UpdateTarget {
        val dirName = SchemeDirs.currentDirName(context)
        val dirFile = File(DataManager.schemeDirsRoot, dirName)
        return if (File(dirFile, "dicts").isDirectory) {
            UpdateTarget.Dir(dirName)
        } else {
            UpdateTarget.Shared
        }
    }

    /**
     * 当前更新源：共享目标即内置 Lite；目录目标先按目录内容判变体
     * （[variantNameOfDir]，Base 与 Lite 共用方案 id、必须按目录判），
     * 目录判定不明时回退按当前方案 id 派生；仍不明或非万象则无源。
     */
    fun resolveUpdateSource(
        target: UpdateTarget,
        currentSchemaId: String?,
        currentSchemaName: String?,
    ): UpdateSource {
        if (target is UpdateTarget.Shared) {
            return UpdateSource.Wanxiang("Lite", SCHEMA_ZIP_NAME)
        }
        if (target !is UpdateTarget.Dir) return UpdateSource.NoSource
        val dirFile = File(DataManager.schemeDirsRoot, target.dirName)
        val variant = variantNameOfDir(dirFile)
            ?: currentSchemaId?.let { variantNameOf(it, currentSchemaName ?: it) }
        return when (variant) {
            "Lite" -> UpdateSource.Wanxiang("Lite", SCHEMA_ZIP_NAME)
            "Base" -> UpdateSource.Wanxiang("Base", SCHEMA_BASE_ZIP_NAME)
            "Pure" -> UpdateSource.Wanxiang("Pure", SCHEMA_PURE_ZIP_NAME)
            "Pro" -> UpdateSource.UnsupportedVariant
            else -> UpdateSource.NoSource
        }
    }

    /** 更新目标的词库目录：共享走 shared/dicts，目录走该目录下的 dicts。 */
    private fun targetDictsDir(target: UpdateTarget): File = when (target) {
        UpdateTarget.Shared -> File(DataManager.sharedDataDir, "dicts")
        is UpdateTarget.Dir -> File(File(DataManager.schemeDirsRoot, target.dirName), "dicts")
    }

    /** 更新目标的根目录：共享走 sharedDataDir，目录走该目录（staging/trash 与 dicts 同级）。 */
    private fun targetRootDir(target: UpdateTarget): File = when (target) {
        UpdateTarget.Shared -> DataManager.sharedDataDir
        is UpdateTarget.Dir -> File(DataManager.schemeDirsRoot, target.dirName)
    }

    /**
     * 词库侧记录键：共享目标沿用旧裸键（存量直接沿用）；目录目标加
     * `dir:<目录名>:` 前缀。语法模型侧键（共享模型）一律不加前缀。
     */
    private fun scopedKey(target: UpdateTarget, key: String): String = when (target) {
        UpdateTarget.Shared -> key
        is UpdateTarget.Dir -> "dir:${target.dirName}:$key"
    }

    /** zip 中只允许更新此前缀下的词库文件，其余一律跳过 */
    private val ALLOWED_PREFIXES = listOf("dicts/")
    private const val ALLOWED_SUFFIX = ".dict.yaml"

    const val PREFS_NAME = "wanxiang_update"
    internal const val KEY_SCHEMA_VERSION = "schema_version"
    // 内置随 App 打包的万象 Lite 数据版本（仅作落盘 version.txt 读不到时的
    // 兜底；展示优先读 shared/version.txt，见 readBundledSchemaVersion）
    const val BUILTIN_SCHEMA_VERSION = "v18.1.7"
    internal const val KEY_GRAM_PUBLISHED_AT = "gram_published_at"
    // 下载时计算的内容指纹（用于本地/远端比对）
    private const val KEY_DICT_REMOTE_FP = "dict_remote_fp"
    private const val KEY_DICT_REMOTE_FP_VER = "dict_remote_fp_ver"
    private const val KEY_GRAM_REMOTE_FP = "gram_remote_fp"
    private const val KEY_GRAM_REMOTE_FP_PUB = "gram_remote_fp_pub"

    private const val PART_SUFFIX = ".part"

    // 语法模型下载的临时后缀：必须与 GramModelDownloader 区分——两者
    // 目标同为 sharedDataDir 下的 GRAM_FILE_NAME，此前共用同一个
    // 「目标名 + .part」，同时触发时互相 delete/rename 对方下到一半
    // 的文件。方案包的 .part 在 cacheDir 的私有工作目录里、无此冲突，
    // 仍用 PART_SUFFIX
    private const val GRAM_PART_SUFFIX = ".wx.part"

    /** 词库整目录切换的 staging/trash 目录名（与现用 dicts 同级，保证 rename 在同一文件系统内原子） */
    private const val DICTS_STAGING_DIR = "dicts.staging"
    private const val DICTS_TRASH_DIR = "dicts.trash"
    // 旧版事务日志/提交标记与模型备份的文件名：S11 后不再产生，
    // 仅供 recoverInterruptedUpdate 清理旧版残留时引用
    private const val UPDATE_TXN_FILE = "wanxiang-update.pending"
    private const val UPDATE_TXN_COMMITTED_FILE = "wanxiang-update.committed"
    private const val GRAM_BACKUP_FILE = "$GRAM_FILE_NAME.update-backup"
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L

    // 下载/解压体积上限（正常量级：方案包约 32MB、语法模型约 400MB、单个
    // 词库文件远小于 64MB）：留足余量，只拦上游污染/异常流写满存储
    private const val MAX_SCHEMA_BYTES = 128L * 1024 * 1024
    private const val MAX_MODEL_BYTES = 600L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_EXTRACT_TOTAL_BYTES = 256L * 1024 * 1024

    // 这些客户端只访问写死的 HTTPS 地址：禁止 https↔http 重定向，
    // 避免全局放开明文后被降级（明文仅供 WebDAV 在用户显式开启后使用）
    // 本 client 只用于检查类短请求（Release 查询）；整包下载走
    // [downloadClient]，见其注释
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // 总时长上限：readTimeout 只约束两次读之间的间隔，慢速涓流
        // 响应能让单次调用无限拖长，callTimeout 给整条调用封顶
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 整包下载专用 client：与 [client] 同配置，唯独去掉 callTimeout。
     * callTimeout 是整条调用（含读完响应体）的总时长上限，只适用于
     * 上面的短请求；整包下载（约 23 MB 方案包、约 400 MB 语法模型）
     * 耗时随用户带宽而定，套 20 秒总上限必然在读流中途被掐断
     * （第十一批给 Range 取数加封顶时误伤了共用同一 client 的下载
     * 路径）。派生 client 与原 client 共享连接池与调度器。
     * internal 供守护测试断言其 callTimeoutMillis == 0、防回归。
     */
    internal val downloadClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhAsset(
        val name: String = "",
        val browser_download_url: String = "",
        val size: Long = 0L,
        /** GitHub 登记的官方摘要（"sha256:..."），查到时下载后据此校验，查不到降级放行（S2） */
        val digest: String = "",
    )

    @Serializable
    private data class GhRelease(
        val tag_name: String = "",
        val published_at: String = "",
        val assets: List<GhAsset> = emptyList(),
    )

    data class UpdateInfo(
        /** 远端方案版本，如 v18.0.14 */
        val schemaRemoteVersion: String,
        /** 本地已下载版本，null=未更新过 */
        val schemaLocalVersion: String?,
        /** 远端模型发布时间 */
        val gramRemotePublishedAt: String,
        /** 本地已下载模型的发布时间，null=未下载过 */
        val gramLocalPublishedAt: String?,
        /** 远端词库内容指纹（下载时计算并存储；null=尚未下载该版本） */
        val dictRemoteFingerprint: String?,
        /** 远端模型内容指纹（下载时计算并存储；null=尚未下载该版本） */
        val gramRemoteFingerprint: String?,
        /** 方案包在 GitHub 登记的官方 SHA-256（查到则下载后强校验，缺失降级放行，S2） */
        val schemaZipSha256: String = "",
        /** 语法模型在 GitHub 登记的官方 SHA-256（同上） */
        val gramSha256: String = "",
        /** 本次检查/更新对应的目标（共享或目录），下载时据此落盘 */
        val target: UpdateTarget = UpdateTarget.Shared,
        /** 本次检查/更新对应的官方包名（Lite/Base/Pure） */
        val assetName: String = SCHEMA_ZIP_NAME,
        /**
         * 语法模型是否有更新：由检查流程按 [resolveGramUpdate] 的判定
         * 在构造时写入。不能再从 gramLocalPublishedAt 派生：上游原地
         * 替换模型文件时 published_at 不变，纯时间比对会把「摘要已变」
         * 的真更新漏判（判定规则见 resolveGramUpdate）。
         */
        val gramUpdateAvailable: Boolean,
    ) {
        // 只在远端确实更新时才算"有更新"：本地版本高于远端（内置更新包等）时
        // 用 != 判断会误报并把用户降级式覆盖
        val schemaUpdateAvailable: Boolean
            get() {
                val local = schemaLocalVersion ?: return true
                return compareVersions(schemaRemoteVersion, local) > 0
            }
        val hasUpdate: Boolean get() = schemaUpdateAvailable || gramUpdateAvailable

        /**
         * 远端语法模型内容是否经 GitHub 登记摘要核实过：摘要解析
         * 失败（接口限流/网络异常，内嵌摘要又恰好缺失）时
         * [gramSha256] 为空，模型侧「无更新」结论只剩下载记录的
         * 发布时间语义兜底——上游同名原地换包时发布时间不变、
         * 记录判不出，界面不得据此宣称「已是最新」，只能如实报
         * 未确认（V7b 口径）。摘要非空即已核实。
         */
        val gramContentVerified: Boolean get() = gramSha256.isNotEmpty()
    }

    /** "v18.0.15" 风格版本号逐段数字比较。 */
    private fun compareVersions(left: String, right: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val r = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (r != 0) return r
        }
        return 0
    }

    /** 语法模型更新判定的结果（见 [resolveGramUpdate]）。 */
    internal data class GramUpdateDecision(
        /** 参与发布时间比对/显示的本地值：内容一致时即远端发布时间 */
        val effectiveLocalPublishedAt: String?,
        /** 记录与内容不一致、需要把发布时间与远端模型指纹回填进 prefs */
        val backfill: Boolean,
        /** 是否有更新：以内容哈希为准，哈希缺失时回落发布时间比对 */
        val updateAvailable: Boolean,
    )

    /**
     * 语法模型更新判定（纯函数）。下载记录只说明「上次经本管理器下载
     * 时」的版本：App 升版会清记录、模型经其他入口（模型增强页）下载
     * 时根本不写记录，记录为空/过期不等于本地文件旧。
     *
     * 本地指纹与远端登记摘要都拿得到时以哈希为唯一判据（上游会在
     * 同一 Release 里原地替换模型文件：published_at 不变、摘要变，
     * 只比时间会把真更新永远漏掉，2026-10-11 实测发生过一次）：
     * 相等→无更新（并按需回填记录，避免每次升版后误报、整份重下
     * 约 400MB）；不等→有更新，哪怕发布时间相同。任一哈希缺失时
     * 回落记录语义：记录为空、或远端发布时间较新，即判有更新。
     */
    internal fun resolveGramUpdate(
        recordedPublishedAt: String?,
        localFingerprint: String?,
        remotePublishedAt: String,
        remoteSha256: String,
    ): GramUpdateDecision {
        val sha = remoteSha256.trim()
        if (sha.isNotEmpty() && !localFingerprint.isNullOrEmpty()) {
            return if (localFingerprint.equals(sha, ignoreCase = true)) {
                GramUpdateDecision(
                    effectiveLocalPublishedAt = remotePublishedAt,
                    backfill = recordedPublishedAt != remotePublishedAt,
                    updateAvailable = false,
                )
            } else {
                GramUpdateDecision(
                    effectiveLocalPublishedAt = recordedPublishedAt,
                    backfill = false,
                    updateAvailable = true,
                )
            }
        }
        return GramUpdateDecision(
            effectiveLocalPublishedAt = recordedPublishedAt,
            backfill = false,
            updateAvailable = recordedPublishedAt == null ||
                remotePublishedAt > recordedPublishedAt,
        )
    }

    /**
     * 本地方案版本（纯函数）：下载记录优先；记录缺失（未在线更新过、
     * 或升版后记录被清）时以实际部署的内置版本为准——内置数据就是
     * 随包发布的官方版本，只信记录会把「记录为空」误判成有更新。
     * 方案侧只做版本比对：方案 zip 的官方摘要是对 zip 文件的，与解压
     * 后的词库派生指纹不可直接比对。两者皆无返回 null（保持原有的
     * 未知即提示更新语义）。
     */
    internal fun resolveLocalSchemaVersion(
        recordedVersion: String?,
        bundledVersion: String?,
    ): String? = recordedVersion ?: bundledVersion

    data class LocalInfo(
        /** 本地方案版本，未更新过则为内置版本 */
        val schemaVersion: String,
        /** 本地词库指纹（dicts 文件内容 SHA-256 派生） */
        val dictFingerprint: String,
        /** 本地模型指纹（文件内容 SHA-256），null=无模型文件 */
        val gramFingerprint: String?,
    )

    data class UpdateProgress(
        val stage: Stage,
        val downloaded: Long = 0L,
        val total: Long = 0L,
    ) {
        enum class Stage { SCHEMA_DOWNLOAD, DICTS_EXTRACT, GRAM_DOWNLOAD }
    }

    /**
     * 获取本地信息（词库指纹、模型指纹），纯本地计算，用于"本地信息"展示。
     * 必须在协程中调用（内部已切换到 IO）。
     * [target] 决定读哪份词库与版本记录；[includeGram] 为 false 时不碰
     * 模型文件（非万象目录不显示模型行，也就不触发 400MB 指纹计算）。
     */
    suspend fun getLocalInfo(
        prefs: android.content.SharedPreferences,
        target: UpdateTarget = UpdateTarget.Shared,
        includeGram: Boolean = true,
    ): LocalInfo = withContext(Dispatchers.IO) {
        val dictsDir = targetDictsDir(target)
        val gramFile = File(DataManager.sharedDataDir, GRAM_FILE_NAME)
        LocalInfo(
            // 未在线更新过则显示内置版本：共享目标优先读解压落盘的
            // shared/version.txt（随打包换版自动跟随，不依赖手改常量——
            // 常量曾停在 v18.0.15 与实际内置 18.1.0 脱节，方案页本地版本错报）；
            // 目录目标读该目录的 version.txt，没有则未知（空串，界面显示"—"）。
            schemaVersion = prefs.getString(scopedKey(target, KEY_SCHEMA_VERSION), null)
                ?: if (target is UpdateTarget.Shared) {
                    readBundledSchemaVersion() ?: BUILTIN_SCHEMA_VERSION
                } else {
                    readDirVersionText(targetRootDir(target)) ?: ""
                },
            dictFingerprint = try {
                cachedDictsFingerprint(prefs, dictsDir, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "词库指纹计算失败")
                ""
            },
            gramFingerprint = if (includeGram && gramFile.isFile) {
                try {
                    cachedFileFingerprint(prefs, gramFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "模型指纹计算失败")
                    null
                }
            } else {
                null
            },
        )
    }

    // 指纹缓存：文件未变（长度+修改时间一致）时不要每次打开页面都全量哈希
    // （语法模型约 400MB，词库也要逐文件读完）。缓存只存"文件状态→指纹"映射，
    // 文件一变键就对不上，自动重算，不会显示过期指纹。
    private const val KEY_FP_GRAM = "fp_cache_gram"
    private const val KEY_FP_GRAM_STATE = "fp_cache_gram_state"
    // 词库指纹缓存键（单代：「路径|大小|CRC32」元组口径）：状态键
    // 只含路径|长度|mtime，文件一变即对不上、自动重算。原 v1/v2
    // 两代键名＋版本切换逐键清理已按 S3 退役，只留现行一对
    private const val KEY_FP_DICT = "fp_cache_dict_v2"
    private const val KEY_FP_DICT_STATE = "fp_cache_dict_state_v2"

    /** 版本切换清理已见过的 App versionCode（见 [ensureVersionHousekeeping]） */
    private const val KEY_APP_VERSION_CODE_SEEN = "app_version_code_seen"

    /**
     * 版本切换时 [ensureVersionHousekeeping] 清掉的偏好键清单：
     * 远端词库指纹记录、词库指纹缓存与在线更新的方案版本记录。
     * 不含 [KEY_GRAM_PUBLISHED_AT]：语法模型文件不被内置包解压覆盖
     * （内置 resource.zip 只有词库与方案文件），其发布时间记录与
     * 盘上文件始终同在、自洽，清掉只会逼已装用户无谓重判。
     * internal 供测试钉住这份清单的构成。
     */
    internal val VERSION_HOUSEKEEPING_CLEARED_KEYS: List<String> = listOf(
        KEY_DICT_REMOTE_FP,
        KEY_DICT_REMOTE_FP_VER,
        KEY_FP_DICT,
        KEY_FP_DICT_STATE,
        KEY_SCHEMA_VERSION,
    )

    /**
     * App 版本切换时的记录清理：内置词库随 App 升级可能已变化——
     * versionCode 与上次记录不一致时，按
     * [VERSION_HOUSEKEEPING_CLEARED_KEYS] 清掉远端词库指纹记录与
     * 词库指纹缓存（本地状态键只含文件 path|length|mtime，换包重
     * 部署偶然同状态时会命中旧值），逼下一次检查重新计算；同时
     * 清在线更新的方案版本记录（data M1）：App 升级
     * 重新解压内置包会把 shared/dicts 还原为内置版本，旧记录不清
     * 时本地版本继续显示在线版、与远端同版还会误判「已是最新」、
     * 不给更新入口——清后本地版本回落到实际部署的内置 version.txt，
     * 与盘上内容一致。语法模型缓存与发布时间记录不动：其指纹是
     * 文件内容 SHA-256、口径未变，状态键含文件长度与 mtime 足以
     * 自行失效；gram 文件也不被内置包覆盖（见清单注释）。
     * 在设置页打开与 [checkForUpdates] 入口各调一次，幂等且只是
     * 一次偏好读（版本号未变即返回）。
     */
    fun ensureVersionHousekeeping(context: android.content.Context) {
        val prefs = context.getSharedPreferences(
            PREFS_NAME, android.content.Context.MODE_PRIVATE
        )
        val current = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrNull() ?: return
        if (prefs.getLong(KEY_APP_VERSION_CODE_SEEN, -1L) == current) return
        prefs.edit {
            VERSION_HOUSEKEEPING_CLEARED_KEYS.forEach { remove(it) }
            putLong(KEY_APP_VERSION_CODE_SEEN, current)
        }
    }

    /**
     * 不跟随符号链接的递归删除：File.deleteRecursively 遇到指向目录的软链
     * 会跟进去删目标内容；解压目录虽由本 App 写入，仍按不跟随实现兜底。
     * 纯 java.io 实现（java.nio.file 要 API 26+，minSdk 24）。
     */
    private fun File.deleteRecursivelyNoFollow() {
        if (!exists()) return
        if (isDirectory && !isSymlink()) {
            listFiles()?.forEach { it.deleteRecursivelyNoFollow() }
        }
        delete()
    }

    /** 软链判定（经典 canonical 对比法，不依赖 java.nio） */
    private fun File.isSymlink(): Boolean {
        val parent = parentFile ?: return false
        val canonicalParent = runCatching { parent.canonicalFile }.getOrNull() ?: return false
        val inCanonicalParent = File(canonicalParent, name)
        val canonical = runCatching { inCanonicalParent.canonicalPath }.getOrNull() ?: return false
        return canonical != inCanonicalParent.path
    }

    /**
     * 更新残留清理（启动路径，函数名为兼容 AppStartup 的步骤引用
     * 沿用旧名）：更新是手动触发、可重来的操作，不再有事务日志/
     * 回滚（S11 已拆）——切换靠 staging 整目录 rename 本身原子，
     * 中断后的坏状态按盘上实际状态收尾即可：
     * - dicts 缺位而 trash 在 → 把旧词库 rename 回位；两者都在 →
     *   trash 是已切换后的旧副本，删；
     * - gram 缺位而备份在 → 备份 rename 回位；在 → 备份删；
     * - staging 残留与旧版事务日志文件一律清掉。
     * 本函数绝不能抛错堵住键盘启动：异常只记日志。
     */
    fun recoverInterruptedUpdate() {
        // 清理会删/换语法模型文件，与 GramModelDownloader 的落盘共用
        // 跨下载器安装锁（见 gramFileInstallLock）：清理不得删掉另一
        // 路刚装好的模型，落盘也不得插进清理中间
        gramFileInstallLock.withLock {
            runCatching { recoverInterruptedUpdateInternal() }
                .onFailure { Timber.e(it, "Interrupted-update cleanup failed; continuing startup") }
        }
    }

    private fun recoverInterruptedUpdateInternal() {
        // 词库切换残留：共享根 + 当前目录根（目录目标更新中断时
        // staging/trash 在目录内）；模型残留只在共享根（模型全局共享）。
        recoverDictsRoot(DataManager.sharedDataDir)
        val currentDir = runCatching { SchemeDirs.currentDirName() }.getOrNull()
        if (currentDir != null) {
            val dirFile = File(DataManager.schemeDirsRoot, currentDir)
            if (dirFile.isDirectory) recoverDictsRoot(dirFile)
        }
        recoverGramRoot()
        // 旧版事务日志/提交标记残留（S11 后不再读写）：直接清掉（仅共享根）
        val root = DataManager.sharedDataDir
        File(root, UPDATE_TXN_FILE).delete()
        File(root, UPDATE_TXN_COMMITTED_FILE).delete()
        File(root, "$UPDATE_TXN_FILE.tmp").delete()
        File(root, "$GRAM_BACKUP_FILE.tmp").delete()
        File(root, "$GRAM_BACKUP_FILE.restore").delete()
    }

    /** 按状态修复某根目录下的词库切换残留（不碰模型文件）。 */
    private fun recoverDictsRoot(root: File) {
        val dicts = File(root, "dicts")
        val trash = File(root, DICTS_TRASH_DIR)

        // 按状态修复（不读任何日志）：词库切换中断在两个 rename
        // 之间时 dicts 缺位、旧词库在 trash 里，回位；切换已完成
        // 时 trash 只是旧副本，删掉
        if (!dicts.exists() && trash.exists()) {
            if (!trash.renameTo(dicts)) {
                // Best effort only: leave the trash directory in
                // place for diagnosis instead of failing startup.
                Timber.e("Failed to restore previous dictionary directory")
            }
        } else if (dicts.exists() && trash.exists()) {
            trash.deleteRecursivelyNoFollow()
        }
        File(root, DICTS_STAGING_DIR).deleteRecursivelyNoFollow()
    }

    /** 按状态修复共享根下的模型残留。 */
    private fun recoverGramRoot() {
        val root = DataManager.sharedDataDir
        // 旧共用临时名遗留：两下载器曾共用「目标名 + .part」作半成品
        // 名（<language>.gram.part，language 不止默认一种），拆分后缀
        // 后已无人再写它，残留文件只占空间（可达数百 MB），启动清
        // 理时按模式统一清掉。现行半成品后缀已逐项核对、均不匹配
        // 「.gram.part」结尾，不会误删在途下载：GMD 是
        // <language>.gmdl.part、本类 gram 是 <目标名>.wx.part、
        // 方案包 .part 在 cacheDir 的工作目录里、不在本目录
        root.listFiles { f -> f.isFile && f.name.endsWith(".gram.part") }
            ?.forEach { it.delete() }
        val gram = File(root, GRAM_FILE_NAME)
        val gramBackup = File(root, GRAM_BACKUP_FILE)

        // 语法模型：旧流程的备份只在 gram 缺位时是唯一完好副本，
        // 回位；gram 在位时备份是过期残留，删之（新流程不再产生
        // 备份，rename 本身原子替换）
        if (!gram.isFile && gramBackup.isFile) {
            runCatching { gramBackup.renameTo(gram) }
                .onFailure { Timber.e(it, "Failed to restore grammar model from backup") }
        } else {
            gramBackup.delete()
        }
    }

    /** 内置方案版本：读资源解压落盘的 shared/version.txt（如 "18.1.0"），补上 v 前缀与常量口径对齐 */
    private fun readBundledSchemaVersion(): String? =
        readVersionText(File(DataManager.sharedDataDir, "version.txt"))

    /** 目录方案版本：读该目录的 version.txt（导入包自带时才有），口径与内置一致 */
    private fun readDirVersionText(dirFile: File): String? =
        readVersionText(File(dirFile, "version.txt"))

    private fun readVersionText(f: File): String? = runCatching {
        if (f.isFile) {
            f.readText().trim().takeIf { it.isNotEmpty() }?.let { "v$it" }
        } else {
            null
        }
    }.getOrNull()

    private suspend fun cachedFileFingerprint(
        prefs: android.content.SharedPreferences,
        file: File,
    ): String {
        val state = "${file.length()}:${file.lastModified()}"
        val cached = prefs.getString(KEY_FP_GRAM, null)
        if (cached != null && prefs.getString(KEY_FP_GRAM_STATE, null) == state) return cached
        val fp = sha256File(file)
        prefs.edit().putString(KEY_FP_GRAM, fp).putString(KEY_FP_GRAM_STATE, state).apply()
        return fp
    }

    private suspend fun cachedDictsFingerprint(
        prefs: android.content.SharedPreferences,
        dictsDir: File,
        target: UpdateTarget = UpdateTarget.Shared,
    ): String {
        // 词库状态键只用各文件的路径+长度+修改时间（stat 级别，开销可忽略）
        val stateParts = mutableListOf<String>()
        dictsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(ALLOWED_SUFFIX) }
            .forEach { stateParts.add("${it.relativeTo(dictsDir).path}|${it.length()}|${it.lastModified()}") }
        stateParts.sort()
        val state = DictFingerprint.combine(stateParts)
        val fpKey = scopedKey(target, KEY_FP_DICT)
        val stateKey = scopedKey(target, KEY_FP_DICT_STATE)
        val cached = prefs.getString(fpKey, null)
        if (cached != null && prefs.getString(stateKey, null) == state) return cached
        val fp = dictsContentFingerprint(dictsDir)
        prefs.edit().putString(fpKey, fp).putString(stateKey, state).apply()
        return fp
    }

    /**
     * 万象变体短名（Base/Pro/Lite/Pure）：按 schema id 派生（上游各变体的
     * id 约定：wanxiang 为 Base、wanxiang_pro 系为 Pro、wanxiang_pure 系为
     * Pure、wanxiang_lite 系与 wanxiang_t9 为 Lite），id 识别不出再按方案名
     * 关键词兜底；都不命中返回 null（调用方回退显示方案名）。不写死某个
     * 变体——用户可自备任意变体的方案包，展示必须跟着实际方案走。
     */
    fun variantNameOf(schemaId: String, schemaName: String): String? {
        val id = schemaId.trim().lowercase()
        if (id.startsWith("wanxiang")) {
            when {
                id.contains("pure") -> return "Pure"
                id.contains("pro") -> return "Pro"
                id.contains("lite") || id == "wanxiang_t9" -> return "Lite"
                id == "wanxiang" || id == "wanxiang_base" -> return "Base"
            }
        }
        val name = schemaName.trim()
        return when {
            name.contains("Pure") -> "Pure"
            name.contains("Pro") -> "Pro"
            name.contains("Lite") -> "Lite"
            name.contains("Base") -> "Base"
            // 上游基础版方案名就是「万象拼音」（无变体后缀）
            name == "万象拼音" -> "Base"
            else -> null
        }
    }

    /**
     * 按目录内容判定万象变体：目录即方案包，变体是包的属性而不
     * 是方案的属性——Base 与 Lite 共用 wanxiang_t9 / wanxiang_english
     * 两个方案 id，按当前方案 id 派生会把 Base 目录误判为 Lite。
     * - 目录含 wanxiang_lite 系主方案文件 → Lite；含 wanxiang 主方
     *   案文件（wanxiang.schema.yaml）→ Base；含 wanxiang_pro 系 →
     *   Pro；含 wanxiang_pure 系 → Pure。
     * - 多个主方案文件并存（合并目录）时变体不明，返回 null，调
     *   用方回退按方案 id 派生；目录不存在、不可读、读异常同样
     *   返回 null。
     */
    fun variantNameOfDir(dir: java.io.File): String? {
        if (!dir.isDirectory) return null
        val names = try {
            dir.list()?.toSet() ?: return null
        } catch (_: Exception) {
            return null
        }
        val hasLite = names.any { it.startsWith("wanxiang_lite") && it.endsWith(".schema.yaml") }
        val hasBase = "wanxiang.schema.yaml" in names
        val hasPro = names.any { it.startsWith("wanxiang_pro") && it.endsWith(".schema.yaml") }
        val hasPure = names.any { it.startsWith("wanxiang_pure") && it.endsWith(".schema.yaml") }
        val found = listOfNotNull(
            if (hasLite) "Lite" else null,
            if (hasBase) "Base" else null,
            if (hasPro) "Pro" else null,
            if (hasPure) "Pure" else null,
        )
        return found.singleOrNull()
    }

    /** 指纹截断显示，如 "21012108...939392"，与设计稿一致。 */
    fun shortFingerprint(full: String): String =
        if (full.length > 14) "${full.take(8)}...${full.takeLast(6)}" else full

    /**
     * 计算文件内容的 SHA-256（流式读取，适用于大文件如 396MB 语法模型）。
     * 每 1MB 检查一次协程取消，避免页面退出后继续空转。
     * 实现收敛至 [DigestUtil.sha256HexCancellable]，口径逐字节一致。
     */
    private suspend fun sha256File(file: File): String =
        DigestUtil.sha256HexCancellable(file)

    /**
     * 词库内容指纹：每个 dict 文件取「相对路径|大小|内容 CRC32」
     * 元组（见 [DictFingerprint]），排序后拼接再取 SHA-256。本地
     * 现用词库与下载暂存词库用同一算法：前者供本地行展示，后者
     * 落盘为该版本的远端指纹记录，更新成功后两者同值可比（远端
     * 实时取数已按 S3 退役，记录是远端指纹的唯一来源）。
     * 目录缺失/为空（部署失败等异常状态）时返回空串而非对零个
     * part 做哈希——空输入哈希是个非空常量，会绕过各处空值守卫、
     * 伪装成一个真实指纹显示并落盘为远端记录；空串则让本地行
     * 诚实显示"—"、回填守卫也不会写入。
     */
    private suspend fun dictsContentFingerprint(dictsDir: File): String {
        val parts = mutableListOf<String>()
        dictsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(ALLOWED_SUFFIX) }
            .forEach {
                currentCoroutineContext().ensureActive()
                parts.add(
                    DictFingerprint.entryPart(
                        it.relativeTo(dictsDir).path,
                        it.length(),
                        crc32File(it),
                    )
                )
            }
        if (parts.isEmpty()) return ""
        parts.sort()
        return DictFingerprint.combine(parts)
    }

    /** 流式计算文件 CRC32（分块读、每块检查协程取消，口径同 sha256File）。 */
    private suspend fun crc32File(file: File): Long = withContext(Dispatchers.IO) {
        val crc = java.util.zip.CRC32()
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        crc.value
    }

    /**
     * 检查远端更新：查询 GitHub Releases 获取最新版本号，与本地记录比对。
     * 必须在协程中调用（内部已切换到 IO）。
     * @return Result 包装的 UpdateInfo，网络失败时携带异常
     */
    suspend fun checkForUpdates(
        prefs: android.content.SharedPreferences,
        target: UpdateTarget = UpdateTarget.Shared,
        source: UpdateSource.Wanxiang = UpdateSource.Wanxiang("Lite", SCHEMA_ZIP_NAME),
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            // 版本切换先清理旧指纹记录/缓存（幂等，见该函数注释）
            ensureVersionHousekeeping(com.jobeen.ime.base.util.appContext)
            val schemaRelease = fetchRelease("$SCHEMA_REPO/releases/latest")
            val gramRelease = fetchRelease("$GRAM_REPO/releases/tags/$GRAM_TAG")
            if (schemaRelease.tag_name.isBlank()) error("远端方案版本无效")
            if (gramRelease.published_at.isBlank()) error("远端模型版本无效")

            // 登记摘要只用 Release 对象内嵌的 digest（它就是 GitHub
            // 登记值本身）；内嵌缺项时为空，下载侧按「查到强校验、
            // 查不到降级放行＋自算哈希存记录」处理，不再走分页端点
            // 补查、更不许因缺摘要拒绝更新（S2）
            val gramSha256 = resolveRegisteredDigest(
                gramRelease.assets.firstOrNull { it.name == GRAM_FILE_NAME }?.digest,
            )
            val schemaZipSha256 = resolveRegisteredDigest(
                schemaRelease.assets.firstOrNull { it.name == source.assetName }?.digest,
            )
            // 模型内容兜底：记录为空/过期时以本地文件真实指纹为准。指纹
            // 走文件状态缓存（cachedFileFingerprint），文件未变时不重哈希
            // 这约 400MB 的文件；算不出（无文件/摘要缺失）退回记录判定
            val localGramFingerprint = if (gramSha256.isNotEmpty()) {
                try {
                    val gramFile = File(DataManager.sharedDataDir, GRAM_FILE_NAME)
                    if (gramFile.isFile) cachedFileFingerprint(prefs, gramFile) else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "本地模型指纹计算失败，退回记录判定")
                    null
                }
            } else {
                null
            }
            val gramDecision = resolveGramUpdate(
                recordedPublishedAt = prefs.getString(KEY_GRAM_PUBLISHED_AT, null),
                localFingerprint = localGramFingerprint,
                remotePublishedAt = gramRelease.published_at,
                remoteSha256 = gramSha256,
            )
            if (gramDecision.backfill && localGramFingerprint != null) {
                // 内容已证实本地即远端当前版本：把记录补齐，此后走记录
                // 判定即可；远端模型指纹也有值可回填，界面不再显示"—"
                prefs.edit {
                    putString(KEY_GRAM_PUBLISHED_AT, gramRelease.published_at)
                    putString(KEY_GRAM_REMOTE_FP, localGramFingerprint)
                    putString(KEY_GRAM_REMOTE_FP_PUB, gramRelease.published_at)
                }
            }

            // 本地版本先定下来（记录缺失时以实际部署的版本为准，
            // 目录目标读该目录的 version.txt，没有则未知，下方 UpdateInfo 共用
            val localSchemaVersion = resolveLocalSchemaVersion(
                recordedVersion = prefs.getString(scopedKey(target, KEY_SCHEMA_VERSION), null),
                bundledVersion = if (target is UpdateTarget.Shared) {
                    readBundledSchemaVersion()
                } else {
                    readDirVersionText(targetRootDir(target))
                },
            )

            // 远端词库指纹：不再实时取数（原 Range 抠 zip 中央目录
            // 的整套机器已按 S3 退役）——只有下载流程留下的记录、
            // 且记录版本与远端标签一致时才作数，其余为未知，界面
            // 显示「—」；远端是否有更新由版本号比对判定
            val dictRemoteFp = prefs.getString(scopedKey(target, KEY_DICT_REMOTE_FP), null)
                .takeIf { prefs.getString(scopedKey(target, KEY_DICT_REMOTE_FP_VER), null) == schemaRelease.tag_name }
            // 远端模型指纹：以本次检查响应拿到的远端登记摘要为准
            // （非空即用）——它就是远端当前文件的真实指纹。下载记录
            // 只说明「上次下载时」的值：远端原地换包后记录仍是旧值，
            // 若优先记录，界面会把本地与记录里的同一个旧值同值同色
            // 展示出来，看着像已对齐、其实远端早已换包（判定本身走
            // 哈希没有错，错的只是这一行显示）。登记摘要为空（查询
            // 失败）时才回落旧记录兜底（发布时间匹配时）
            val gramRemoteFp = gramSha256.ifEmpty { null }
                ?: prefs.getString(KEY_GRAM_REMOTE_FP, null)
                    .takeIf { prefs.getString(KEY_GRAM_REMOTE_FP_PUB, null) == gramRelease.published_at }

            UpdateInfo(
                schemaRemoteVersion = schemaRelease.tag_name,
                schemaLocalVersion = localSchemaVersion,
                gramRemotePublishedAt = gramRelease.published_at,
                gramLocalPublishedAt = gramDecision.effectiveLocalPublishedAt,
                dictRemoteFingerprint = dictRemoteFp,
                gramRemoteFingerprint = gramRemoteFp,
                schemaZipSha256 = schemaZipSha256,
                gramSha256 = gramSha256,
                gramUpdateAvailable = gramDecision.updateAvailable,
                target = target,
                assetName = source.assetName,
            )
        }.onFailure {
            Timber.e(it, "检查方案更新失败")
        }
    }

    /**
     * 资产登记摘要：只用 Release 对象内嵌的 [embeddedDigest]（归一
     * 化后合法即用，它就是 GitHub 登记值本身）。内嵌缺项返回空
     * 串，下载侧降级放行（S2），不再走资产分页端点补查。
     */
    private fun resolveRegisteredDigest(embeddedDigest: String?): String =
        com.jobeen.ime.base.ngram.normalizeSha256(embeddedDigest)

    private fun fetchRelease(apiPath: String): GhRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$apiPath")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("版本检查失败：HTTP ${response.code}")
            val body = response.body?.string() ?: error("版本检查返回为空")
            return json.decodeFromString<GhRelease>(body)
        }
    }

    // 下载并应用更新（词库 + 语法模型配套）的全流程互斥：临时目录
    // 与 .part 路径都是固定名，并发更新会互相覆盖/删除。入口防重入
    // 与跨路互斥由 DownloadGate 承担（见 downloadAndApply），本锁只
    // 串行化同一管理器的实际更新事务
    private val updateMutex = Mutex()

    /**
     * 方案更新的进程级运行状态、进度与结果：设置页订阅它们决定按钮
     * 与状态文案——页面的 remember 局部状态在离开页面后即丢失，
     * 工作却跑在应用级作用域里继续，重进页面若只看局部状态会误以
     * 为没在更新、可再点一次，进行中的进度与已完成的结果也无从
     * 读回（N2）。
     * 状态由跨下载器共用的 [DownloadGate] 驱动：只有抢到闸的调用
     * 才置位/复位，被拒的重复调用（BUSY）不触碰它们，不会把正在
     * 跑（或排队中）的更新假复位、也不会覆盖 [lastResult]。
     */
    private val runningState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = runningState

    private val progressState =
        kotlinx.coroutines.flow.MutableStateFlow<UpdateProgress?>(null)

    /** 更新进度（阶段与已下载/总字节数）：进程级发布，页面重进可续看；非运行期为 null */
    val progress: kotlinx.coroutines.flow.StateFlow<UpdateProgress?> = progressState

    private val lastResultState =
        kotlinx.coroutines.flow.MutableStateFlow<DownloadRecord?>(null)

    /** 最近一次更新的完成记录（成功/失败与完成时间）；未跑过为 null */
    val lastResult: kotlinx.coroutines.flow.StateFlow<DownloadRecord?> = lastResultState

    /**
     * 下载并应用更新。防重入占位用跨下载器共用的 [DownloadGate]
     * （与语法模型单独下载共用一把，见该类注释）：[downloadAndApply]
     * 入口抢占，持有到整个函数结束（含在 updateMutex 上排队
     * 等锁期间）。updateMutex 只保证串行、挡不住「排队重跑一遍」，
     * 且旧写法任何一次调用结束都会把运行状态置回 false，先结束者
     * 会让排队中的调用假复位；闸把「排队」也算作进行中，重复调用
     * 在入口即被拒，并与语法模型下载跨路互斥。
     * 交叉守门（先于一切状态置位与文件写入）：部署进行中（含
     * 超时孤儿）或导入事务进行中时一律 BUSY，不触碰运行状态
     * 与完成记录——被拒不得表现为失败；部署状态只在入口与换位
     * 前各读一次（见 [downloadAndApplyLocked] 步骤 4 前的复核），
     * 入口读不到导入换位阶段的部署（那时部署还没开始），故另
     * 用 [UpdateImportGate] 与导入事务互斥。锁序固定：先交叉闸、
     * 后 DownloadGate，释放反序；交叉闸被拒时尚未抢 DownloadGate。
     * @return 三态结果：被拒（已有任一路下载在跑、部署进行中、
     * 导入事务进行中）为 [DownloadResult.BUSY]，与失败严格区分
     */
    suspend fun downloadAndApply(
        prefs: android.content.SharedPreferences,
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (UpdateProgress) -> Unit = {},
    ): DownloadResult {
        if (deployBlocksUpdate()) {
            Timber.w("部署进行中，忽略本次方案更新调用")
            return DownloadResult.BUSY
        }
        if (!UpdateImportGate.tryAcquire()) {
            Timber.w("方案导入进行中，忽略本次方案更新调用")
            return DownloadResult.BUSY
        }
        try {
            if (!DownloadGate.tryAcquire()) {
                Timber.w("已有大文件下载在进行中，忽略本次方案更新调用")
                return DownloadResult.BUSY
            }
            runningState.value = true
            progressState.value = null
            try {
                val ok = updateMutex.withLock {
                    downloadAndApplyLocked(prefs, info, cacheDir) { progress ->
                        progressState.value = progress
                        onProgress(progress)
                    }
                }
                val result = if (ok) DownloadResult.SUCCESS else DownloadResult.FAILED
                lastResultState.value = DownloadRecord(result, System.currentTimeMillis())
                return result
            } finally {
                // 先复位状态再放闸：闸未放时无人能抢入并置位，
                // 不会出现新调用的 true 被本处 false 覆盖
                progressState.value = null
                runningState.value = false
                DownloadGate.release()
            }
        } finally {
            UpdateImportGate.release()
        }
    }

    private suspend fun downloadAndApplyLocked(
        prefs: android.content.SharedPreferences,
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (UpdateProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        recoverInterruptedUpdate()
        val workDir = File(cacheDir, "wanxiang_update").apply { mkdirs() }
        // 本次下载的内容指纹（下载成功后存入 prefs，供本地/远端比对）
        var newDictFp: String? = null
        var newGramFp: String? = null
        // 先全部下载到临时位置、校验通过后才动现用文件：
        // 避免"词库已换新、模型下载失败"形成新词库+旧模型的混合状态
        var extractDir: File? = null
        var gramPartial: File? = null
        var gramTarget: File? = null
        try {
            // 1. 下载方案包（只取其中 dicts/ 词库）
            if (info.schemaUpdateAvailable) {
                val zipFile = File(workDir, "${info.assetName}$PART_SUFFIX")
                zipFile.delete()
                val ok = downloadFile(schemaUrl(info.schemaRemoteVersion, info.assetName), zipFile, MAX_SCHEMA_BYTES) { d, t ->
                    onProgress(UpdateProgress(UpdateProgress.Stage.SCHEMA_DOWNLOAD, d, t))
                }
                if (!ok) return@withContext false
                val finalZip = File(workDir, info.assetName)
                finalZip.delete()
                check(zipFile.renameTo(finalZip)) { "方案包落盘失败" }
                // 校验口径（S2）：GitHub 登记摘要非空才比对、不一致
                // 放弃更新；摘要为空（内嵌缺项）降级放行——自算哈
                // 希仅记日志，后续词库内容指纹已覆盖内容层比对
                val actualSchemaSha = sha256File(finalZip)
                if (info.schemaZipSha256.isNotBlank() &&
                    !actualSchemaSha.equals(info.schemaZipSha256, ignoreCase = true)
                ) {
                    Timber.w("方案包 SHA-256 与 GitHub 登记不一致，放弃更新")
                    finalZip.delete()
                    return@withContext false
                }
                if (info.schemaZipSha256.isBlank()) {
                    Timber.i("方案包无 GitHub 登记摘要，降级放行（自算 SHA-256=%s）", actualSchemaSha)
                }

                // 2. 解压词库到 staging 目录并校验（暂不切换，等模型也下载
                // 成功后一起应用）。staging 必须与现用 dicts 同级（同在
                // 目标根目录下）：应用时整目录 rename 切换，跨文件系统
                // （如 cacheDir 在内部存储）rename 会失败
                extractDir = File(targetRootDir(info.target), DICTS_STAGING_DIR).apply {
                    deleteRecursivelyNoFollow()
                    mkdirs()
                }
                val count = extractDicts(finalZip, extractDir) { done, total ->
                    onProgress(UpdateProgress(UpdateProgress.Stage.DICTS_EXTRACT, done.toLong(), total.toLong()))
                }
                if (count <= 0) {
                    Timber.w("方案包中未找到词库文件，放弃更新")
                    return@withContext false
                }
                // 计算下载词库的内容指纹（与本地同一算法，供更新后比对）
                newDictFp = try {
                    dictsContentFingerprint(extractDir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "下载词库指纹计算失败")
                    null
                }
            }

            // 3. 下载语法模型到 .part（只下载校验，先不替换现用文件）
            if (info.gramUpdateAvailable) {
                val target = File(DataManager.sharedDataDir, GRAM_FILE_NAME)
                val partial = File(target.parentFile, target.name + GRAM_PART_SUFFIX)
                partial.delete()
                val ok = downloadFile(GRAM_URL, partial, MAX_MODEL_BYTES) { d, t ->
                    onProgress(UpdateProgress(UpdateProgress.Stage.GRAM_DOWNLOAD, d, t))
                }
                if (!ok) {
                    partial.delete()
                    return@withContext false
                }
                if (partial.length() <= 0L) {
                    partial.delete()
                    Timber.w("语法模型下载为空，放弃更新")
                    return@withContext false
                }
                // 计算下载模型的内容指纹（流式）：写入记录供更新后
                // 比对；GitHub 登记摘要非空时同时作强校验，对不上
                // 放弃更新；摘要为空降级放行、自算值直接作记录（S2）
                newGramFp = try {
                    sha256File(partial)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "下载模型指纹计算失败")
                    null
                }
                if (info.gramSha256.isNotBlank() &&
                    (newGramFp == null ||
                        !newGramFp.equals(info.gramSha256, ignoreCase = true))
                ) {
                    Timber.w("语法模型 SHA-256 与 GitHub 登记不一致，放弃更新")
                    partial.delete()
                    return@withContext false
                }
                gramPartial = partial
                gramTarget = target
            }

            // 4. 切换：整目录/整文件 rename 即切换本身。不再逐文件
            // 搬移+清残留：逐文件落位期间现用目录是新旧混合态，中途
            // 被杀 Rime 会加载到半套词库；整目录 rename 后现用目录
            // 要么是旧版完整词库、要么是新版完整词库。上游删掉/改名
            // 的词库文件随旧目录整份进 trash，不再需要单独清残留。
            // 事务日志/掉电回滚已按 S11 拆除：切换中断在两个 rename
            // 之间时，启动清理（recoverInterruptedUpdate）按盘上状
            // 态把 trash 回位；用户重跑一次更新即可，不再为一个手动
            // 操作养回滚体系
            val dictsDir = targetDictsDir(info.target)
            val trashDir = File(targetRootDir(info.target), DICTS_TRASH_DIR)
            val changedDicts = extractDir != null
            val changedGram = gramPartial != null && gramTarget != null
            // 跨下载器安装锁（见 gramFileInstallLock）：从切换起
            // 到版本/指纹记录落盘为止，语法模型的切换与记录是
            // 一个临界区，GramModelDownloader 的落盘不得插进来
            // （否则记录会对上另一路的文件）。
            // 锁序固定：外层 updateMutex 已先持有，本锁恒在其内层
            gramFileInstallLock.lock()
            // 换位前复核：下载中途部署启动时，入口守门已拦不住
            // 本次下载；此处是动现用文件前的最后一道关——部署
            // 进行中（含超时孤儿）即放弃切换与记录，staging 由
            // 外层 finally 的启动清理清掉，现用词库与记录保持
            // 旧的自洽状态，下次更新可重跑。检查与 rename 间无
            // 挂起点；本闸此时由本次更新自己持有，故此处只读
            // 部署状态、不读本闸。残余窗口只剩检查到 rename 间
            // 另一线程刚启动部署的瞬间（无挂起、无 IO，极短），
            // 部署侧若要彻底关掉它，须在部署入口同样读本闸
            // （见汇报未决项）。
            var deployAborted = false
            try {
            if (deployBlocksUpdate()) {
                Timber.w("部署进行中，放弃本次词库切换")
                deployAborted = true
            } else {
            extractDir?.let { staging ->
                // 上一轮残留 trash 不得挡住本次切换：切换已完成时它
                // 只是旧副本，就此作废
                if (trashDir.exists()) trashDir.deleteRecursivelyNoFollow()
                if (dictsDir.exists()) check(dictsDir.renameTo(trashDir)) { "词库旧目录移开失败" }
                check(staging.renameTo(dictsDir)) { "词库目录切换失败" }
            }
            if (gramPartial != null && gramTarget != null) {
                // partial 与 target 同目录，rename 本身即原子替换目标文件；
                // 不要先 delete——那会打开"旧模型已删、新模型未就位"的窗口，
                // 中途被杀将留下无语法模型而 prefs 仍记旧版本的状态
                check(gramPartial.renameTo(gramTarget)) { "语法模型落盘失败" }
            }
            // 切换完成：旧词库目录在锁内直接删——原事务日志流程要
            // 留着它等提交后清理，日志拆除后没有保留理由；语法模
            // 型旧文件已被 rename 覆盖、同样无残留
            if (changedDicts) trashDir.deleteRecursivelyNoFollow()

            // 5. 全部成功后才记录版本号与内容指纹。版本记录按侧写：
            // 只更新了哪侧就只写哪侧——无条件写另一侧的远端值，会在
            // 上游回撤版本等情形下把未变更侧的记录改退，并压过实际
            // 部署版本（本地方案版本解析是记录优先）
            prefs.edit().apply {
                if (changedDicts) putString(scopedKey(info.target, KEY_SCHEMA_VERSION), info.schemaRemoteVersion)
                if (changedGram) putString(KEY_GRAM_PUBLISHED_AT, info.gramRemotePublishedAt)
                // 词库指纹仅在本次下载了方案包时更新（词库侧记录按目标分键）
                if (info.schemaUpdateAvailable) {
                    newDictFp?.let {
                        putString(scopedKey(info.target, KEY_DICT_REMOTE_FP), it)
                        putString(scopedKey(info.target, KEY_DICT_REMOTE_FP_VER), info.schemaRemoteVersion)
                    }
                }
                // 模型指纹仅在本次下载了模型时更新
                if (info.gramUpdateAvailable) {
                    newGramFp?.let {
                        putString(KEY_GRAM_REMOTE_FP, it)
                        putString(KEY_GRAM_REMOTE_FP_PUB, info.gramRemotePublishedAt)
                    }
                }
            }.apply()
            }
            } finally {
                gramFileInstallLock.unlock()
            }
            if (deployAborted) return@withContext false
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "方案更新失败")
            runCatching { recoverInterruptedUpdate() }
            false
        } finally {
            // Cancellation may arrive after the first resource was switched.
            // Clean any leftover update state before cleaning temporary data.
            runCatching { recoverInterruptedUpdate() }
            // 清理临时文件，避免占用存储空间
            runCatching { File(workDir, "rime-wanxiang-lite.zip$PART_SUFFIX").delete() }
            runCatching { File(workDir, "rime-wanxiang-lite.zip").delete() }
            // staging 未切换（失败/取消）时清掉；切换成功后该路径已 rename
            // 为现用 dicts、不存在，此处是空操作。trash 不在此清：切换中断
            // 时它可能是旧词库的唯一副本，留给上方按状态的启动清理处理
            runCatching {
                File(DataManager.sharedDataDir, DICTS_STAGING_DIR).deleteRecursivelyNoFollow()
            }
            // gram 成功时已 rename 走；失败/取消时清掉 .part 残留
            runCatching { gramPartial?.delete() }
        }
    }

    /**
     * 从方案包中只解压 dicts/ 下的词库文件到 destDir。
     * @return 成功解压的文件数
     */
    private suspend fun extractDicts(
        zipFile: File,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        var done = 0
        var extractedBytes = 0L
        val destCanonical = destDir.canonicalPath
        // 用 ZipFile 读中央目录：条目数直接可得，不必像 ZipInputStream 那样
        // 先把整个包流式过一遍（等于完整解压两遍）
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().asSequence()
                .filter { !it.isDirectory && isAllowedEntry(it.name.trimEnd('/')) }
                .toList()
            val total = entries.size
            for (entry in entries) {
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("解压已取消")
                }
                val name = entry.name.trimEnd('/')
                // 剥掉 dicts/ 前缀：解压目录直接代表现用 dicts 目录本身。
                // 这样解压目录与现用目录的相对路径完全同构——内容指纹可直接比对，
                // 应用时也不会再多套一层 dicts/dicts/（旧实现把文件写进了
                // shared/dicts/dicts/，Rime 根本读不到，在线更新的词库从未真正生效）
                val relName = name.removePrefix("dicts/")
                val destFile = File(destDir, relName).canonicalFile
                // 路径穿越防护（必须带分隔符：无分隔符前缀比较可被同前缀兄弟目录绕过）
                if (destFile.path.startsWith(destCanonical + File.separator)) {
                    destFile.parentFile?.mkdirs()
                    // 解压体积上限：防 zip 炸弹（小压缩包解出数 GB 伪词库写满存储）
                    var entryBytes = 0L
                    zf.getInputStream(entry).use { input ->
                        destFile.outputStream().use { out ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                entryBytes += n
                                extractedBytes += n
                                if (entryBytes > MAX_ENTRY_BYTES || extractedBytes > MAX_EXTRACT_TOTAL_BYTES) {
                                    throw IllegalStateException("词库解压体积超上限")
                                }
                                out.write(buf, 0, n)
                            }
                            // 解压出的词库文件同样先 fsync 再参与后续整目录
                            // rename 切换（与下载文件同一掉电安全口径）
                            out.fd.sync()
                        }
                    }
                    done++
                    onProgress(done, total)
                } else {
                    Timber.w("跳过非法路径：%s", name)
                }
            }
        }
        done
    }

    private fun isAllowedEntry(name: String): Boolean =
        ALLOWED_PREFIXES.any { name.startsWith(it) } && name.endsWith(ALLOWED_SUFFIX)

    private suspend fun downloadFile(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        return runCatching {
            // 整包下载走无总时长上限的 downloadClient：共用带
            // callTimeout 的检查用 client 会在 20 秒处掐断读流
            downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("方案更新下载失败：HTTP %d", response.code)
                    return false
                }
                val body = response.body ?: return false
                val total = body.contentLength()
                // 体积上限：上游被污染或异常无限流响应可写满设备存储；声明
                // 长度超限直接拒，chunked 无长度时靠下面累计字节数兜底
                if (total > maxBytes) {
                    Timber.w("下载体积超上限：声明 %d 字节，上限 %d", total, maxBytes)
                    return false
                }
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var reported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return false
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded > maxBytes) {
                                Timber.w("下载体积超上限：已下载 %d 字节，上限 %d", downloaded, maxBytes)
                                return false
                            }
                            if (downloaded - reported >= REPORT_STEP ||
                                (total > 0 && downloaded >= total)
                            ) {
                                onProgress(downloaded, total)
                                reported = downloaded
                            }
                        }
                        onProgress(downloaded, total)
                        // 写完先 fsync 文件本体再走后续 rename/校验：只靠
                        // close 落盘，断电时文件内容可能还没真正上盘，
                        // 提交标记却已写下，恢复会误判这批数据完整
                        output.fd.sync()
                    }
                }
                // 完整性校验：服务端声明了长度时，实际字节数必须一致，
                // 否则截断的坏文件会被当成功落盘（语法模型坏文件会让旧模型也找不回来）
                if (total > 0 && target.length() != total) {
                    Timber.w("下载不完整：期望 %d 字节，实际 %d 字节", total, target.length())
                    return false
                }
                true
            }
        }.onFailure {
            if (it !is kotlinx.coroutines.CancellationException) {
                Timber.e(it, "方案更新下载失败")
            }
        }.getOrDefault(false)
    }
}
