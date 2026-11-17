package com.jobeen.ime.data.backup

import android.content.Context
import android.os.Build
import com.jobeen.ime.R
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.data.App
import com.jobeen.ime.data.ai.AiBackupCodec
import com.jobeen.ime.data.ai.AiBackupData
import com.jobeen.ime.data.database.AIConversation
import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.database.AIMessage
import com.jobeen.ime.data.database.AIProvider
import com.jobeen.ime.data.database.AIProviderModel
import com.jobeen.ime.data.database.AISystemPrompt
import com.jobeen.ime.data.database.AIUserPrompt
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.CandidatePrefer
import com.jobeen.ime.data.database.CandidateSorting
import com.jobeen.ime.data.database.ClipboardDatabase
import com.jobeen.ime.data.database.ClipboardGroup
import com.jobeen.ime.data.database.ClipboardRecord
import com.jobeen.ime.data.database.dedupeClipboardByText
import com.jobeen.ime.data.database.PhraseRecord
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.skin.store.AtomicFiles
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 整体备份与还原：设置（全部 SharedPreferences）+ 剪贴板历史 + 常用语 +
 * 选词偏好 + 候选排序 + 皮肤库文件，导出为单个口令加密文件（.jbk）。
 *
 * 加密格式：文件 = 头部 JSONObject 的 UTF-8 字节 + '\n' + 密文。头部含
 * format/version/kdf/iterations/salt/iv，明文可见但不含任何数据；头部
 * 字节同时作为 GCM 的 AAD 被认证（篡改头部参数同样导致解密失败）；
 * 密文为 AES-256-GCM（口令经 PBKDF2 派生），口令错误或文件被篡改时
 * GCM 校验失败，还原拒绝执行。KDF 算法随文件记录（新设备 SHA-256、
 * 低版本设备 SHA-1），跨设备可迁移。iterations 只接受合理区间，
 * 构造文件用极大迭代数拖死还原会被直接拒绝。
 *
 * WebDAV 配置整表（user_dict_sync）按设备本地状态处理：导出照常整表
 * 采集（包本身有口令加密），还原时整表跳过回写——服务器地址、账号
 * 口令与同步时刻都是本机配置/状态，不随备份跨设备走（清点 S8 定论，
 * 替代原脱敏＋回填五层规则与密码专用迁移通道）。
 *
 * 皮肤库文件（index.json + skins/ + assets/）随包走、合并写回：
 * 只覆盖同路径文件，不删除备份中没有的现文件；空库不写该段、
 * 原始字节超 8MB 整段跳过只记日志（不阻塞整包）；旧备份无此段
 * 时还原不碰现皮肤。写回后重启进程，下次启动对账收敛索引。
 *
 * 还原严格分两段：先把解密后的内容完整解析为内存对象（此阶段零写
 * 入，任何结构/类型错误直接拒绝，不留半还原状态），再执行写入。
 * Room 四表是合并写回（D31）：备份与当前状态按“当前优先、备份补缺”
 * 取并集——备份后新学的内容留在当前表里，不会被整表覆盖丢掉；备份
 * 里有、当前已删的内容则补回来。prefs 随后逐文件同步提交（仍整表
 * 覆盖，设置还原即回到备份时点）。各管理器有内存缓存，因此 UI 层
 * 在还原成功后应引导重启应用使全部生效。
 *
 * 包内清单（D-M1，payload v2）：载荷末尾带 `manifest` 字段（版本＋
 * 各源计数／存在标记），恢复前逐项核对；外层头部 FORMAT_VERSION
 * 仍为 1（旧备份的解密通道不变），无 `manifest` 字段的旧备份跳过
 * 校验直接兼容，`manifest.payloadVersion` 不是 2 或清单损坏时明确
 * 报错（见 [BackupManifest]）。
 */
object BackupManager {

    const val FILE_EXTENSION = "jbk"
    private const val FORMAT = "jime-backup"
    private const val FORMAT_VERSION = 1
    private const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
    private const val KDF_SHA1 = "PBKDF2WithHmacSHA1"
    // 新备份按 OWASP 对 PBKDF2-HMAC-SHA256 的建议取 600k；还原时迭代数读自文件头
    // （区间 MIN..MAX 已覆盖），旧的 20 万次备份仍可还原
    private const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val SALT_BYTES = 32
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val WEBDAV_PREFS = "user_dict_sync"

    /**
     * 设备本地状态偏好，不随备份还原回写：内容是本机状态而非用户
     * 数据——模型/方案的下载记录（随备份来到新设备会与本机实际
     * 文件脱钩，检查误报「已最新」）、数据库维护的一次性标记
     * （db_maintenance 的 VACUUM 完成标记描述的是本机库文件的
     * 物理状态，还原到新设备会让新设备误以为已擦除而永不补跑）、
     * WebDAV 同步整表（服务器/账号/口令/同步时刻都是本机配置，
     * 清点 S8 定论）。导出端照常采集（无害），只在写入段按文件名
     * 跳过——这样用户手上已有的旧备份包还原时同样不会回写这些。
     */
    private val DEVICE_LOCAL_PREFS =
        setOf("speech_model_prefs", "wanxiang_update", "db_maintenance", WEBDAV_PREFS)

    open class BackupException(message: String) : Exception(message)

    /**
     * 载荷版本兼容（1.12）：只拒未来版。旧版备份（payloadVersion <
     * CURRENT）照常还原——清单校验只核对清单里有的键、缺失的新键
     * 不判不符（见 BackupManifestTest.legacy10KeyManifest）；未来
     * 版（> CURRENT）才拒绝：新版可能写了旧版读不懂的字段，强行
     * 还原会静默丢数据。纯函数，可单测。
     */
    internal fun isSupportedPayloadVersion(payloadVersion: Int): Boolean =
        payloadVersion <= BackupManifest.CURRENT_PAYLOAD_VERSION

    /**
     * 采集中途数据变了：逐文件改动检测（[BackupChangeGuard]）或
     * Room 快照读到一半失败时抛。消息直接面向用户（备份页 Toast
     * 原样显示），告诉用户重做一次即可。
     */
    class BackupDataChangedException(operation: String) :
        BackupException("${operation}中数据变了，请重试")

    // ---------------- 备份 ----------------

    /** 备份出包结果：文件字节。 */
    data class CreatedBackup(
        val bytes: ByteArray,
    )

    /** 采集结果：载荷 JSON。 */
    private data class CollectedPayload(
        val json: JSONObject,
    )

    /** 采集当前数据并加密，返回完整备份文件字节（头部行 + 密文） */
    suspend fun createBackup(password: CharArray): CreatedBackup {
        val collected = collectPayload("备份")
        return CreatedBackup(
            bytes = encryptPayload(collected.json, password),
        )
    }

    /** 把已收集的载荷 JSON 加密为备份文件字节。 */
    private fun encryptPayload(payloadJson: JSONObject, password: CharArray): ByteArray {
        val payload = payloadJson.toString().toByteArray(Charsets.UTF_8)
        val kdf = if (Build.VERSION.SDK_INT >= 26) KDF_SHA256 else KDF_SHA1
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val header = JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("kdf", kdf)
            .put("iterations", ITERATIONS)
            .put("salt", salt.toBase64())
            .put("iv", iv.toBase64())
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val key = deriveKey(password, salt, ITERATIONS, kdf)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        // 头部字节作 AAD：iterations/kdf 等参数被篡改时解密直接失败
        cipher.updateAAD(headerBytes)
        val encrypted = cipher.doFinal(payload)
        return headerBytes + byteArrayOf('\n'.code.toByte()) + encrypted
    }

    private suspend fun collectPayload(operation: String = "备份"): CollectedPayload {
        val root = JSONObject()
        val guard = BackupChangeGuard()
        // —— SharedPreferences：枚举 shared_prefs 目录全部文件整表导出
        val prefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val prefsJson = JSONObject()
        var prefsEntries = 0
        val names = prefsDir.listFiles { f -> f.extension == "xml" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
        for (name in names) {
            val file = File(prefsDir, "$name.xml")
            // 枚举到但读前已消失 = 采集中途被删，整包失败，不静默丢一表。
            if (!file.isFile) throw BackupDataChangedException(operation)
            // 逐文件改动检测（D-M1）：读前取元数据，读后重验，变了即
            // 整个备份失败、不产残包（见 BackupChangeGuard）。
            // WO33 第 1 项：值从磁盘文件解析（PrefsXml），不读
            // SharedPreferences.all——all 是内存，守卫验的是磁盘，
            // 一次 apply() 在读时已改内存、落盘却在重验之后时，旧
            // 读法会把“未来的值”装进包而守卫看不出；磁盘读让值
            // 与守卫验的是同一份字节。文件损坏／形状异常才回落
            // 到 all 内存读（旧行为，见 PrefsXml 的 KDoc）。
            val entries = guard.guard(file, operation) {
                if (!file.isFile) throw BackupDataChangedException(operation)
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    // 读到一半文件消失／不可读 = 采集中途被改，按变了处理。
                    throw BackupDataChangedException(operation)
                }
                val all: Map<String, *> = try {
                    PrefsXml.parse(bytes)
                } catch (e: Exception) {
                    Timber.w(e, "prefs xml parse failed ($name), fallback to in-memory read")
                    appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
                }
                val entries = JSONObject()
                for ((key, value) in all) {
                    if (value == null) continue
                    entries.put(key, encodePrefValue(value))
                }
                entries
            }
            prefsEntries += entries.length()
            prefsJson.put(name, entries)
        }
        // WebDAV 整表照常采集进包（S8）：还原侧按设备本地表整表跳过，
        // 采集侧无脱敏——密码在 prefs 里本就是 Keystore 密文、包又有
        // 口令加密，不再需要剔键＋独立字段迁移通道
        root.put("prefs", prefsJson)

        // —— Room 五表：跨库一致快照（D-M1，fcitx SqliteSnapshots 形状）——
        // 另开一条原生 SQLite 连接（RoomSnapshotReader），ATTACH 两库后
        // 在一个事务内读出五张表，事务外 PRAGMA quick_check(1) 校验；
        // 失败即备份失败，不回落旧的 DAO 逐表读（回落会掩盖系统性
        // SQL 错误）。旧实现是两库各一次 Room 事务（库内一致、跨库
        // 可取自不同时点），且与 prefs 之间无统一快照点；新实现里
        // 两库取自同一事务时点，prefs／文件类另由逐文件改动检测兜底
        // （见 BackupChangeGuard）。全局写锁／停写总闸仍无（写盘方
        // 散在数十处，造闸是跨组件架构活，记已知差距）。
        // WO33 第 1 项的缓解（全局写锁未做，原因见本函数末尾的结算
        // 注释）：① prefs 改磁盘读（上）；② 两库的 db 文件与 -wal
        // 文件在快照读前记标记、结算时重验——两库未显式设
        // journalMode，走 Room 默认（WAL 下写先落 -wal，主文件只在
        // checkpoint 时变；非 WAL 下写直接落主文件），两文件都盯
        // 即覆盖两种模式；-shm 只随连接开关变，不盯。③ 采集末尾
        // 一次结算重验（见下），三类源第一次有了统一复核点。
        // 先开库（迁移已跑完）再裸读：裸读旧版库文件会因缺列失败。
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)
        // 拆库迁移本体已随清点 Q2 退场（现行设备早已迁完），采集直接
        // 读新库；原「迁移不成中止备份」的耦合随之解除

        // 遗留超限行先清理（D-S5）：限长前的超限行读出即抛
        // SQLiteBlobTooBigException，备份导出会被一行毒化整包。
        ClipboardManager.sanitizeOversizedOnce(appContext)

        val appDbFile = db.openHelper.writableDatabase.path?.let { File(it) }
            ?: throw BackupException(backupString(R.string.backup_read_db_failed_locate_main))
        val clipDbFile = clipDb.openHelper.writableDatabase.path?.let { File(it) }
            ?: throw BackupException(backupString(R.string.backup_read_db_failed_locate_clip))
        // 结算标记（WO33 第 1 项）：在清理之后、快照读之前取——
        // sanitizeOversizedOnce 自身的写是备份操作的一部分，不计入
        // “采集中途数据变了”；只读快照连接不改 db／-wal 文件
        // （只读恢复只重建 -shm），标记不会被本次读本身触发。
        // -wal 不存在时记 null 标记：采集中途出现即变了。
        guard.markForSettle(appDbFile)
        guard.markForSettle(File(appDbFile.path + "-wal"))
        guard.markForSettle(clipDbFile)
        guard.markForSettle(File(clipDbFile.path + "-wal"))
        val room = RoomSnapshotReader(appDbFile, clipDbFile).read()
        val clipRows = room.clipboard
        val groupRows = room.groups
        val clips = JSONArray()
        // 只导出有效行：历史软删除存量不进备份（删除已改物理删，此为存量兜底）
        for (r in clipRows) {
            clips.put(
                JSONObject()
                    .put("id", r.id).put("text", r.text).put("timestamp", r.timestamp)
                    .put("cloud", r.cloud).put("deleted", r.deleted).put("deletedAt", r.deletedAt)
                    .put("pinned", r.pinned).put("sortOrder", r.sortOrder)
                    .put("groupId", r.groupId).put("isFavorite", r.isFavorite)
            )
        }
        root.put("clipboard", clips)

        // 自定义分组（H2-4）：与剪贴板行一起出包，还原时按名称合并、条目 groupId 重映射
        val groups = JSONArray()
        for (g in groupRows) {
            groups.put(
                JSONObject()
                    .put("id", g.id).put("name", g.name)
                    .put("sortOrder", g.sortOrder).put("createdAt", g.createdAt)
            )
        }
        root.put("clipboard_groups", groups)

        val phraseRows = room.phrases
        val preferRows = room.prefers
        val sortingRows = room.sortings
        val phrases = JSONArray()
        for (r in phraseRows) {
            phrases.put(
                JSONObject()
                    .put("id", r.id).put("text", r.text)
                    .put("label", r.label).put("createdAt", r.createdAt)
                    .put("sortOrder", r.sortOrder).put("pinned", r.pinned)
                    .put("shortcut", r.shortcut).put("category", r.category)
                    .put("tags", r.tags).put("variables", r.variables)
                    .put("lastUsedAt", r.lastUsedAt)
            )
        }
        root.put("phrases", phrases)

        val prefers = JSONArray()
        for (r in preferRows) {
            prefers.put(
                JSONObject()
                    .put("text", r.text).put("count", r.count).put("context", "")
                    .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
            )
        }
        root.put("candidatePrefers", prefers)

        val sortings = JSONArray()
        for (r in sortingRows) {
            sortings.put(
                JSONObject()
                    .put("key", r.key)
                    .put("candidateIds", JSONArray(r.candidateIds))
            )
        }
        root.put("candidateSortings", sortings)

        // —— AI 七表（WO52 9.1）：经 AiBackupCodec 脱敏后出包——服务商
        // apiToken 一律清空，且 JSON 里**不写 `apiToken` 键**（token
        // 永不进备份文件；对端备份明文存 AI Token 是缺陷，不照搬）。
        // DAO 直读（导出在后台协程内；AI 表在备份窗口内几乎不动，
        // 与 prefs 段同口径，不走 RoomSnapshotReader 的快照读）。
        val aiDb = AppDatabase.getInstance(appContext)
        val aiExport = AiBackupCodec.forExport(
            AiBackupData(
                providers = aiDb.aiProviderDao().getAll(),
                models = aiDb.aiProviderModelDao().getAll(),
                systemPrompts = aiDb.aiSystemPromptDao().getAll(),
                userPrompts = aiDb.aiUserPromptDao().getAll(),
                instructions = aiDb.aiInstructionDao().getAll(),
                conversations = aiDb.aiConversationDao().getAll(),
                messages = aiDb.aiMessageDao().getAll(),
            ),
        )
        val aiProvidersJson = JSONArray()
        for (p in aiExport.providers) {
            val o = JSONObject()
                .put("id", p.id).put("name", p.name).put("type", p.type)
                .put("sort", p.sort).put("isDefault", p.isDefault)
                .put("isEnabled", p.isEnabled).put("isBuiltIn", p.isBuiltIn)
                .put("builtInCanBeDelete", p.builtInCanBeDelete)
                .put("baseURL", p.baseURL).put("apiPath", p.apiPath)
                .put("requestTimeout", p.requestTimeout)
                .put("maxOutputTokens", p.maxOutputTokens)
                .put("contextWindow", p.contextWindow)
            p.temperature?.let { o.put("temperature", it) }
            p.presencePenalty?.let { o.put("presencePenalty", it) }
            p.frequencyPenalty?.let { o.put("frequencyPenalty", it) }
            p.customParametersJSON?.let { o.put("customParametersJSON", it) }
            p.defaultModelId?.let { o.put("defaultModelId", it) }
            aiProvidersJson.put(o)
        }
        root.put("ai_providers", aiProvidersJson)
        val aiModelsJson = JSONArray()
        for (m in aiExport.models) {
            val o = JSONObject()
                .put("id", m.id).put("displayName", m.displayName)
                .put("modelID", m.modelID).put("sort", m.sort)
                .put("isEnabled", m.isEnabled)
            m.providerId?.let { o.put("providerId", it) }
            aiModelsJson.put(o)
        }
        root.put("ai_provider_models", aiModelsJson)
        val aiSysJson = JSONArray()
        for (p in aiExport.systemPrompts) {
            val o = JSONObject()
                .put("id", p.id).put("name", p.name).put("iconName", p.iconName)
                .put("purpose", p.purpose).put("body", p.body)
                .put("sort", p.sort).put("isDefault", p.isDefault)
                .put("isEnabled", p.isEnabled).put("isBuiltIn", p.isBuiltIn)
                .put("builtInCanBeDelete", p.builtInCanBeDelete)
            p.generationOverridesJSON?.let { o.put("generationOverridesJSON", it) }
            aiSysJson.put(o)
        }
        root.put("ai_system_prompts", aiSysJson)
        val aiUserJson = JSONArray()
        for (p in aiExport.userPrompts) {
            aiUserJson.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("iconName", p.iconName)
                    .put("purpose", p.purpose).put("templateBody", p.templateBody)
                    .put("sort", p.sort).put("isDefault", p.isDefault)
                    .put("isEnabled", p.isEnabled).put("isBuiltIn", p.isBuiltIn)
                    .put("builtInCanBeDelete", p.builtInCanBeDelete),
            )
        }
        root.put("ai_user_prompts", aiUserJson)
        val aiInsJson = JSONArray()
        for (i in aiExport.instructions) {
            val o = JSONObject()
                .put("id", i.id).put("name", i.name).put("detail", i.detail)
                .put("sort", i.sort)
            i.systemPromptID?.let { o.put("systemPromptID", it) }
            i.userPromptID?.let { o.put("userPromptID", it) }
            i.providerID?.let { o.put("providerID", it) }
            aiInsJson.put(o)
        }
        root.put("ai_instructions", aiInsJson)
        val aiConvJson = JSONArray()
        for (c in aiExport.conversations) {
            val o = JSONObject()
                .put("id", c.id).put("title", c.title)
                .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
                .put("isTitleUserEdited", c.isTitleUserEdited)
            c.systemPromptID?.let { o.put("systemPromptID", it) }
            c.providerID?.let { o.put("providerID", it) }
            c.modelID?.let { o.put("modelID", it) }
            c.snapshotJSON?.let { o.put("snapshotJSON", it) }
            aiConvJson.put(o)
        }
        root.put("ai_conversations", aiConvJson)
        val aiMsgJson = JSONArray()
        for (m in aiExport.messages) {
            val o = JSONObject()
                .put("id", m.id).put("role", m.role).put("status", m.status)
                .put("sequence", m.sequence).put("createdAt", m.createdAt)
                .put("content", m.content).put("tokenEstimate", m.tokenEstimate)
                .put("isSuperseded", m.isSuperseded)
            m.conversationId?.let { o.put("conversationId", it) }
            m.originalInput?.let { o.put("originalInput", it) }
            m.renderedRequest?.let { o.put("renderedRequest", it) }
            m.userPromptName?.let { o.put("userPromptName", it) }
            m.usageJSON?.let { o.put("usageJSON", it) }
            m.errorCategory?.let { o.put("errorCategory", it) }
            m.requestID?.let { o.put("requestID", it) }
            m.regenerationOfID?.let { o.put("regenerationOfID", it) }
            m.configSnapshotJSON?.let { o.put("configSnapshotJSON", it) }
            aiMsgJson.put(o)
        }
        root.put("ai_messages", aiMsgJson)

        // —— 输入统计（2026-10-29 批量第 4 项）：文件型数据不进 prefs
        // 枚举，须显式采集；内容仅计数与时长、无输入内容（InputStats
        // 自身口径）。超 1MB 的异常文件跳过（不阻塞整包备份）。
        // 改动检测（见 BackupChangeGuard）：读前取元数据，读后重验，
        // 变了即整个备份失败、不产残包（统计文件在采集窗口内被改写时
        // 旧实现会无声地导出读到一半的 JSON）。旧实现直接 readBytes
        // 读整文件（统计文件只在写盘方写盘时可能被读到一半）。
        val statsFile = File(appContext.filesDir, "input_stats/stats.json")
        if (statsFile.isFile) {
            val bytes = guard.guard(statsFile, operation) {
                runCatching { statsFile.readBytes() }.getOrNull()
            }
            if (bytes != null) {
                if (bytes.size <= 1_048_576) {
                    root.put("input_stats_json", String(bytes, Charsets.UTF_8))
                } else {
                    Timber.w("input stats file too large, skipped in backup")
                }
            }
        }

        // —— 结算重验（WO33 第 1 项，D-M1 残余窗口的缓解）——
        // 采集全部结束、清单附上之前，把本次读过的全部文件（prefs
        // 各表、Room 两库 db＋-wal、统计文件、皮肤库文件）
        // 在同一个点上再验一遍：任一文件自其读前标记之后变了，
        // 即整个备份失败、不产残包。这是三类源第一次有统一复核点，
        // 残余窗口由“整个采集时长”缩到“结算点之后”。
        // 结算点之后才落盘的写不属于本次快照（快照语义上正确排除）；
        // 单源写晚落盘只是合法的更早快照，不撕裂。WO36（D-P2）已把
        // 全仓唯一的真 Room＋prefs 配对写逐个收口，不再留“配对撕裂”
        // 的逃逸情形：PhraseManager.ensureSeeded 改同步 commit()
        // （IO 线程，无 ANR 风险；失败留 seeded=false 自愈），
        // ClipboardManager.setMaxEntries 的 prefs 侧改同步 commit()
        // （小文件；库内裁剪仍异步，备份多带的只是裁剪前合法存量，
        // 下次新增即收敛）。其余配对写不存在（WO34 已核实）。
        // 全局写锁／停写总闸仍未做：写盘方约 130 处 prefs 编辑点
        // （39 处 .edit(＋91 处 edit{}，17＋ 文件）＋157 处 DAO 写
        // 调用（43 文件）＋60 处文件直写（AtomicFiles 16＋
        // writeText／writeBytes 44），无单一收口（190 处
        // getSharedPreferences 直取实例）；造闸须全数改走持锁
        // 写，跨组件架构活，非一批可做。且锁本身有风险：主线程
        // 的设置开关 apply() 会被采集全程阻塞（皮肤段 8MB 时可
        // 达秒级，ANR 线 5 秒）；采集持 Room 快照事务等写锁、写
        // 方持 Room 写事务等闸锁即构成死锁环。彻底消除仍是已知差距。
        // —— 结算点重枚举 prefs 目录（D-L2）：采集中途新建的 prefs
        // 文件不在跟踪表里，reverifyAll 无从比对；新文件出现即整包
        // 失败（与“枚举到但读前消失”的口径对称）。不纳入本次备份：
        // 新文件的内容未经守卫在一致点上验过，收入包会把一次可能
        // 撕裂的读装进快照；失败即 loud 失败，用户重做一次即可。
        // 删文件不走这里：被删的已跟踪文件由 reverifyAll 抓住。
        val newPrefsFiles = newPrefsFilesSince(names.toSet(), prefsDir)
        if (newPrefsFiles.isNotEmpty()) throw BackupDataChangedException(operation)
        guard.reverifyAll(operation)

        // —— 包内清单（D-M1，fcitx 清单 v2 形状）：各源计数／存在标记，
        // 恢复前 [parsePayload] 逐项核对；旧备份无此字段，解析时跳过
        // 校验直接兼容（外层 FORMAT_VERSION 仍为 1，见类 KDoc）。
        val manifest = BackupManifest(
            payloadVersion = BackupManifest.CURRENT_PAYLOAD_VERSION,
            counts = manifestCounts(
                prefsFiles = prefsJson.length(),
                prefsEntries = prefsEntries,
                clipboardCount = clipRows.size,
                clipboardGroupsCount = groupRows.size,
                phrasesCount = phraseRows.size,
                prefersCount = preferRows.size,
                sortingsCount = sortingRows.size,
                aiProvidersCount = aiExport.providers.size,
                aiInstructionsCount = aiExport.instructions.size,
                aiConversationsCount = aiExport.conversations.size,
                aiMessagesCount = aiExport.messages.size,
                inputStatsPresent = root.has("input_stats_json"),
            ),
        )
        root.put("manifest", manifest.toJsonObject())
        return CollectedPayload(root)
    }


    private fun encodePrefValue(value: Any): JSONObject {
        val obj = JSONObject()
        when (value) {
            is String -> obj.put("t", "s").put("v", value)
            is Int -> obj.put("t", "i").put("v", value)
            is Long -> obj.put("t", "l").put("v", value)
            is Float -> obj.put("t", "f").put("v", value.toDouble())
            is Boolean -> obj.put("t", "b").put("v", value)
            is Set<*> -> obj.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
            else -> obj.put("t", "s").put("v", value.toString())
        }
        return obj
    }

    // ---------------- 还原 ----------------

    /**
     * 解密并还原。Room 五表合并写回（见类 KDoc 的 D31 口径）。
     * 口令错误/文件损坏/结构非法抛 [BackupException]，且解密与完整
     * 结构解析全部通过后才开始写任何数据（不会半还原）。写入前先采
     * 一份当前状态的内存快照：写入阶段任一步失败都用它整体回滚，
     * 绝不留下新旧混杂的半还原状态。快照落盘的第二层曾随清点 S7
     * 退场（进程被杀的还原中断无自动恢复，用户重导备份即可）；
     * WO28 已推翻该定论——还原前把当前状态快照＋journal 落盘
     * （见 [RestoreJournal]），启动时 AppStartup.recoverInterruptedRestore
     * 对账：完成／尚未写盘即清理，中断则备份页提示用户重导或回滚。
     * 快照是明文 JSON（见 RestoreJournal 的 KDoc：进程死后备份口令
     * 已不在内存，加密快照无法解密；应用沙箱私有权＋云备份排除，
     * 保护级别与现库／prefs 同级）。
     */
    /**
     * 回滚快照内存 token 补丁（L-31）：快照解析后 provider token
     * 已被强制清空，用现库 token 直接 patch（重构整个 ParsedBackup
     * 对象，只用于内存回滚；落盘 journal 快照仍不含 token）。
     * 磁盘回滚路径读 journal 文件，不动，靠 applyPayload 的保留
     * 逻辑兜底。
     */
    private suspend fun patchSnapshotTokens(snapshot: ParsedBackup): ParsedBackup {
        val ai = snapshot.ai ?: return snapshot
        val localTokens = AppDatabase.getInstance(appContext)
            .aiProviderDao().getAll().associate { it.id to it.apiToken }
        if (localTokens.isEmpty()) return snapshot
        val patched = ai.copy(
            providers = ai.providers.map { provider ->
                provider.copy(apiToken = localTokens[provider.id] ?: provider.apiToken)
            },
        )
        return ParsedBackup(
            prefs = snapshot.prefs,
            clipboard = snapshot.clipboard,
            clipboardGroups = snapshot.clipboardGroups,
            phrases = snapshot.phrases,
            prefers = snapshot.prefers,
            sortings = snapshot.sortings,
            inputStatsJson = snapshot.inputStatsJson,
            ai = patched,
        )
    }

    /**
     * 还原流程主调（解密→解析→快照→合并→写回→回滚兜底）。
     * 返回还原时跳过的孤儿 AI 消息条数（L-33）。
     */
    suspend fun restoreBackup(bytes: ByteArray, password: CharArray): Int {
        val payload = decryptPayload(bytes, password)
        val parsed = parsePayload(payload)
        // 快照解析结构直接取自当前状态的采集结果——回滚回放是把我们
        // 自己的完整状态写回去；也不再对快照二次解密重解析（省掉一
        // 轮 60 万次 PBKDF2）。采集本身可能因采集中途数据变了而失败
        // （BackupDataChangedException），此时尚未写任何数据，直接失败。
        val snapshotJson = collectPayload("还原")
        val snapshotParsed = patchSnapshotTokens(parsePayload(snapshotJson.json))
        // 合并语义（D31）：四表先取“当前 ∪ 备份”（当前优先），再整表
        // 写回合并结果——备份后新学的内容保留，备份里有、当前已删的
        // 内容补回来。合并只发生在还原入口这一处，applyPayload 本体
        // 仍是整表替换，因此失败回滚仍用快照原样写回，回滚口径不变。
        val merged = mergeForRestore(snapshotParsed, parsed)
        // 失败还原新建的偏好文件（在备份包里、不在还原前快照里）：
        // 回滚时删掉，否则还原后多出失败还原写入的设置文件（D-M2）。
        // 整表跳过的文件（设备本地表、scheme_dirs）从不被回写，不在此列。
        val rollbackExtraPrefsFiles = (merged.prefs.keys - snapshotParsed.prefs.keys)
            .filter { !isSkippedPrefsFile(it) }.toSet()

        // —— 还原 journal（D-S2，fcitx journal 形状）：快照落盘＋
        // STAGING journal 写在一切写盘之前；写盘中途被杀，下次启动
        // 凭 journal 对账（见 reconcileInterruptedRestore）。
        // 顺序：先写快照文件（新 token 名，不与旧快照冲突），再原子
        // 覆盖 journal（AtomicFiles 写失败时旧 journal 原样保留），
        // 最后清掉旧 token 的孤儿快照——旧 journal 只在新 journal
        // 成功落盘后才被取代。
        val journalDir = RestoreJournal.dir(appContext.filesDir)
        val token = UUID.randomUUID().toString()
        val snapshotFile = RestoreJournal.snapshotFile(journalDir, token)
        try {
            AtomicFiles.write(
                snapshotFile,
                snapshotJson.json.toString().toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw BackupException(backupString(R.string.backup_restore_prepare_snapshot_failed, e.message ?: backupString(R.string.backup_disk_write_failed)))
        }
        val journalEntry = RestoreJournal.Entry(
            token = token,
            phase = RestoreJournal.Phase.STAGING,
            startedAt = System.currentTimeMillis(),
            backupFingerprint = DigestUtil.sha256Hex(bytes),
            snapshotFile = snapshotFile.name,
            completedSections = emptySet(),
        )
        try {
            RestoreJournal.write(journalDir, journalEntry)
        } catch (e: Exception) {
            runCatching { snapshotFile.delete() }
            throw BackupException(backupString(R.string.backup_restore_prepare_journal_failed, e.message ?: backupString(R.string.backup_disk_write_failed)))
        }
        RestoreJournal.sweepOrphanSnapshots(journalDir, token)
        var skippedAiMessages = 0
        try {
            // REPLACING 是强制推进：journal 推进不了就不开始写盘——
            // 没有 journal 兜底的写盘，中断后无从对账。
            RestoreJournal.write(journalDir, journalEntry.copy(phase = RestoreJournal.Phase.REPLACING))
            // 写入与回滚都不可取消：调用方协程被取消（界面销毁等）时
            // 半途而废比继续写完更糟——半还原状态正是快照机制要防的
            withContext(NonCancellable) {
                skippedAiMessages = applyPayload(
                    merged,
                    onSectionDone = { section ->
                        // 段进度是尽力而为的记录：journal 写失败不阻断
                        // 还原本体（阶段已是 REPLACING，中断后照常提示）；
                        // 段进度丢了也不影响回滚覆盖判定（见
                        // uncoveredSectionsForRollback 的 KDoc：可部分写
                        // 的段要么原子、要么快照恒覆盖）。
                        // 失败只记日志（失败改 Timber.e 大声上报，不再静默 w）。
                        runCatching {
                            val current = RestoreJournal.read(journalDir)
                                ?: journalEntry.copy(phase = RestoreJournal.Phase.REPLACING)
                            RestoreJournal.write(
                                journalDir,
                                current.copy(
                                    completedSections = current.completedSections + section,
                                    inProgressSections = current.inProgressSections - section,
                                ),
                            )
                        }.onFailure { Timber.e(it, "restore journal progress update failed") }
                    },
                )
            }
            // 成功：先记 COMMITTED（给“写完数据、未及删 journal 就被
            // 杀”的窗口留标记），再删 journal＋快照；COMMITTED 写失败
            // 也不阻断——删 journal 本就是目标，删不掉时 COMMITTED 标
            // 记让启动直接清理、不误报。
            runCatching {
                RestoreJournal.write(
                    journalDir,
                    journalEntry.copy(phase = RestoreJournal.Phase.COMMITTED),
                )
            }
            RestoreJournal.clear(
                journalDir,
                journalEntry.copy(phase = RestoreJournal.Phase.COMMITTED),
            )
        } catch (e: Exception) {
            val replayed = runCatching {
                withContext(NonCancellable) {
                    applyPayload(
                        snapshotParsed,
                        forRollback = true,
                        rollbackExtraPrefsFiles,
                    )
                }
            }.isSuccess
            // 回放成功才算回滚成功。
            val rolledBack = replayed
            if (rolledBack) {
                // 内存回滚已把状态恢复到还原前：中断记录不再有意义，
                // 清掉，下次启动不提示。
                RestoreJournal.clear(journalDir, journalEntry)
            }
            // 回滚未成功时 journal 保留（仍是 REPLACING）：下次启动
            // 提示用户重导／回滚，快照仍在、可走磁盘回滚。
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw BackupException(
                if (rolledBack) backupString(R.string.backup_restore_failed_rolled_back, e.message ?: backupString(R.string.backup_unknown_error))
                else backupString(R.string.backup_restore_failed_rollback_failed, e.message ?: backupString(R.string.backup_unknown_error))
            )
        }
        return skippedAiMessages
    }

    // —— 应用级还原执行（不随界面销毁取消） ——

    /** 还原进度状态：由备份页观察，界面重建（旋转等）不影响执行本体。 */
    sealed interface RestoreState {
        data object Idle : RestoreState
        data object Running : RestoreState
        /** skippedAiMessages：还原时跳过的孤儿 AI 消息条数（L-33）。 */
        data class Done(val skippedAiMessages: Int = 0) : RestoreState
        data class Failed(val message: String) : RestoreState
    }

    private val restoreScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _restoreState =
        kotlinx.coroutines.flow.MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: kotlinx.coroutines.flow.StateFlow<RestoreState> = _restoreState

    /**
     * 中断的还原（启动对账检出）：备份页据此提示用户重导／回滚。
     * snapshotAvailable=false 时回滚入口禁用，只能重导备份文件。
     */
    data class InterruptedRestoreInfo(
        val token: String,
        val startedAt: Long,
        val completedSections: Set<String>,
        val snapshotAvailable: Boolean,
        val journalCorrupt: Boolean = false,
    )

    private val _interruptedRestore =
        kotlinx.coroutines.flow.MutableStateFlow<InterruptedRestoreInfo?>(null)
    val interruptedRestore: kotlinx.coroutines.flow.StateFlow<InterruptedRestoreInfo?> =
        _interruptedRestore

    /** 还原互斥：check-then-set 靠状态值判断不是原子操作，用 CAS 守门。 */
    private val restoreRunning =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 还原代次（D-M12）：每次 startRestore 自增。延迟杀进程只杀本代次
     * 的还原完成后的进程——600ms 内若用户又发起一次还原（代次已变），
     * 旧的延迟杀进程必须作废，否则会杀掉正在进行的新还原。
     */
    private val restoreGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 在应用级作用域启动还原：旋转/退出备份页都不会取消它（旧实现挂在
     * 界面作用域上，旋转即取消，还原会遭腰斩）。重复启动忽略。
     */
    fun startRestore(bytes: ByteArray, password: CharArray) {
        if (!restoreRunning.compareAndSet(false, true)) {
            password.fill(' ')
            return
        }
        val generation = restoreGeneration.incrementAndGet()
        _restoreState.value = RestoreState.Running
        restoreScope.launch {
            try {
                val skippedAiMessages = restoreBackup(bytes, password)
                _restoreState.value = RestoreState.Done(skippedAiMessages = skippedAiMessages)
                // 正常还原成功：旧的中断记录已随新 journal 被取代／清掉，
                // 提示随之消失。
                _interruptedRestore.value = null
                scheduleProcessRestart(generation)
            } catch (e: Exception) {
                Timber.w(e, "Restore failed")
                _restoreState.value =
                    RestoreState.Failed(e.message ?: backupString(R.string.backup_unknown_error))
                // 失败还原若留下了 REPLACING journal（回滚未成功），
                // 立即刷新中断提示，不必等下次启动才出现。
                reconcileInterruptedRestore()
            } finally {
                password.fill(' ')
                restoreRunning.set(false)
            }
        }
    }

    /**
     * 还原完成后由执行本体安排进程重启，让各处内存缓存整体失效。
     * 旧实现把杀进程挂在备份页的 Done 观察者上：用户中途离开页面
     * 就无人触发，旧缓存继续运行、还可能把旧数据回写覆盖已还原的
     * 结果。延迟 600ms 与旧行为一致（给完成提示留显示时间）。
     * 杀进程前核对代次与还原状态（D-M12）：代次已变（600ms 内又
     * 起一次还原）或正有还原在跑时不杀，双重守门。
     */
    private fun scheduleProcessRestart(generation: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (restoreGeneration.get() == generation && !restoreRunning.get()) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }, 600)
    }

    /** 备份页消费掉终态后复位，避免下次打开页面重复处理。 */
    fun consumeRestoreState() {
        if (_restoreState.value != RestoreState.Running) {
            _restoreState.value = RestoreState.Idle
        }
    }

    // ---------------- 中断还原对账（D-S2） ----------------

    /**
     * 启动对账（AppStartup.recoverInterruptedRestore 调用）：
     * STAGING／COMMITTED 自动清理（前者尚未写盘，后者已写完）；
     * REPLACING 留 journal，置 [interruptedRestore] 供备份页提示。
     * 启动从不自动回滚（回滚是用户发起的动作）。
     * 自身不抛错：journal 文件不存在即无记录；journal 损坏（文件
     * 在、解析失败）按疑似中断还原处理（D-L1）——快照全部保留、
     * 不清扫，备份页提示用户（回滚不可用，只能重导／忽略），不得
     * 按“无 journal”静默清扫损坏 journal 对应的还原前快照。
     */
    fun reconcileInterruptedRestore() {
        val dir = RestoreJournal.dir(appContext.filesDir)
        val entry = runCatching { RestoreJournal.read(dir) }.getOrNull()
        val journalCorrupt = entry == null && runCatching {
            RestoreJournal.isCorrupt(dir)
        }.getOrDefault(false)
        val action = RestoreJournal.decide(
            entry,
            entry?.let { RestoreJournal.snapshotExists(dir, it) } ?: false,
            journalCorrupt = journalCorrupt,
        )
        when (action) {
            RestoreJournal.RecoveryAction.NONE -> {
                RestoreJournal.sweepOrphanSnapshots(dir, null)
                _interruptedRestore.value = null
            }
            RestoreJournal.RecoveryAction.PROMPT_CORRUPT -> {
                // 损坏 journal 对应的还原前快照无法归属到任一 token，
                // 全部保留、不清扫；损坏文件本身留待下次成功还原覆盖
                // 或用户点“忽略”时删掉。下次启动仍走同一分支，直到
                // 用户处置为止——不静默、不丢失回滚能力。
                _interruptedRestore.value = InterruptedRestoreInfo(
                    token = "",
                    // 横幅按 startedAt 展示日期：损坏 journal 无可信
                    // 的 startedAt，取 journal 文件最后写盘时间。
                    startedAt = RestoreJournal.journalFile(dir).lastModified(),
                    completedSections = emptySet(),
                    snapshotAvailable = false,
                    journalCorrupt = true,
                )
                Timber.e(
                    "Restore journal is corrupt, keeping all snapshots " +
                        "and prompting user (rollback unavailable)",
                )
            }
            RestoreJournal.RecoveryAction.CLEANUP_COMPLETED,
            RestoreJournal.RecoveryAction.CLEANUP_STAGING -> {
                if (entry != null) RestoreJournal.clear(dir, entry)
                RestoreJournal.sweepOrphanSnapshots(dir, null)
                _interruptedRestore.value = null
                Timber.i("Interrupted restore cleaned up: phase=${entry?.phase}")
            }
            RestoreJournal.RecoveryAction.PROMPT_USER -> {
                if (entry != null) {
                    _interruptedRestore.value = InterruptedRestoreInfo(
                        token = entry.token,
                        startedAt = entry.startedAt,
                        completedSections = entry.completedSections,
                        snapshotAvailable = RestoreJournal.snapshotExists(dir, entry),
                    )
                    RestoreJournal.sweepOrphanSnapshots(dir, entry.token)
                    Timber.w(
                        "Interrupted restore detected: token=${entry.token} " +
                            "completed=${entry.completedSections}",
                    )
                }
            }
        }
    }

    /** 用户在备份页点“忽略”：删 journal＋快照，接受当前状态。 */
    fun dismissInterruptedRestore() {
        val dir = RestoreJournal.dir(appContext.filesDir)
        val entry = runCatching { RestoreJournal.read(dir) }.getOrNull()
        if (entry != null) {
            RestoreJournal.clear(dir, entry)
        } else {
            // D-L1：损坏 journal 读不出 entry，clear 无从删文件；用户
            // 已明确点“忽略”，此处删掉损坏的 journal 文件本身，否则
            // 下次启动仍走 PROMPT_CORRUPT、横幅永不消失。
            runCatching { RestoreJournal.journalFile(dir).delete() }
        }
        RestoreJournal.sweepOrphanSnapshots(dir, null)
        _interruptedRestore.value = null
    }

    /** 磁盘回滚结果（见 [startRollbackInterruptedRestore]）。 */
    sealed interface DiskRollbackResult {
        data object NoJournal : DiskRollbackResult
        data object SnapshotMissing : DiskRollbackResult
        data class RolledBack(val uncoveredSections: List<String>) : DiskRollbackResult
        data class Failed(val message: String) : DiskRollbackResult
    }

    /**
     * 从备份页发起磁盘回滚：读 journal＋快照，把还原前状态写回。
     * 与 [startRestore] 同一套应用级执行（restoreRunning 互斥、
     * restoreState 回报、成功后 600ms 杀进程重启）。
     * 回滚覆盖判定：快照里缺段、且 journal 显示该段已被失败还原
     * 写过（含皮肤库已开始未完成的半写段，D-P1）的，列入 uncovered——
     * 回滚成功但 uncovered 非空时按失败报（journal 保留，用户重导
     * 才是正解），不谎报“已恢复”。快照无皮肤段且还原前本就无皮肤时
     * 回滚清掉失败还原新增的孤儿皮肤文件，全覆盖、不列入。
     */
    fun startRollbackInterruptedRestore() {
        if (!restoreRunning.compareAndSet(false, true)) return
        val generation = restoreGeneration.incrementAndGet()
        _restoreState.value = RestoreState.Running
        restoreScope.launch {
            try {
                when (val result = rollbackInterruptedRestore()) {
                    is DiskRollbackResult.RolledBack -> {
                        if (result.uncoveredSections.isEmpty()) {
                            _interruptedRestore.value = null
                            _restoreState.value = RestoreState.Done()
                            scheduleProcessRestart(generation)
                        } else {
                            throw BackupException(
                                backupString(
                                    R.string.backup_rollback_partial,
                                    result.uncoveredSections.joinToString("、") {
                                        sectionDisplayName(it)
                                    },
                                ),
                            )
                        }
                    }
                    DiskRollbackResult.NoJournal -> throw BackupException(backupString(R.string.backup_no_interrupted_restore))
                    DiskRollbackResult.SnapshotMissing ->
                        throw BackupException(backupString(R.string.backup_snapshot_missing))
                    is DiskRollbackResult.Failed -> throw BackupException(result.message)
                }
            } catch (e: Exception) {
                Timber.w(e, "Disk rollback failed")
                _restoreState.value = RestoreState.Failed(e.message ?: backupString(R.string.backup_unknown_error))
            } finally {
                restoreRunning.set(false)
            }
        }
    }

    private suspend fun rollbackInterruptedRestore(): DiskRollbackResult {
        val dir = RestoreJournal.dir(appContext.filesDir)
        val entry = RestoreJournal.read(dir) ?: return DiskRollbackResult.NoJournal
        if (entry.phase != RestoreJournal.Phase.REPLACING) {
            // STAGING/COMMITTED 的 journal 不应走到这里（启动对账已清
            // 理）；撞上即清掉、按无记录处理。
            RestoreJournal.clear(dir, entry)
            return DiskRollbackResult.NoJournal
        }
        val snapshotFile = File(dir, entry.snapshotFile)
        if (entry.snapshotFile.isEmpty() || !snapshotFile.isFile) {
            return DiskRollbackResult.SnapshotMissing
        }
        val snapshotJson = try {
            JSONObject(snapshotFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return DiskRollbackResult.Failed(backupString(R.string.backup_snapshot_corrupt, e.message ?: backupString(R.string.backup_read_failed)))
        }
        val snapshotParsed = try {
            parsePayload(snapshotJson)
        } catch (e: Exception) {
            return DiskRollbackResult.Failed(backupString(R.string.backup_snapshot_parse_failed, e.message ?: backupString(R.string.backup_parse_failed)))
        }
        val uncovered = uncoveredSectionsForRollback(
            entry.completedSections,
            snapshotHasInputStats = snapshotParsed.inputStatsJson != null,
            inProgressSections = entry.inProgressSections,
        )
        // 失败还原新建的偏好文件：以当前 shared_prefs 目录为准（磁盘
        // 回滚没有内存里的 merged 目标可比）。
        val rollbackExtraPrefsFiles = currentPrefsFilesNotInSnapshot(snapshotParsed)
        // D-P1：clearSkins 与内存回滚取同一判定（快照无皮肤段且还原前
        // 本就无皮肤 → 快照即空库，清掉失败还原新增的孤儿皮肤文件）；
        // 还原前确有皮肤时绝不清——快照里没有它们可恢复，清了即丢数据。
        return try {
            withContext(NonCancellable) {
                applyPayload(
                    snapshotParsed,
                    forRollback = true,
                    rollbackExtraPrefsFiles = rollbackExtraPrefsFiles,
                )
            }
            // 全覆盖才清 journal；uncovered 非空时 journal 保留，
            // 提示继续存在，用户重导或忽略。
            if (uncovered.isEmpty()) RestoreJournal.clear(dir, entry)
            DiskRollbackResult.RolledBack(uncovered)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiskRollbackResult.Failed(backupString(R.string.backup_rollback_failed, e.message ?: backupString(R.string.backup_unknown_error)))
        }
    }

    /** 磁盘回滚用：当前 shared_prefs 里有、快照里没有、且非跳过文件的偏好文件（失败还原新建的）。 */
    private fun currentPrefsFilesNotInSnapshot(snapshotParsed: ParsedBackup): Set<String> {
        val prefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val live = prefsDir.listFiles { f -> f.extension == "xml" }
            ?.map { it.nameWithoutExtension }?.toSet().orEmpty()
        return (live - snapshotParsed.prefs.keys).filter { !isSkippedPrefsFile(it) }.toSet()
    }

    /**
     * 结算点重枚举（D-L2，纯函数）：shared_prefs 目录当前文件集减去采集
     * 开始时的文件集，即采集中途新建的 prefs 文件。新建文件不在守卫
     * 跟踪表里，[BackupChangeGuard.reverifyAll] 抓不到；调用方见非空
     * 即整包失败。只认 `.xml` 文件，与采集枚举的过滤口径一致。
     */
    internal fun newPrefsFilesSince(initialNames: Set<String>, prefsDir: File): Set<String> {
        val current = prefsDir.listFiles { f -> f.extension == "xml" }
            ?.map { it.nameWithoutExtension }?.toSet().orEmpty()
        return current - initialNames
    }

    /**
     * 磁盘回滚覆盖判定（纯函数）：快照里缺段、且失败还原写过该段的，列入 uncovered。
     * Room 两库与 prefs 在快照里恒有（collectPayload 必写），永不缺段；
     * 输入统计段是条件段，缺段且被写过即 uncovered。
     * “写过”含两类（D-P1）：journal 已记的完成段（completedSections），
     * 与已开始未完成的段（inProgressSections）。
     * 其余段的原子性（输入统计单文件 tmp＋rename）
     * 使“开始过”只可能来自未来才加的段开始标记，统一按写过计亦保守。
     */
    internal fun uncoveredSectionsForRollback(
        completedSections: Set<String>,
        snapshotHasInputStats: Boolean,
        inProgressSections: Set<String> = emptySet(),
    ): List<String> {
        val uncovered = ArrayList<String>()
        val touched = { section: String ->
            section in completedSections || section in inProgressSections
        }
        if (touched(RestoreJournal.SECTION_INPUT_STATS) && !snapshotHasInputStats) {
            uncovered += RestoreJournal.SECTION_INPUT_STATS
        }
        return uncovered
    }


    private fun sectionDisplayName(section: String): String = when (section) {
        RestoreJournal.SECTION_INPUT_STATS -> backupString(R.string.backup_section_input_stats)
        else -> section
    }

    private fun decryptPayload(bytes: ByteArray, password: CharArray): JSONObject {
        val nl = bytes.indexOf('\n'.code.toByte())
        if (nl <= 0) throw BackupException(backupString(R.string.backup_file_bad_format))
        val headerBytes = bytes.copyOfRange(0, nl)
        val header = try {
            JSONObject(String(headerBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException(backupString(R.string.backup_file_bad_format))
        }
        if (header.optString("format") != FORMAT) throw BackupException(backupString(R.string.backup_not_jime_file))
        if (header.optInt("version", -1) != FORMAT_VERSION) {
            throw BackupException(backupString(R.string.backup_version_unsupported))
        }
        val kdf = header.optString("kdf")
        if (kdf != KDF_SHA256 && kdf != KDF_SHA1) throw BackupException(backupString(R.string.backup_kdf_unsupported))
        // iterations 完全来自文件头，必须钳制：极大值会让 PBKDF2 长时间挂死
        val iterations = header.optInt("iterations", ITERATIONS)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BackupException(backupString(R.string.backup_kdf_unsupported))
        }
        val salt = header.optString("salt").fromBase64()
        val iv = header.optString("iv").fromBase64()
        if (salt == null || iv == null || salt.isEmpty() || iv.isEmpty()) {
            throw BackupException(backupString(R.string.backup_file_bad_format))
        }
        val key = deriveKey(password, salt, iterations, kdf)
        val cipherText = bytes.copyOfRange(nl + 1, bytes.size)
        // 只认带 AAD（头部作附加数据）的现行包：无 AAD 旧包兼容解密已
        // 随清点 D19 退场（997 版为一年前内部版本，存量旧包已清）。
        // GCM 校验失败：口令错误与文件篡改在加密上不可区分，统一提示
        val plain = decryptGcm(key, iv, cipherText, headerBytes)
            ?: throw BackupException(backupString(R.string.backup_password_or_corrupt))
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException(backupString(R.string.backup_content_parse_failed))
        }
    }

    private fun decryptGcm(
        key: SecretKeySpec,
        iv: ByteArray,
        cipherText: ByteArray,
        aad: ByteArray?,
    ): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        cipher.doFinal(cipherText)
    } catch (e: Exception) {
        null
    }

    /** 解析完成、等待写入的还原数据（解析阶段零写入） */
    private class ParsedBackup(
        val prefs: Map<String, Map<String, Any>>,
        val clipboard: List<ClipboardRecord>,
        /** 自定义分组（H2-4；旧备份无此段时为空列表，还原不碰现分组） */
        val clipboardGroups: List<ClipboardGroup>,
        val phrases: List<PhraseRecord>,
        val prefers: List<CandidatePrefer>,
        val sortings: List<CandidateSorting>,
        /** 输入统计文件内容（null = 旧备份无此字段，还原时不碰现文件） */
        val inputStatsJson: String?,
        /** AI 七表（null = 旧备份无此段，还原不碰现 AI 表；有段即整表替换，见 applyPayload） */
        val ai: AiBackupData?,
    )

    /**
     * 把解密后的 JSON 完整解析为内存对象。任何结构/类型错误抛
     * [BackupException]，此时尚未写入任何数据。
     */
    private fun parsePayload(root: JSONObject): ParsedBackup {
        try {
            val prefs = LinkedHashMap<String, Map<String, Any>>()
            val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
            val names = prefsJson.keys()
            while (names.hasNext()) {
                val name = names.next()
                // 还原时 name 直接成为 getSharedPreferences 的文件名
                // （shared_prefs/<name>.xml）：含路径分隔符的名字会穿越出
                // shared_prefs 目录写到任意位置，解析阶段先按白名单拒绝。
                // 本应用的 prefs 名均为字母/数字/下划线/点/连字符，兼容不受影响
                if (!isValidPrefsName(name)) {
                    throw BackupException(backupString(R.string.backup_illegal_prefs_name))
                }
                val entries = prefsJson.getJSONObject(name)
                val map = LinkedHashMap<String, Any>()
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = parsePrefValue(entries.getJSONObject(key))
                }
                prefs[name] = map
            }
            // WebDAV 整表不在解析段做任何过滤：写入段按设备本地表
            // 整表跳过（见 applyPayload 与 DEVICE_LOCAL_PREFS，S8）——
            // 原脱敏＋本机现值回填五层规则随之退场，旧备份里的
            // webdavPassword 专用字段也不再有人读（忽略即可）

            val clips = root.optJSONArray("clipboard") ?: JSONArray()
            val clipList = ArrayList<ClipboardRecord>(clips.length())
            for (i in 0 until clips.length()) {
                val o = clips.getJSONObject(i)
                // 软删行不写回：导出侧只导有效行，此为旧备份/异常包的兜底——
                // 已删除内容还原回库只会重新成为墓碑行，不应复活也不应占位
                if (o.optBoolean("deleted")) continue
                clipList += ClipboardRecord(
                    id = o.optLong("id", 0),
                    text = o.optString("text"),
                    timestamp = o.optLong("timestamp"),
                    cloud = o.optBoolean("cloud"),
                    deleted = o.optBoolean("deleted"),
                    deletedAt = o.optLong("deletedAt"),
                    // 旧备份无此字段，按未置顶还原
                    pinned = o.optBoolean("pinned"),
                    // 旧备份无此字段，按未分组还原
                    groupId = o.optLong("groupId"),
                    // 旧备份无此字段，按未收藏还原
                    isFavorite = o.optBoolean("isFavorite"),
                    // 旧备份无排序序号：用 -timestamp 派生，保持备份内的时间倒序
                    sortOrder = if (o.has("sortOrder")) o.optLong("sortOrder")
                    else -o.optLong("timestamp"),
                )
            }

            // 自定义分组（H2-4）：旧备份无此段时为空列表，还原不碰现分组
            val groupList = ArrayList<ClipboardGroup>()
            val groupsJson = root.optJSONArray("clipboard_groups") ?: JSONArray()
            for (i in 0 until groupsJson.length()) {
                val o = groupsJson.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                groupList += ClipboardGroup(
                    id = o.optLong("id", 0),
                    name = name.take(ClipboardManager.MAX_GROUP_NAME_LENGTH),
                    sortOrder = o.optLong("sortOrder"),
                    createdAt = o.optLong("createdAt"),
                )
            }

            val phrases = root.optJSONArray("phrases") ?: JSONArray()
            val phraseList = ArrayList<PhraseRecord>(phrases.length())
            for (i in 0 until phrases.length()) {
                val o = phrases.getJSONObject(i)
                phraseList += PhraseRecord(
                    id = o.optLong("id", 0),
                    text = o.optString("text"),
                    label = o.optString("label"),
                    createdAt = o.optLong("createdAt"),
                    sortOrder = if (o.has("sortOrder")) o.optLong("sortOrder")
                    else -o.optLong("createdAt"),
                    // 旧备份无此字段，按未置顶还原
                    pinned = o.optBoolean("pinned"),
                    // 旧备份无此字段，按空／0 还原
                    shortcut = o.optString("shortcut"),
                    category = o.optString("category"),
                    tags = o.optString("tags"),
                    variables = o.optString("variables"),
                    lastUsedAt = o.optLong("lastUsedAt"),
                )
            }

            val prefers = root.optJSONArray("candidatePrefers") ?: JSONArray()
            val preferList = ArrayList<CandidatePrefer>(prefers.length())
            for (i in 0 until prefers.length()) {
                val o = prefers.getJSONObject(i)
                preferList += CandidatePrefer(
                    text = o.optString("text"),
                    count = o.optInt("count", 1),
                    // 旧备份可能带有输入片段：还原时一律丢弃
                    context = "",
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                )
            }

            val sortings = root.optJSONArray("candidateSortings") ?: JSONArray()
            val sortingList = ArrayList<CandidateSorting>(sortings.length())
            for (i in 0 until sortings.length()) {
                val o = sortings.getJSONObject(i)
                val ids = o.optJSONArray("candidateIds") ?: JSONArray()
                sortingList += CandidateSorting(
                    key = o.optString("key"),
                    candidateIds = (0 until ids.length()).map { ids.optInt(it) },
                )
            }

            // AI 七表（WO52 9.1）：无 ai_providers 段 = 旧备份，还原
            // 不碰现 AI 表；有段即整表替换（空数组 = 备份时表就是空的）。
            // 服务商还原时强制 apiToken = ""（AiBackupCodec 规则：外来
            // 备份带 token 也丢弃，还原后由使用者重新自填）。
            val ai = if (!root.has("ai_providers")) {
                null
            } else {
                val providersJson = root.optJSONArray("ai_providers") ?: JSONArray()
                val providerList = ArrayList<AIProvider>(providersJson.length())
                for (i in 0 until providersJson.length()) {
                    val o = providersJson.getJSONObject(i)
                    providerList += AiBackupCodec.providerForRestore(
                        AIProvider(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            type = o.optString("type", "openai"),
                            sort = o.optInt("sort"),
                            isDefault = o.optBoolean("isDefault"),
                            isEnabled = o.optBoolean("isEnabled", true),
                            isBuiltIn = o.optBoolean("isBuiltIn"),
                            builtInCanBeDelete = o.optBoolean("builtInCanBeDelete", true),
                            baseURL = o.optString("baseURL"),
                            apiPath = o.optString("apiPath"),
                            apiToken = "",
                            requestTimeout = o.optDouble("requestTimeout", 60.0),
                            maxOutputTokens = o.optInt("maxOutputTokens"),
                            contextWindow = o.optInt("contextWindow"),
                            temperature = if (o.has("temperature")) o.optDouble("temperature") else null,
                            presencePenalty = if (o.has("presencePenalty")) o.optDouble("presencePenalty") else null,
                            frequencyPenalty = if (o.has("frequencyPenalty")) o.optDouble("frequencyPenalty") else null,
                            customParametersJSON = if (o.has("customParametersJSON")) o.optString("customParametersJSON") else null,
                            defaultModelId = if (o.has("defaultModelId")) o.optString("defaultModelId") else null,
                        ),
                    )
                }
                val modelsJson = root.optJSONArray("ai_provider_models") ?: JSONArray()
                val modelList = ArrayList<AIProviderModel>(modelsJson.length())
                for (i in 0 until modelsJson.length()) {
                    val o = modelsJson.getJSONObject(i)
                    modelList += AIProviderModel(
                        id = o.optString("id"),
                        providerId = if (o.has("providerId")) o.optString("providerId") else null,
                        displayName = o.optString("displayName"),
                        modelID = o.optString("modelID"),
                        sort = o.optInt("sort"),
                        isEnabled = o.optBoolean("isEnabled", true),
                    )
                }
                val sysJson = root.optJSONArray("ai_system_prompts") ?: JSONArray()
                val sysList = ArrayList<AISystemPrompt>(sysJson.length())
                for (i in 0 until sysJson.length()) {
                    val o = sysJson.getJSONObject(i)
                    sysList += AISystemPrompt(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        iconName = o.optString("iconName"),
                        purpose = o.optString("purpose"),
                        body = o.optString("body"),
                        generationOverridesJSON = if (o.has("generationOverridesJSON")) o.optString("generationOverridesJSON") else null,
                        sort = o.optInt("sort"),
                        isDefault = o.optBoolean("isDefault"),
                        isEnabled = o.optBoolean("isEnabled", true),
                        isBuiltIn = o.optBoolean("isBuiltIn"),
                        builtInCanBeDelete = o.optBoolean("builtInCanBeDelete", true),
                    )
                }
                val userJson = root.optJSONArray("ai_user_prompts") ?: JSONArray()
                val userList = ArrayList<AIUserPrompt>(userJson.length())
                for (i in 0 until userJson.length()) {
                    val o = userJson.getJSONObject(i)
                    userList += AIUserPrompt(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        iconName = o.optString("iconName"),
                        purpose = o.optString("purpose"),
                        templateBody = o.optString("templateBody", "{{input}}"),
                        sort = o.optInt("sort"),
                        isDefault = o.optBoolean("isDefault"),
                        isEnabled = o.optBoolean("isEnabled", true),
                        isBuiltIn = o.optBoolean("isBuiltIn"),
                        builtInCanBeDelete = o.optBoolean("builtInCanBeDelete", true),
                    )
                }
                val insJson = root.optJSONArray("ai_instructions") ?: JSONArray()
                val insList = ArrayList<AIInstruction>(insJson.length())
                for (i in 0 until insJson.length()) {
                    val o = insJson.getJSONObject(i)
                    insList += AIInstruction(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        detail = o.optString("detail"),
                        systemPromptID = if (o.has("systemPromptID")) o.optString("systemPromptID") else null,
                        userPromptID = if (o.has("userPromptID")) o.optString("userPromptID") else null,
                        providerID = if (o.has("providerID")) o.optString("providerID") else null,
                        sort = o.optInt("sort"),
                    )
                }
                val convJson = root.optJSONArray("ai_conversations") ?: JSONArray()
                val convList = ArrayList<AIConversation>(convJson.length())
                for (i in 0 until convJson.length()) {
                    val o = convJson.getJSONObject(i)
                    convList += AIConversation(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        createdAt = o.optLong("createdAt"),
                        updatedAt = o.optLong("updatedAt"),
                        isTitleUserEdited = o.optBoolean("isTitleUserEdited"),
                        systemPromptID = if (o.has("systemPromptID")) o.optString("systemPromptID") else null,
                        providerID = if (o.has("providerID")) o.optString("providerID") else null,
                        modelID = if (o.has("modelID")) o.optString("modelID") else null,
                        snapshotJSON = if (o.has("snapshotJSON")) o.optString("snapshotJSON") else null,
                    )
                }
                val msgJson = root.optJSONArray("ai_messages") ?: JSONArray()
                val msgList = ArrayList<AIMessage>(msgJson.length())
                for (i in 0 until msgJson.length()) {
                    val o = msgJson.getJSONObject(i)
                    msgList += AIMessage(
                        id = o.optString("id"),
                        conversationId = if (o.has("conversationId")) o.optString("conversationId") else null,
                        role = o.optString("role"),
                        status = o.optString("status"),
                        sequence = o.optInt("sequence"),
                        createdAt = o.optLong("createdAt"),
                        originalInput = if (o.has("originalInput")) o.optString("originalInput") else null,
                        renderedRequest = if (o.has("renderedRequest")) o.optString("renderedRequest") else null,
                        userPromptName = if (o.has("userPromptName")) o.optString("userPromptName") else null,
                        content = o.optString("content"),
                        tokenEstimate = o.optInt("tokenEstimate"),
                        usageJSON = if (o.has("usageJSON")) o.optString("usageJSON") else null,
                        errorCategory = if (o.has("errorCategory")) o.optString("errorCategory") else null,
                        requestID = if (o.has("requestID")) o.optString("requestID") else null,
                        regenerationOfID = if (o.has("regenerationOfID")) o.optString("regenerationOfID") else null,
                        isSuperseded = o.optBoolean("isSuperseded"),
                        configSnapshotJSON = if (o.has("configSnapshotJSON")) o.optString("configSnapshotJSON") else null,
                    )
                }
                AiBackupData(
                    providers = providerList,
                    models = modelList,
                    systemPrompts = sysList,
                    userPrompts = userList,
                    instructions = insList,
                    conversations = convList,
                    messages = msgList,
                )
            }

            // 包内清单校验（D-M1，payload v2）：清单在场时逐项核对
            // 计数与存在性，任一不符整包判失败（解析阶段拦截，不写
            // 脏数据）；旧备份无清单字段时跳过（兼容 v1）。注意计数
            // 以载荷原始数组长度为准（剪贴板去重前——清单记的是采集
            // 时的原始行数）。
            if (root.has("manifest")) {
                val manifestJson = try {
                    root.getJSONObject("manifest")
                } catch (e: Exception) {
                    throw BackupException(backupString(R.string.backup_manifest_corrupt))
                }
                val manifest = BackupManifest.fromJsonObject(manifestJson)
                    ?: throw BackupException(backupString(R.string.backup_manifest_corrupt))
                if (!isSupportedPayloadVersion(manifest.payloadVersion)) {
                    throw BackupException(backupString(R.string.backup_version_unsupported))
                }
                val mismatches = manifest.validate(countsFromPayload(root))
                if (mismatches.isNotEmpty()) {
                    Timber.w("backup manifest mismatch: ${mismatches.joinToString("; ")}")
                    throw BackupException(backupString(R.string.backup_manifest_mismatch))
                }
            }

            return ParsedBackup(
                prefs = prefs,
                // 剪贴板表 text 唯一（ClipboardDatabase v4 起）：备份内
                // 同 text 多行（旧版本应用层唯一性约定失效时期的产物）
                // 直插会撞唯一索引、整表事务回滚让还原全灭。解析阶段
                // 先按统一口径去重（pinned 优先、timestamp 最新、
                // id 最大，与库内迁移去重一致）
                clipboard = dedupeClipboardByText(clipList),
                clipboardGroups = groupList,
                phrases = phraseList,
                prefers = preferList,
                sortings = sortingList,
                // 输入统计文件：有字段时必须是合法 JSON 对象，否则整包判失败
                // （解析阶段拦截，不写脏文件；无字段 = 旧备份，不碰现文件）
                inputStatsJson = if (!root.has("input_stats_json")) {
                    null
                } else {
                    val raw = root.optString("input_stats_json")
                    try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        throw BackupException(backupString(R.string.backup_input_stats_not_json))
                    }
                    raw
                },
                ai = ai,
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(backupString(R.string.backup_content_parse_failed))
        }
    }

    /**
     * 从解密后的载荷 JSON 读实际计数（清单校验用；纯 JSONObject
     * 长度统计，与采集侧 [BackupManifest] 的计数口径逐项对应：
     * prefs 条目数 = 各表对象长度之和，行数 = 原始数组长度）。
     */
    private fun countsFromPayload(root: JSONObject): Map<String, Any> {
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        var prefsEntries = 0
        val prefsNames = prefsJson.keys()
        while (prefsNames.hasNext()) {
            prefsEntries += prefsJson.optJSONObject(prefsNames.next())?.length() ?: 0
        }
        return manifestCounts(
            prefsFiles = prefsJson.length(),
            prefsEntries = prefsEntries,
            clipboardCount = root.optJSONArray("clipboard")?.length() ?: 0,
            clipboardGroupsCount = root.optJSONArray("clipboard_groups")?.length() ?: 0,
            phrasesCount = root.optJSONArray("phrases")?.length() ?: 0,
            prefersCount = root.optJSONArray("candidatePrefers")?.length() ?: 0,
            sortingsCount = root.optJSONArray("candidateSortings")?.length() ?: 0,
            aiProvidersCount = root.optJSONArray("ai_providers")?.length() ?: 0,
            aiInstructionsCount = root.optJSONArray("ai_instructions")?.length() ?: 0,
            aiConversationsCount = root.optJSONArray("ai_conversations")?.length() ?: 0,
            aiMessagesCount = root.optJSONArray("ai_messages")?.length() ?: 0,
            inputStatsPresent = root.has("input_stats_json"),
        )
    }

    // prefs 名白名单：字母/数字/点/下划线/连字符，且不许是 "." / ".."
    // 这类纯相对路径段（点本身在字符白名单内，必须单独排除）
    private val PREFS_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

    private fun isValidPrefsName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            PREFS_NAME_PATTERN.matches(name)


    /** 严格解析单个 prefs 值：未知类型标签或类型不符直接抛错（解析阶段拦截） */
    private fun parsePrefValue(obj: JSONObject): Any = when (obj.optString("t")) {
        "s" -> obj.getString("v")
        "i" -> obj.getInt("v")
        "l" -> obj.getLong("v")
        "f" -> obj.getDouble("v").toFloat()
        "b" -> obj.getBoolean("v")
        "ss" -> {
            val arr = obj.getJSONArray("v")
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
        else -> throw BackupException(backupString(R.string.backup_content_parse_failed))
    }

    /**
     * 判定偏好条目是否属于旧多目录状态残留，命中则不还原。
     * 只有 prefsName 为 scheme_dirs 时为 true——该文件整表已由
     * [isSkippedPrefsFile] 在创建 editor 之前跳过，此处是第二道 guard。
     * 注意：schema_settings 里的分目录键 enabled_schema_ids_<目录>
     * 不是残留，而是现行模型的一部分（SchemaManager.saveEnabledIds 双写
     * 分目录键＋全局镜像键；重置与迁移路径读写初始目录的分目录键），必须
     * 写回。旧实现把分目录键当残留跳过，又因还原先 editor.clear()，
     * 导致已迁移设备还原后各目录自定义顺序丢失、回落目录方案表顺序，
     * 且全局镜像键兜不了底：各目录顺序只存在各自的分目录键里，而
     * scheme_dirs 整表不还原、目标机 current_dir 未必等于备份时目录，
     * 镜像又只含备份时当前目录一家的顺序。
     * （表名取备份载荷里的字面名，不跟随代码常量改名：旧备份里的
     * scheme_dirs 表无论现常量为何值，都必须跳过。）
     */
    internal fun isLegacyMultiDirPref(prefsName: String, key: String): Boolean {
        return prefsName == "scheme_dirs"
    }

    /**
     * 整表跳过回写的文件：设备本地表（见 DEVICE_LOCAL_PREFS）与
     * 现行多目录状态文件 scheme_dirs。调用方必须在循环开头、
     * 创建 editor 之前判断——一旦 editor.clear() 执行，跳过已无意义。
     * （字面名 scheme_dirs，不跟随常量改名，与 isLegacyMultiDirPref 同口径。）
     */
    internal fun isSkippedPrefsFile(name: String): Boolean =
        name in DEVICE_LOCAL_PREFS || name == "scheme_dirs"

    /**
     * 还原合并语义（D31）：四表“当前优先、备份补缺”取并集。冲突键
     * 以当前行为准（备份后新学／新改的内容不被备份时点的旧行覆盖）；
     * 只在备份里有的行补进来（备份后删掉的内容还原时回来）。prefs、
     * 输入统计不在此列：prefs 整表覆盖是设置还原的本意，统计只是计数。
     * 分组（H2-4）同口径按名称合并；补入的剪贴板行按分组重映射
     * 改 groupId，当前行 groupId 不动。
     */
    private fun mergeForRestore(current: ParsedBackup, backup: ParsedBackup): ParsedBackup {
        val (mergedGroups, groupRemap) =
            mergeGroupsForRestore(current.clipboardGroups, backup.clipboardGroups)
        val currentTexts = current.clipboard.mapTo(HashSet()) { it.text }
        return ParsedBackup(
            prefs = backup.prefs,
            clipboard = remapBackupRecordGroups(
                mergeClipboardForRestore(current.clipboard, backup.clipboard),
                currentTexts,
                groupRemap,
            ),
            clipboardGroups = mergedGroups,
            phrases = mergePhrasesForRestore(current.phrases, backup.phrases),
            prefers = mergePrefersForRestore(current.prefers, backup.prefers),
            sortings = mergeSortingsForRestore(current.sortings, backup.sortings),
            inputStatsJson = backup.inputStatsJson,
            // AI 七表是整表替换口径（备份原样透传；缺段时
            // 为 null，下游 applyPayload 不碰现表）
            ai = backup.ai,
        )
    }

    /**
     * 分组合并（H2-4）：名称是合并键，当前组优先；备份独有的组补进来，
     * id 重排（自增 id 是本机代次，跨设备直插会撞主键——与剪贴板行同理）。
     * @return 合并后的分组表＋备份组 id 到合并后 id 的重映射（id 不变的组不在映射里）。
     */
    internal fun mergeGroupsForRestore(
        current: List<ClipboardGroup>,
        backup: List<ClipboardGroup>,
    ): Pair<List<ClipboardGroup>, Map<Long, Long>> {
        if (backup.isEmpty()) return current to emptyMap()
        val out = current.toMutableList()
        val remap = HashMap<Long, Long>()
        var nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
        for (g in backup) {
            val existing = out.firstOrNull { it.name == g.name }
            if (existing != null) {
                // 同名：当前组优先，备份 id 映射到当前组 id
                if (g.id != existing.id) remap[g.id] = existing.id
            } else {
                val newId = nextId++
                out += g.copy(id = newId)
                if (g.id != newId) remap[g.id] = newId
            }
        }
        return out to remap
    }

    /**
     * 补入的剪贴板行按分组重映射（H2-4）：只有备份来源的行（text 不在
     * 当前表里）才改 groupId；当前行 groupId 不动。映射不到的组
     * （备份组在合并表里不存在）回落未分组。
     */
    internal fun remapBackupRecordGroups(
        records: List<ClipboardRecord>,
        currentTexts: Set<String>,
        groupRemap: Map<Long, Long>,
    ): List<ClipboardRecord> {
        if (groupRemap.isEmpty()) return records
        return records.map { r ->
            if (r.text in currentTexts || r.groupId == 0L) r
            else r.copy(groupId = groupRemap[r.groupId] ?: 0L)
        }
    }

    /**
     * 剪贴板合并：text 是唯一键（v4 起唯一索引），当前行优先；备份
     * 独有的行补进来，id 清零重排——自增 id 是本机代次，跨设备还原
     * 时备份 id 可能撞当前行 id，直插会触发主键冲突、整事务回滚。
     */
    internal fun mergeClipboardForRestore(
        current: List<ClipboardRecord>,
        backup: List<ClipboardRecord>,
    ): List<ClipboardRecord> {
        if (backup.isEmpty()) return current
        val seen = current.mapTo(HashSet(current.size + backup.size)) { it.text }
        val out = current.toMutableList()
        for (r in backup) {
            if (seen.add(r.text)) out += r.copy(id = 0)
        }
        return out
    }

    /**
     * 常用语合并：text 上无唯一约束，同文不同 label 是合法的两条，
     * 故合并键取（text, label）；当前行优先，备份独有行补进来，
     * id 清零重排（与剪贴板同理，防跨设备 id 碰撞）。
     */
    internal fun mergePhrasesForRestore(
        current: List<PhraseRecord>,
        backup: List<PhraseRecord>,
    ): List<PhraseRecord> {
        if (backup.isEmpty()) return current
        val seen = current.mapTo(HashSet(current.size + backup.size)) { it.text to it.label }
        val out = current.toMutableList()
        for (r in backup) {
            if (seen.add(r.text to r.label)) out += r.copy(id = 0)
        }
        return out
    }

    /** 选词偏好合并：text 是主键，当前行优先，备份独有行补进来。 */
    internal fun mergePrefersForRestore(
        current: List<CandidatePrefer>,
        backup: List<CandidatePrefer>,
    ): List<CandidatePrefer> {
        if (backup.isEmpty()) return current
        val seen = current.mapTo(HashSet(current.size + backup.size)) { it.text }
        val out = current.toMutableList()
        for (r in backup) {
            if (seen.add(r.text)) out += r
        }
        return out
    }

    /** 候选排序合并：key 是主键，当前行优先，备份独有行补进来。 */
    internal fun mergeSortingsForRestore(
        current: List<CandidateSorting>,
        backup: List<CandidateSorting>,
    ): List<CandidateSorting> {
        if (backup.isEmpty()) return current
        val seen = current.mapTo(HashSet(current.size + backup.size)) { it.key }
        val out = current.toMutableList()
        for (r in backup) {
            if (seen.add(r.key)) out += r
        }
        return out
    }

    /**
     * 写入段：输入已在解析阶段全量校验，此处只做落盘，不再有解析错误。
     * @param forRollback 回滚回放：快照即还原前状态，分组恒整表替换、
     *   失败还原新建的偏好文件删掉；正常还原走合并写回口径，不碰快照里没有的现数据。
     */
    /**
     * 把解析后的备份写回各存储。失败时由调用方用快照回放整体回滚。
     * [onSectionDone]：每写完一段（剪贴板库／主库／prefs／输入统计）
     * 回调一次，供还原 journal 记段进度；条件段
     * （输入统计）只在真实写盘后才回调，跳过的段
     * 不记（见 [uncoveredSectionsForRollback] 的 KDoc）。回滚回放
     * 不传回调（journal 正在被处置，不记进度）。
     */
    /**
     * 将解析后的备份内容写回各存储（主调）。返回还原时跳过的孤儿
     * AI 消息条数（L-33；其余段无跳过口径）。
     */
    private suspend fun applyPayload(
        parsed: ParsedBackup,
        forRollback: Boolean = false,
        rollbackExtraPrefsFiles: Set<String> = emptySet(),
        onSectionDone: ((String) -> Unit)? = null,
    ): Int {
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)
        // 跳过的孤儿 AI 消息条数（L-33；AI 段有段时才写，其余为 0）。
        var skippedAiMessages = 0
        // 拆库迁移本体已随清点 Q2 退场（现行设备早已迁完）：原「迁移
        // 不成中止还原」的耦合随之解除，直接整表替换

        // —— Room 五表：各自单事务内整表替换，中途失败整表回滚
        clipDb.withTransaction {
            clipDb.clipboardDao().deleteAllRaw()
            if (parsed.clipboard.isNotEmpty()) {
                // 还原入口同样限长：旧备份里的超限文本不能把毒化行带回库里。
                // 截断会新造重复（两条长文前 MAX_TEXT_LENGTH 字相同），
                // 解析阶段的去重防不住这一格，插入前按同一口径再去重
                // 一次，避免撞 text 唯一索引整表回滚
                val clipped = dedupeClipboardByText(
                    parsed.clipboard.map { r ->
                        if (r.text.length > ClipboardManager.MAX_TEXT_LENGTH) {
                            r.copy(text = r.text.take(ClipboardManager.MAX_TEXT_LENGTH))
                        } else r
                    }
                )
                clipDb.clipboardDao().insertAll(clipped)
            }
            // 自定义分组（H2-4）：与剪贴板行同一事务整表替换；
            // 旧备份无此段时 parsed.clipboardGroups 为空列表，还原
            // 不碰现分组（见 parsePayload）；groupId 已在合并阶段
            // 按合并后的分组表重映射（见 mergeForRestore）。回滚
            // 模式下恒替换：快照里的分组表就是还原前状态，空表即
            // “还原前无分组”，必须把失败还原新建的分组清掉，而
            // 不是只在非空时才替换（D-M2 的分组残留）。
            if (forRollback || parsed.clipboardGroups.isNotEmpty()) {
                clipDb.clipboardDao().deleteAllGroups()
                clipDb.clipboardDao().insertGroups(parsed.clipboardGroups)
            }
        }
        onSectionDone?.invoke(RestoreJournal.SECTION_CLIPBOARD)
        db.withTransaction {
            db.phraseDao().deleteAll()
            if (parsed.phrases.isNotEmpty()) db.phraseDao().insertAll(parsed.phrases)
            db.candidatePreferDao().deleteAll()
            if (parsed.prefers.isNotEmpty()) db.candidatePreferDao().insertAll(parsed.prefers)
            db.candidateSortingDao().deleteAll()
            if (parsed.sortings.isNotEmpty()) db.candidateSortingDao().insertAll(parsed.sortings)
            // —— AI 七表（WO52 9.1）：有段即整表替换（与导出段对应；
            // 旧备份无段时 parsed.ai 为 null，不碰现 AI 表）。同一事务
            // 内先清后写；provider↔model 外键为 DEFERRABLE，提交时才
            // 校验，清／写顺序不限。服务商 token 在解析段已强制清空，
            // 还原无 token 时保留本机 token（L-31：导出文件永不含
            // token，实际效果恒为保留本机；回滚快照经内存补丁带真
            // token 时同样不被空 token 盖回）。
            parsed.ai?.let { ai ->
                val localTokens = db.aiProviderDao().getAll()
                    .associate { it.id to it.apiToken }
                db.aiProviderModelDao().deleteAll()
                db.aiProviderDao().deleteAll()
                db.aiSystemPromptDao().deleteAll()
                db.aiUserPromptDao().deleteAll()
                db.aiInstructionDao().deleteAll()
                db.aiMessageDao().deleteAll()
                db.aiConversationDao().deleteAll()
                if (ai.providers.isNotEmpty()) {
                    db.aiProviderDao().insertAll(
                        ai.providers.map { provider ->
                            provider.copy(
                                apiToken = provider.apiToken
                                    .ifEmpty { localTokens[provider.id] ?: "" },
                            )
                        },
                    )
                }
                if (ai.models.isNotEmpty()) db.aiProviderModelDao().insertAll(ai.models)
                if (ai.systemPrompts.isNotEmpty()) db.aiSystemPromptDao().insertAll(ai.systemPrompts)
                if (ai.userPrompts.isNotEmpty()) db.aiUserPromptDao().insertAll(ai.userPrompts)
                if (ai.instructions.isNotEmpty()) db.aiInstructionDao().insertAll(ai.instructions)
                if (ai.conversations.isNotEmpty()) db.aiConversationDao().insertAll(ai.conversations)
                // 孤儿消息（L-33）：conversationId 不在恢复会话集内
                // 的跳过（null conversationId 是合法值，FK 不约束
                // NULL，保留）；跳过数由调用方提示用户。
                val (keptMessages, skippedMessages) = filterOrphanMessages(
                    ai.messages,
                    ai.conversations.mapTo(HashSet()) { it.id },
                )
                skippedAiMessages = skippedMessages
                if (keptMessages.isNotEmpty()) db.aiMessageDao().insertAll(keptMessages)
            }
        }
        onSectionDone?.invoke(RestoreJournal.SECTION_APPDB)

        // —— prefs 整表覆盖（先清后写，与备份时点的一致状态）
        for ((name, entries) in parsed.prefs) {
            // 整表跳过的文件根本不碰：循环开头 continue，绝不走到
            // 下面的 editor.clear()。scheme_dirs 是现行多目录状态
            // （current_dir / migrated_to_dirs），不是旧残留——先
            // clear 再逐条跳过会把它清空，且回滚回放走同一路径
            // 同样救不回来；与 DEVICE_LOCAL_PREFS 的“整表跳过
            // 不碰”口径一致。
            if (isSkippedPrefsFile(name)) continue
            val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            for ((key, value) in entries) {
                // 旧多目录状态文件条目不还原（见 isLegacyMultiDirPref；
                // scheme_dirs 整表已在循环开头跳过，此处是第二道 guard）。
                // schema_settings 的分目录键是现行模型，必须写回，
                // 不再按旧口径跳过。
                if (isLegacyMultiDirPref(name, key)) continue
                putPrefValue(editor, key, value)
            }
            // prefs 回写必须同步落盘：随后可能马上重启进程，异步 apply 有丢失窗口；
            // 同步提交失败必须让整个还原判失败（触发快照回滚），只记日志继续
            // 会留下数据库是新数据、偏好是旧数据的混杂状态
            if (!editor.commit()) throw BackupException(backupString(R.string.backup_prefs_write_failed, name))
        }
        // 回滚模式：删掉失败还原新建的偏好文件（D-M2 的偏好残留）。
        // deleteSharedPreferences 同时清内存缓存实例（API 24+，minSdk 24）；
        // 残留的 .bak 一并清掉。
        if (forRollback) {
            val prefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
            for (name in rollbackExtraPrefsFiles) {
                appContext.deleteSharedPreferences(name)
                File(prefsDir, "$name.xml.bak").delete()
            }
        }
        onSectionDone?.invoke(RestoreJournal.SECTION_PREFS)

        // 引擎侧候选偏好有内存快照，还原后显式失效，不依赖随后的杀进程重启
        CandidatePreferCache.invalidate()

        // —— 输入统计文件：写回后还原流程末尾重启进程，InputStats
        // 下次启动读回新文件；无字段（旧备份）时不碰现文件。
        // 临时文件＋改名：中途失败不留半截 stats.json（启动侧损坏
        // 本就容错为空，但能不脏就不脏）
        parsed.inputStatsJson?.let { json ->
            try {
                AtomicFiles.write(
                    File(File(appContext.filesDir, "input_stats"), "stats.json"),
                    json.toByteArray(Charsets.UTF_8),
                )
            } catch (e: java.io.IOException) {
                throw BackupException(backupString(R.string.backup_input_stats_write_failed))
            }
            onSectionDone?.invoke(RestoreJournal.SECTION_INPUT_STATS)
        }

        return skippedAiMessages
    }

    private fun putPrefValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Any,
    ) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
        }
    }

    // ---------------- 加密原语 ----------------

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        kdf: String,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(kdf)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray? = try {
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

/** 备份文件的硬上限：备份只含设置与小型数据库，超过 64MiB 视为异常文件 */
internal const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

/**
 * 带硬上限的流式读取：累计字节一旦超过 [maxBytes] 立即抛错中止，
 * 不会把超大（或恶意）文件先全量读入内存、等读完才被大小检查拦下。
 */
internal fun readBackupBytes(
    input: java.io.InputStream,
    maxBytes: Int = MAX_BACKUP_BYTES,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw BackupManager.BackupException("备份文件过大")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

/**
 * 孤儿 AI 消息过滤（L-33，纯函数）：conversationId 不在恢复会话
 * id 集内的消息跳过（外键约束会整表回滚）；null conversationId
 * 是合法值（FK 不约束 NULL），保留。返回（保留的消息，跳过条数）。
 */
internal fun filterOrphanMessages(
    messages: List<com.jobeen.ime.data.database.AIMessage>,
    conversationIds: Set<String>,
): Pair<List<com.jobeen.ime.data.database.AIMessage>, Int> {
    val kept = ArrayList<com.jobeen.ime.data.database.AIMessage>(messages.size)
    var skipped = 0
    for (message in messages) {
        val conversationId = message.conversationId
        if (conversationId != null && conversationId !in conversationIds) {
            skipped++
        } else {
            kept += message
        }
    }
    return kept to skipped
}
