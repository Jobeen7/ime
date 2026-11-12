package com.jobeen.ime.data.backup

import android.content.Context
import android.os.Build
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.data.App
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
import com.jobeen.ime.skin.script.SkinScriptIds
import com.jobeen.ime.skin.script.SkinScriptStore
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
import java.security.MessageDigest
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
 * 原始字节超 32MB 整段跳过只记日志（不阻塞整包）；旧备份无此段
 * 时还原不碰现皮肤。写回后重启进程，下次启动对账收敛索引。
 *
 * 皮肤脚本文件（filesDir/skin_scripts/<id>/script.js + meta.json）
 * 随包走、整表替换写回（与皮肤库合并写回不同，见下）：脚本是
 * 用户数据而非库代次，备份即该时点的全部脚本，还原即回到该
 * 时点——段内文件全量写回、段外现文件删除；旧备份无此段时
 * 还原不碰现脚本；新备份即使无脚本也带空段（files 为空），
 * 使还原失败时的快照回放总能把脚本恢复到还原前状态（含还原
 * 前无脚本时删干净，而非留下失败还原中途新增的孤儿脚本）。
 * 采集只认 <id>/script.js 与 <id>/meta.json（半写 .tmp 与其它
 * 文件跳过不进包）；相对路径逐项白名单校验（id 须过脚本 id
 * 口径），原始字节超 32MB 整段跳过只记日志（不阻塞整包，
 * 此时该包还原不碰脚本）。
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
     * 采集中途数据变了：逐文件改动检测（[BackupChangeGuard]）或
     * Room 快照读到一半失败时抛。消息直接面向用户（备份页 Toast
     * 原样显示），告诉用户重做一次即可。
     */
    class BackupDataChangedException(operation: String) :
        BackupException("${operation}中数据变了，请重试")

    // ---------------- 备份 ----------------

    /** 采集当前数据并加密，返回完整备份文件字节（头部行 + 密文） */
    suspend fun createBackup(password: CharArray): ByteArray =
        encryptPayload(collectPayload("备份"), password)

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

    private suspend fun collectPayload(operation: String = "备份"): JSONObject {
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
            val entries = guard.guard(file, operation) {
                val sp = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                val entries = JSONObject()
                for ((key, value) in sp.all) {
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
        // 先开库（迁移已跑完）再裸读：裸读旧版库文件会因缺列失败。
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)
        // 拆库迁移本体已随清点 Q2 退场（现行设备早已迁完），采集直接
        // 读新库；原「迁移不成中止备份」的耦合随之解除

        // 遗留超限行先清理（D-S5）：限长前的超限行读出即抛
        // SQLiteBlobTooBigException，备份导出会被一行毒化整包。
        ClipboardManager.sanitizeOversizedOnce(appContext)

        val appDbFile = db.openHelper.writableDatabase.path?.let { File(it) }
            ?: throw BackupException("备份读取数据库失败：无法定位主库文件")
        val clipDbFile = clipDb.openHelper.writableDatabase.path?.let { File(it) }
            ?: throw BackupException("备份读取数据库失败：无法定位剪贴板库文件")
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
                    .put("groupId", r.groupId)
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

        // —— 皮肤库文件：index.json + skins/ + assets/（合并写回，见 KDoc）；
        // 空库或超限时 collectSkinLibraryJson 返回 null，即不写该段。
        collectSkinLibraryJson(guard, operation)?.let { root.put("skin_library", it) }

        // —— 皮肤脚本文件：filesDir/skin_scripts（整表替换写回，见 KDoc）；
        // 超限时 collectSkinScriptsJson 返回 null，即不写该段；无脚本
        // 时带空段，使还原失败回放能恢复到“还原前无脚本”状态。
        collectSkinScriptsJson(guard, operation)?.let { root.put("skin_scripts", it) }

        // —— 包内清单（D-M1，fcitx 清单 v2 形状）：各源计数／存在标记，
        // 恢复前 [parsePayload] 逐项核对；旧备份无此字段，解析时跳过
        // 校验直接兼容（外层 FORMAT_VERSION 仍为 1，见类 KDoc）。
        val manifest = BackupManifest(
            payloadVersion = BackupManifest.CURRENT_PAYLOAD_VERSION,
            counts = ManifestCounts(
                prefsFiles = prefsJson.length(),
                prefsEntries = prefsEntries,
                clipboardCount = clipRows.size,
                clipboardGroupsCount = groupRows.size,
                phrasesCount = phraseRows.size,
                prefersCount = preferRows.size,
                sortingsCount = sortingRows.size,
                inputStatsPresent = root.has("input_stats_json"),
                skinLibraryPresent = root.has("skin_library"),
                skinLibraryFiles = root.optJSONObject("skin_library")
                    ?.optJSONObject("files")?.length() ?: 0,
                skinScriptsPresent = root.has("skin_scripts"),
                skinScriptsFiles = root.optJSONObject("skin_scripts")
                    ?.optJSONObject("files")?.length() ?: 0,
            ),
        )
        root.put("manifest", manifest.toJsonObject())
        return root
    }

    /** 皮肤库备份原始字节上限：超限整段跳过只记日志（仿 input_stats 跳过模式）。 */
    private const val SKIN_BACKUP_MAX_RAW_BYTES = 32L * 1024 * 1024

    /**
     * 皮肤库段采集：index.json（文本，读不到则 null）+ skins/ 与 assets/
     * 下全部文件（相对路径 → Base64）。只认 App.skinLibraryDir 这一唯一
     * 目录来源；相对路径须过 canonical 校验（防符号链接逃逸）；空库
     * （无 index 且无文件）返回 null，即不写该段；原始字节总量超
     * 32MB 整段跳过只记日志（不阻塞整包）。
     */
    private fun collectSkinLibraryJson(
        guard: BackupChangeGuard,
        operation: String,
    ): JSONObject? {
        val rootDir = App.skinLibraryDir
        val indexFile = File(rootDir, "index.json")
        val indexText = if (indexFile.isFile) {
            // 逐文件改动检测（D-M1）：读前取元数据，读后重验，变了即
            // 整个备份失败、不产残包（见 BackupChangeGuard）。
            guard.guard(indexFile, operation) {
                if (!indexFile.isFile) throw BackupDataChangedException(operation)
                runCatching { indexFile.readText(Charsets.UTF_8) }.onFailure {
                    Timber.w("skin index unreadable, skipped in backup")
                }.getOrNull()
            }
        } else {
            null
        }
        val canonicalRoot = runCatching { rootDir.canonicalPath }.getOrNull() ?: return null
        val filesJson = JSONObject()
        var totalBytes = 0L
        for (sub in listOf("skins", "assets")) {
            val subDir = File(rootDir, sub)
            if (!subDir.isDirectory) continue
            for (file in subDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
                if (!canonical.startsWith(canonicalRoot + File.separator)) continue
                val rel = "$sub/" + file.relativeTo(subDir).path.replace(File.separatorChar, '/')
                if (!isValidSkinRelPath(rel)) continue
                // 枚举到但读前已消失 = 采集中途被删，整包失败，不静默丢一文件。
                if (!file.isFile) throw BackupDataChangedException(operation)
                val bytes = guard.guard(file, operation) {
                    runCatching { file.readBytes() }.getOrNull()
                } ?: continue
                totalBytes += bytes.size
                if (totalBytes > SKIN_BACKUP_MAX_RAW_BYTES) {
                    Timber.w("skin library too large, skipped in backup")
                    return null
                }
                filesJson.put(rel, bytes.toBase64())
            }
        }
        if (indexText == null && filesJson.length() == 0) return null
        val section = JSONObject()
        if (indexText != null) section.put("index", indexText)
        section.put("files", filesJson)
        return section
    }

    /** 皮肤脚本备份原始字节上限：超限整段跳过只记日志（仿皮肤库跳过模式）。 */
    private const val SKIN_SCRIPTS_BACKUP_MAX_RAW_BYTES = 32L * 1024 * 1024

    /**
     * 皮肤脚本段采集：filesDir/skin_scripts 下全部有效文件
     * （相对路径 → Base64）。只认 filesDir/skin_scripts 这一唯一
     * 目录来源；相对路径须过 canonical 校验（防符号链接逃逸）
     * 与 [isValidSkinScriptRelPath]（仅 <id>/script.js 与
     * <id>/meta.json，半写 .tmp 与其它文件跳过不进包）；无脚本
     * 时返回空 files 段（非 null，见 KDoc 回放口径）；原始字节
     * 总量超限返回 null，即不写该段。
     */
    private fun collectSkinScriptsJson(
        guard: BackupChangeGuard,
        operation: String,
    ): JSONObject? {
        val rootDir = File(appContext.filesDir, SkinScriptStore.SCRIPTS_DIR)
        val filesJson = JSONObject()
        if (rootDir.isDirectory) {
            val canonicalRoot = runCatching { rootDir.canonicalPath }.getOrNull()
                ?: return null
            var totalBytes = 0L
            for (file in rootDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
                if (!canonical.startsWith(canonicalRoot + File.separator)) continue
                val rel = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                if (!isValidSkinScriptRelPath(rel)) continue
                // 枚举到但读前已消失 = 采集中途被删，整包失败，不静默丢一文件。
                if (!file.isFile) throw BackupDataChangedException(operation)
                val bytes = guard.guard(file, operation) {
                    runCatching { file.readBytes() }.getOrNull()
                } ?: continue
                totalBytes += bytes.size
                if (totalBytes > SKIN_SCRIPTS_BACKUP_MAX_RAW_BYTES) {
                    Timber.w("skin scripts too large, skipped in backup")
                    return null
                }
                filesJson.put(rel, bytes.toBase64())
            }
        }
        val section = JSONObject()
        section.put("files", filesJson)
        return section
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
    suspend fun restoreBackup(bytes: ByteArray, password: CharArray) {
        val payload = decryptPayload(bytes, password)
        val parsed = parsePayload(payload)
        // 快照解析结构直接取自当前状态的采集结果——回滚回放是把我们
        // 自己的完整状态写回去；也不再对快照二次解密重解析（省掉一
        // 轮 60 万次 PBKDF2）。采集本身可能因采集中途数据变了而失败
        // （BackupDataChangedException），此时尚未写任何数据，直接失败。
        val snapshotJson = collectPayload("还原")
        val snapshotParsed = parsePayload(snapshotJson)
        // 回滚可覆盖性（D-S1/D-M2）：快照里缺段的源，快照回放恢复不了。
        // 脚本段缺失且还原前确有脚本（超 32MB 跳段或采集失败），或皮肤
        // 库段缺失且还原前确有皮肤文件（同理），回滚后这些数据即永久丢
        // 失——此时绝不能报“已恢复到还原前状态”，必须如实报回滚未成功。
        // 皮肤库段缺失且还原前本就无皮肤文件时，快照即“空库”，回滚清
        // 空皮肤即恢复（clearSkinsForRollback），覆盖成立。
        val scriptsPresentBefore = hasSkinScripts()
        val skinsPresentBefore = hasSkinFiles()
        val rollbackCovered = rollbackCovered(
            snapshotHasScripts = snapshotParsed.skinScripts != null,
            scriptsPresent = scriptsPresentBefore,
            snapshotHasSkins = snapshotParsed.skinLibrary != null,
            skinsPresentBefore = skinsPresentBefore,
        )
        val clearSkinsForRollback = snapshotParsed.skinLibrary == null && !skinsPresentBefore
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
                snapshotJson.toString().toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw BackupException("还原准备失败：无法保存还原前快照（${e.message ?: "写盘失败"}）")
        }
        val journalEntry = RestoreJournal.Entry(
            token = token,
            phase = RestoreJournal.Phase.STAGING,
            startedAt = System.currentTimeMillis(),
            backupFingerprint = sha256Hex(bytes),
            snapshotFile = snapshotFile.name,
            completedSections = emptySet(),
        )
        try {
            RestoreJournal.write(journalDir, journalEntry)
        } catch (e: Exception) {
            runCatching { snapshotFile.delete() }
            throw BackupException("还原准备失败：无法写入还原记录（${e.message ?: "写盘失败"}）")
        }
        RestoreJournal.sweepOrphanSnapshots(journalDir, token)
        try {
            // REPLACING 是强制推进：journal 推进不了就不开始写盘——
            // 没有 journal 兜底的写盘，中断后无从对账。
            RestoreJournal.write(journalDir, journalEntry.copy(phase = RestoreJournal.Phase.REPLACING))
            // 写入与回滚都不可取消：调用方协程被取消（界面销毁等）时
            // 半途而废比继续写完更糟——半还原状态正是快照机制要防的
            withContext(NonCancellable) {
                applyPayload(
                    merged,
                    onSectionDone = { section ->
                        // 段进度是尽力而为的记录：journal 写失败不阻断
                        // 还原本体（阶段已是 REPLACING，中断后照常提示）；
                        // 段进度丢了也不影响回滚覆盖判定（见
                        // uncoveredSectionsForRollback 的 KDoc：可部分写
                        // 的段要么原子、要么快照恒覆盖）。
                        runCatching {
                            val current = RestoreJournal.read(journalDir)
                                ?: journalEntry.copy(phase = RestoreJournal.Phase.REPLACING)
                            RestoreJournal.write(
                                journalDir,
                                current.copy(completedSections = current.completedSections + section),
                            )
                        }.onFailure { Timber.w(it, "restore journal progress update failed") }
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
                        clearSkinsForRollback,
                        rollbackExtraPrefsFiles,
                    )
                }
            }.isSuccess
            // 回放成功才算回滚成功；缺段快照盖不住的源（脚本/皮肤）
            // 只要还原前存在，回滚即不完整——如实报失败，不谎报成功。
            val rolledBack = replayed && rollbackCovered
            if (rolledBack) {
                // 内存回滚已把状态恢复到还原前：中断记录不再有意义，
                // 清掉，下次启动不提示。
                RestoreJournal.clear(journalDir, journalEntry)
            }
            // 回滚未成功时 journal 保留（仍是 REPLACING）：下次启动
            // 提示用户重导／回滚，快照仍在、可走磁盘回滚。
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw BackupException(
                if (rolledBack) "还原失败，已恢复到还原前状态：${e.message ?: "未知错误"}"
                else "还原失败且自动回滚未成功，数据可能不完整：${e.message ?: "未知错误"}"
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // —— 应用级还原执行（不随界面销毁取消） ——

    /** 还原进度状态：由备份页观察，界面重建（旋转等）不影响执行本体。 */
    sealed interface RestoreState {
        data object Idle : RestoreState
        data object Running : RestoreState
        data object Done : RestoreState
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
                restoreBackup(bytes, password)
                _restoreState.value = RestoreState.Done
                // 正常还原成功：旧的中断记录已随新 journal 被取代／清掉，
                // 提示随之消失。
                _interruptedRestore.value = null
                scheduleProcessRestart(generation)
            } catch (e: Exception) {
                Timber.w(e, "Restore failed")
                _restoreState.value =
                    RestoreState.Failed(e.message ?: "未知错误")
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
     * 自身不抛错：损坏的 journal 按无 journal 处理。
     */
    fun reconcileInterruptedRestore() {
        val dir = RestoreJournal.dir(appContext.filesDir)
        val entry = runCatching { RestoreJournal.read(dir) }.getOrNull()
        val action = RestoreJournal.decide(
            entry,
            entry?.let { RestoreJournal.snapshotExists(dir, it) } ?: false,
        )
        when (action) {
            RestoreJournal.RecoveryAction.NONE -> {
                RestoreJournal.sweepOrphanSnapshots(dir, null)
                _interruptedRestore.value = null
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
        if (entry != null) RestoreJournal.clear(dir, entry)
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
     * 写过的，列入 uncovered——回滚成功但 uncovered 非空时按失败
     * 报（journal 保留，用户重导才是正解），不谎报“已恢复”。
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
                            _restoreState.value = RestoreState.Done
                            scheduleProcessRestart(generation)
                        } else {
                            throw BackupException(
                                "回滚未完全成功：${
                                    result.uncoveredSections.joinToString("、") {
                                        sectionDisplayName(it)
                                    }
                                }未能恢复（还原前快照中没有），请重新导入备份文件",
                            )
                        }
                    }
                    DiskRollbackResult.NoJournal -> throw BackupException("没有未完成的还原记录")
                    DiskRollbackResult.SnapshotMissing ->
                        throw BackupException("还原前快照已丢失，请重新导入备份文件")
                    is DiskRollbackResult.Failed -> throw BackupException(result.message)
                }
            } catch (e: Exception) {
                Timber.w(e, "Disk rollback failed")
                _restoreState.value = RestoreState.Failed(e.message ?: "未知错误")
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
            return DiskRollbackResult.Failed("还原前快照损坏，无法回滚：${e.message ?: "读取失败"}")
        }
        val snapshotParsed = try {
            parsePayload(snapshotJson)
        } catch (e: Exception) {
            return DiskRollbackResult.Failed("还原前快照解析失败，无法回滚：${e.message ?: "解析失败"}")
        }
        val uncovered = uncoveredSectionsForRollback(
            entry.completedSections,
            snapshotHasInputStats = snapshotParsed.inputStatsJson != null,
            snapshotHasSkinLibrary = snapshotParsed.skinLibrary != null,
            snapshotHasSkinScripts = snapshotParsed.skinScripts != null,
        )
        // 失败还原新建的偏好文件：以当前 shared_prefs 目录为准（磁盘
        // 回滚没有内存里的 merged 目标可比）。
        val rollbackExtraPrefsFiles = currentPrefsFilesNotInSnapshot(snapshotParsed)
        return try {
            withContext(NonCancellable) {
                applyPayload(
                    snapshotParsed,
                    forRollback = true,
                    clearSkinsForRollback = false,
                    rollbackExtraPrefsFiles = rollbackExtraPrefsFiles,
                )
            }
            // 全覆盖才清 journal；uncovered 非空时 journal 保留，
            // 提示继续存在，用户重导或忽略。
            if (uncovered.isEmpty()) RestoreJournal.clear(dir, entry)
            DiskRollbackResult.RolledBack(uncovered)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiskRollbackResult.Failed("回滚失败：${e.message ?: "未知错误"}")
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
     * 磁盘回滚覆盖判定（纯函数）：快照里缺段、且 journal 显示该段已被
     * 失败还原写过的，列入 uncovered。
     * Room 两库与 prefs 在快照里恒有（collectPayload 必写），永不缺段；
     * 输入统计／皮肤库／脚本三段是条件段，缺段且被写过即 uncovered。
     * 段写盘本身的原子性（Room 事务、tmp＋rename、目录整换）保证：
     * 快照里没有的段，还原也不会写它——journal 里没记的段不可能处于
     * 半写状态（applyPayload 的 onSectionDone 只在真实写完后记）。
     */
    internal fun uncoveredSectionsForRollback(
        completedSections: Set<String>,
        snapshotHasInputStats: Boolean,
        snapshotHasSkinLibrary: Boolean,
        snapshotHasSkinScripts: Boolean,
    ): List<String> {
        val uncovered = ArrayList<String>()
        if (RestoreJournal.SECTION_INPUT_STATS in completedSections && !snapshotHasInputStats) {
            uncovered += RestoreJournal.SECTION_INPUT_STATS
        }
        if (RestoreJournal.SECTION_SKIN_LIBRARY in completedSections && !snapshotHasSkinLibrary) {
            uncovered += RestoreJournal.SECTION_SKIN_LIBRARY
        }
        if (RestoreJournal.SECTION_SKIN_SCRIPTS in completedSections && !snapshotHasSkinScripts) {
            uncovered += RestoreJournal.SECTION_SKIN_SCRIPTS
        }
        return uncovered
    }

    private fun sectionDisplayName(section: String): String = when (section) {
        RestoreJournal.SECTION_INPUT_STATS -> "输入统计"
        RestoreJournal.SECTION_SKIN_LIBRARY -> "皮肤库"
        RestoreJournal.SECTION_SKIN_SCRIPTS -> "皮肤脚本"
        else -> section
    }

    private fun decryptPayload(bytes: ByteArray, password: CharArray): JSONObject {
        val nl = bytes.indexOf('\n'.code.toByte())
        if (nl <= 0) throw BackupException("备份文件格式不正确")
        val headerBytes = bytes.copyOfRange(0, nl)
        val header = try {
            JSONObject(String(headerBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("备份文件格式不正确")
        }
        if (header.optString("format") != FORMAT) throw BackupException("不是 Jime 备份文件")
        if (header.optInt("version", -1) != FORMAT_VERSION) {
            throw BackupException("备份版本不受支持")
        }
        val kdf = header.optString("kdf")
        if (kdf != KDF_SHA256 && kdf != KDF_SHA1) throw BackupException("备份加密参数不受支持")
        // iterations 完全来自文件头，必须钳制：极大值会让 PBKDF2 长时间挂死
        val iterations = header.optInt("iterations", ITERATIONS)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BackupException("备份加密参数不受支持")
        }
        val salt = header.optString("salt").fromBase64()
        val iv = header.optString("iv").fromBase64()
        if (salt == null || iv == null || salt.isEmpty() || iv.isEmpty()) {
            throw BackupException("备份文件格式不正确")
        }
        val key = deriveKey(password, salt, iterations, kdf)
        val cipherText = bytes.copyOfRange(nl + 1, bytes.size)
        // 只认带 AAD（头部作附加数据）的现行包：无 AAD 旧包兼容解密已
        // 随清点 D19 退场（997 版为一年前内部版本，存量旧包已清）。
        // GCM 校验失败：口令错误与文件篡改在加密上不可区分，统一提示
        val plain = decryptGcm(key, iv, cipherText, headerBytes)
            ?: throw BackupException("口令错误或备份文件已损坏")
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("备份内容解析失败")
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
    /** 皮肤库备份段（null = 旧备份无此段，还原时不碰现皮肤）。 */
    private data class SkinBackup(
        /** index.json 文本（null = 备份时无 index 或读不到，还原时不写 index） */
        val index: String?,
        /** 相对路径（skins/… 或 assets/…）→ 原始字节（已在解析阶段 Base64 解码） */
        val files: Map<String, ByteArray>,
    )

    /** 皮肤脚本备份段（null = 旧备份无此段，还原时不碰现脚本）。 */
    private data class SkinScriptsBackup(
        /** 相对路径（<id>/script.js 或 <id>/meta.json）→ 原始字节（已在解析阶段 Base64 解码） */
        val files: Map<String, ByteArray>,
    )

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
        /** 皮肤库段（null = 旧备份无此字段，还原时不碰现皮肤） */
        val skinLibrary: SkinBackup?,
        /** 皮肤脚本段（null = 旧备份无此字段，还原时不碰现脚本） */
        val skinScripts: SkinScriptsBackup?,
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
                    throw BackupException("备份包含非法 prefs 名称")
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

            // 包内清单校验（D-M1，payload v2）：清单在场时逐项核对
            // 计数与存在性，任一不符整包判失败（解析阶段拦截，不写
            // 脏数据）；旧备份无清单字段时跳过（兼容 v1）。注意计数
            // 以载荷原始数组长度为准（剪贴板去重前——清单记的是采集
            // 时的原始行数）。
            if (root.has("manifest")) {
                val manifestJson = try {
                    root.getJSONObject("manifest")
                } catch (e: Exception) {
                    throw BackupException("备份清单损坏")
                }
                val manifest = BackupManifest.fromJsonObject(manifestJson)
                    ?: throw BackupException("备份清单损坏")
                if (manifest.payloadVersion != BackupManifest.CURRENT_PAYLOAD_VERSION) {
                    throw BackupException("备份版本不受支持")
                }
                val mismatches = manifest.validate(countsFromPayload(root))
                if (mismatches.isNotEmpty()) {
                    Timber.w("backup manifest mismatch: ${mismatches.joinToString("; ")}")
                    throw BackupException("备份清单与内容不符，请重新备份")
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
                        throw BackupException("输入统计内容不是合法 JSON")
                    }
                    raw
                },
                // 皮肤库段：无字段 = 旧备份，不碰现皮肤；有字段则相对路径
                // 逐项白名单校验、Base64 逐项解码，任一非法整包判失败
                // （解析阶段拦截，不写脏文件）。index 只要求是文本，不
                // 硬校验其 JSON 结构——损坏索引由下次启动对账重建，
                // 不值得为此判整包失败；文件内容损坏则无自愈，必须拦截。
                skinLibrary = if (!root.has("skin_library")) {
                    null
                } else {
                    val section = root.getJSONObject("skin_library")
                    val index = when (val v = section.opt("index")) {
                        null -> null
                        is String -> v
                        else -> throw BackupException("皮肤备份索引不是文本")
                    }
                    val filesJson = when (val v = section.opt("files")) {
                        null -> JSONObject()
                        is JSONObject -> v
                        else -> throw BackupException("皮肤备份文件表不是对象")
                    }
                    val files = LinkedHashMap<String, ByteArray>()
                    val keys = filesJson.keys()
                    while (keys.hasNext()) {
                        val rel = keys.next()
                        if (!isValidSkinRelPath(rel)) {
                            throw BackupException("皮肤备份包含非法相对路径")
                        }
                        files[rel] = when (val v = filesJson.opt(rel)) {
                            is String -> v.fromBase64()
                                ?: throw BackupException("皮肤备份文件内容损坏")
                            else -> throw BackupException("皮肤备份文件内容损坏")
                        }
                    }
                    SkinBackup(index = index, files = files)
                },
                // 皮肤脚本段：无字段 = 旧备份，不碰现脚本；有字段则
                // 相对路径逐项白名单校验、Base64 逐项解码，任一非法
                // 整包判失败（解析阶段拦截，不写脏文件）。缺 files
                // 表视为空段（整表替换即清掉现脚本，与采集口径一致：
                // 采集无脚本时写空段，旧备份则根本无此字段）。
                skinScripts = if (!root.has("skin_scripts")) {
                    null
                } else {
                    val section = root.getJSONObject("skin_scripts")
                    val filesJson = when (val v = section.opt("files")) {
                        null -> JSONObject()
                        is JSONObject -> v
                        else -> throw BackupException("脚本备份文件表不是对象")
                    }
                    val files = LinkedHashMap<String, ByteArray>()
                    val keys = filesJson.keys()
                    while (keys.hasNext()) {
                        val rel = keys.next()
                        if (!isValidSkinScriptRelPath(rel)) {
                            throw BackupException("脚本备份包含非法相对路径")
                        }
                        files[rel] = when (val v = filesJson.opt(rel)) {
                            is String -> v.fromBase64()
                                ?: throw BackupException("脚本备份文件内容损坏")
                            else -> throw BackupException("脚本备份文件内容损坏")
                        }
                    }
                    SkinScriptsBackup(files = files)
                },
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("备份内容解析失败")
        }
    }

    /**
     * 从解密后的载荷 JSON 读实际计数（清单校验用；纯 JSONObject
     * 长度统计，与采集侧 [BackupManifest] 的计数口径逐项对应：
     * prefs 条目数 = 各表对象长度之和，行数 = 原始数组长度）。
     */
    private fun countsFromPayload(root: JSONObject): ManifestCounts {
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        var prefsEntries = 0
        val prefsNames = prefsJson.keys()
        while (prefsNames.hasNext()) {
            prefsEntries += prefsJson.optJSONObject(prefsNames.next())?.length() ?: 0
        }
        return ManifestCounts(
            prefsFiles = prefsJson.length(),
            prefsEntries = prefsEntries,
            clipboardCount = root.optJSONArray("clipboard")?.length() ?: 0,
            clipboardGroupsCount = root.optJSONArray("clipboard_groups")?.length() ?: 0,
            phrasesCount = root.optJSONArray("phrases")?.length() ?: 0,
            prefersCount = root.optJSONArray("candidatePrefers")?.length() ?: 0,
            sortingsCount = root.optJSONArray("candidateSortings")?.length() ?: 0,
            inputStatsPresent = root.has("input_stats_json"),
            skinLibraryPresent = root.has("skin_library"),
            skinLibraryFiles = root.optJSONObject("skin_library")
                ?.optJSONObject("files")?.length() ?: 0,
            skinScriptsPresent = root.has("skin_scripts"),
            skinScriptsFiles = root.optJSONObject("skin_scripts")
                ?.optJSONObject("files")?.length() ?: 0,
        )
    }

    // prefs 名白名单：字母/数字/点/下划线/连字符，且不许是 "." / ".."
    // 这类纯相对路径段（点本身在字符白名单内，必须单独排除）
    private val PREFS_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

    private fun isValidPrefsName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            PREFS_NAME_PATTERN.matches(name)

    /**
     * 皮肤备份文件相对路径白名单（解析阶段防穿越）：仅 skins/ 与 assets/
     * 下的普通文件路径；段为空、含 . / ..、含反斜杠、绝对路径一律拒绝。
     * index.json 不走此口径（它是段内独立字段，不在 files 表中）。
     */
    internal fun isValidSkinRelPath(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.startsWith("/") || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size < 2) return false
        if (parts[0] != "skins" && parts[0] != "assets") return false
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return true
    }

    /**
     * 皮肤脚本备份文件相对路径白名单（解析阶段防穿越）：仅
     * <id>/script.js 与 <id>/meta.json 两段路径放行；id 须过脚本
     * id 口径（无分隔符、非 . / ..）；段为空、含 . / ..、含反斜杠、
     * 绝对路径、更深嵌套、半写 .tmp 一律拒绝。
     */
    internal fun isValidSkinScriptRelPath(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.startsWith("/") || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size != 2) return false
        if (!SkinScriptIds.isValid(parts[0])) return false
        return parts[1] == SkinScriptStore.SCRIPT_FILE ||
            parts[1] == SkinScriptStore.META_FILE
    }

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
        else -> throw BackupException("备份内容解析失败")
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
     * 输入统计、皮肤脚本不在此列：prefs 整表覆盖是设置还原的本意，
     * 统计只是计数，脚本是快照语义（见类 KDoc）。
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
            skinLibrary = backup.skinLibrary,
            skinScripts = backup.skinScripts,
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

    /** 还原前脚本库是否确有脚本文件（任一文件即算，含半写 .tmp——回滚覆盖判定取保守口径）。 */
    private fun hasSkinScripts(): Boolean {
        val dir = File(appContext.filesDir, SkinScriptStore.SCRIPTS_DIR)
        return dir.isDirectory && dir.walkTopDown().any { it.isFile }
    }

    /** 还原前皮肤库是否确有文件（index.json 或 skins/、assets/ 下任一文件）。 */
    private fun hasSkinFiles(): Boolean {
        val dir = App.skinLibraryDir
        return dir.isDirectory && dir.walkTopDown().any { it.isFile }
    }

    /**
     * 回滚可覆盖性（D-S1/D-M2）：快照里缺段的源，快照回放恢复不了。
     * 脚本段缺失且还原前确有脚本、或皮肤库段缺失且还原前确有皮肤文件
     * 时，回滚后这些数据即永久丢失——此时绝不能报“已恢复到还原前状态”。
     */
    private fun rollbackCovered(
        snapshotHasScripts: Boolean,
        scriptsPresent: Boolean,
        snapshotHasSkins: Boolean,
        skinsPresentBefore: Boolean,
    ): Boolean =
        (snapshotHasScripts || !scriptsPresent) &&
            (snapshotHasSkins || !skinsPresentBefore)

    /**
     * 写入段：输入已在解析阶段全量校验，此处只做落盘，不再有解析错误。
     * @param forRollback 回滚回放：快照即还原前状态，分组恒整表替换、
     *   失败还原新建的偏好文件删掉、皮肤库整表替换（或快照即空库时
     *   清空）；正常还原走合并写回口径，不碰快照里没有的现数据。
     */
    /**
     * 把解析后的备份写回各存储。失败时由调用方用快照回放整体回滚。
     * [onSectionDone]：每写完一段（剪贴板库／主库／prefs／输入统计／
     * 皮肤库／皮肤脚本）回调一次，供还原 journal 记段进度；条件段
     * （输入统计／皮肤库／皮肤脚本）只在真实写盘后才回调，跳过的段
     * 不记（见 [uncoveredSectionsForRollback] 的 KDoc）。回滚回放
     * 不传回调（journal 正在被处置，不记进度）。
     */
    private suspend fun applyPayload(
        parsed: ParsedBackup,
        forRollback: Boolean = false,
        clearSkinsForRollback: Boolean = false,
        rollbackExtraPrefsFiles: Set<String> = emptySet(),
        onSectionDone: ((String) -> Unit)? = null,
    ) {
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)
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
            if (!editor.commit()) throw BackupException("设置写入失败（$name），还原中止")
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
            val dir = File(appContext.filesDir, "input_stats")
            runCatching { dir.mkdirs() }
            val tmp = File(dir, "stats.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(File(dir, "stats.json"))) {
                throw BackupException("输入统计写入失败，还原中止")
            }
            onSectionDone?.invoke(RestoreJournal.SECTION_INPUT_STATS)
        }

        // —— 皮肤库文件（正常还原合并写回，非整表覆盖）：只覆盖备份中
        // 同路径的文件，不删除备份中没有的现文件；无字段（旧备份）时
        // 不碰现皮肤。写回后还原流程末尾重启进程，下次启动对账会把
        // 磁盘孤儿代次重新登记、把损坏索引重建，因此此处不做索引一
        // 致性校验。临时文件＋改名：中途失败不留半截文件。回滚模式
        // 下整表替换（D-M2 的皮肤残留）：先清掉现库再写回快照，失败
        // 还原中途新增的文件在此清掉；快照即空库时（clearSkinsForRollback）
        // 只清不写。
        parsed.skinLibrary?.let { skin ->
            val rootDir = App.skinLibraryDir
            runCatching { rootDir.mkdirs() }
            if (forRollback) {
                File(rootDir, "index.json").delete()
                File(rootDir, "skins").deleteRecursively()
                File(rootDir, "assets").deleteRecursively()
            }
            skin.index?.let { indexText ->
                val tmp = File(rootDir, "index.json.tmp")
                tmp.writeText(indexText, Charsets.UTF_8)
                if (!tmp.renameTo(File(rootDir, "index.json"))) {
                    throw BackupException("皮肤索引写入失败，还原中止")
                }
            }
            for ((rel, bytes) in skin.files) {
                // 解析阶段已白名单校验，此处只做落盘（与 prefs 名口径一致：
                // 输入已在解析阶段全量校验，写入段不再有解析错误）。
                val target = File(rootDir, rel)
                runCatching { target.parentFile?.mkdirs() }
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    throw BackupException("皮肤文件写入失败（$rel），还原中止")
                }
            }
            onSectionDone?.invoke(RestoreJournal.SECTION_SKIN_LIBRARY)
        }
        // 回滚模式 + 快照无皮肤段 + 还原前本就无皮肤文件：快照即“空库”，
        // 失败还原新增的皮肤文件必须清干净（clearSkinsForRollback）。
        // 快照无段但还原前确有皮肤时（超限跳段）不碰皮肤，由回滚覆盖
        // 判定如实报回滚未成功。
        if (forRollback && clearSkinsForRollback) {
            val rootDir = App.skinLibraryDir
            File(rootDir, "index.json").delete()
            File(rootDir, "skins").deleteRecursively()
            File(rootDir, "assets").deleteRecursively()
        }

        // —— 皮肤脚本文件（整表替换，非合并写回，见 KDoc）：换位写，
        // 先把新脚本写进临时目录、再整体换位；无字段（旧备份）时不
        // 碰现脚本。旧实现“先删整树再写”，写盘中途失败即把还原前
        // 的脚本全毁（D-S1），且回滚回放走同一路径同样先删后写。
        // 换位写失败只丢临时目录，旧脚本原样保留；换位本身是同目
        // 录 rename，原子完成。失败回滚走同一换位路径写回还原前
        // 快照，故失败还原不留孤儿脚本（快照由新版采集产生，恒带
        // 此段；超限跳段的快照除外，见 KDoc——该情形下回放不碰脚本）。
        parsed.skinScripts?.let { scripts ->
            val scriptsDir = File(appContext.filesDir, SkinScriptStore.SCRIPTS_DIR)
            val tmpDir = File(appContext.filesDir, SkinScriptStore.SCRIPTS_DIR + ".tmp")
            val oldDir = File(appContext.filesDir, SkinScriptStore.SCRIPTS_DIR + ".old")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            if (oldDir.exists()) oldDir.deleteRecursively()
            try {
                runCatching { tmpDir.mkdirs() }
                for ((rel, bytes) in scripts.files) {
                    // 解析阶段已白名单校验，此处只做落盘。
                    val target = File(tmpDir, rel)
                    runCatching { target.parentFile?.mkdirs() }
                    val tmp = File(target.parentFile, "${target.name}.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) {
                        throw BackupException("脚本文件写入失败（$rel），还原中止")
                    }
                }
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                if (e is kotlinx.coroutines.CancellationException) throw e
                tmpDir.deleteRecursively()
                throw e
            }
            // 换位：旧目录 → .old，临时目录 → 脚本目录，再删 .old。
            // 旧目录不存在（还原前无脚本）时直接换位。
            val hadOld = scriptsDir.exists()
            if (hadOld && !scriptsDir.renameTo(oldDir)) {
                tmpDir.deleteRecursively()
                throw BackupException("脚本库写入失败，还原中止")
            }
            if (!tmpDir.renameTo(scriptsDir)) {
                // 换位失败：尽力把旧目录挪回来，临时目录清掉；旧目录
                // 恢复失败时 .old 原样保留（下次备份采集只认脚本目录，
                // 不会误读它；下次还原先清残留 .old），还原判失败、
                // 由回滚回放处置。
                if (hadOld) oldDir.renameTo(scriptsDir)
                tmpDir.deleteRecursively()
                throw BackupException("脚本库写入失败，还原中止")
            }
            oldDir.deleteRecursively()
            onSectionDone?.invoke(RestoreJournal.SECTION_SKIN_SCRIPTS)
        }
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
