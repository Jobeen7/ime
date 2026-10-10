package com.jobeen.ime.data.backup

import android.content.Context
import android.os.Build
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.CandidatePrefer
import com.jobeen.ime.data.database.CandidateSorting
import com.jobeen.ime.data.database.ClipboardDatabase
import com.jobeen.ime.data.database.ClipboardRecord
import com.jobeen.ime.data.database.PhraseRecord
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.data.manager.ClipboardManager
import androidx.room.withTransaction
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
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
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 整体备份与还原：设置（全部 SharedPreferences）+ 剪贴板历史 + 常用语 +
 * 选词偏好 + 候选排序，导出为单个口令加密文件（.jbk）。
 *
 * 加密格式：文件 = 头部 JSONObject 的 UTF-8 字节 + '\n' + 密文。头部含
 * format/version/kdf/iterations/salt/iv，明文可见但不含任何数据；头部
 * 字节同时作为 GCM 的 AAD 被认证（篡改头部参数同样导致解密失败）；
 * 密文为 AES-256-GCM（口令经 PBKDF2 派生），口令错误或文件被篡改时
 * GCM 校验失败，还原拒绝执行。KDF 算法随文件记录（新设备 SHA-256、
 * 低版本设备 SHA-1），跨设备可迁移。iterations 只接受合理区间，
 * 构造文件用极大迭代数拖死还原会被直接拒绝。
 *
 * WebDAV 密码特殊处理：prefs 原样导出时它是 Keystore 密文、换设备解不开。
 * 因此采集时单独把解密后的密码放进备份包的 webdavPassword 字段并从 prefs
 * 副本中删掉密码键；还原时 prefs 先整表回写、密码最后经 UserDictPrefs
 * 的加密 setter 在本机重新加密落盘。密码的明文只存在于内存与加密包内部，
 * 两个落盘点（本机 prefs、备份文件）都不是明文。
 *
 * 还原是整体覆盖，且严格分两段：先把解密后的内容完整解析为内存对象
 * （此阶段零写入，任何结构/类型错误直接拒绝，不留半还原状态），再执行
 * 写入——Room 四表各自在单事务内整表替换，prefs 随后逐文件同步提交。
 * 各管理器有内存缓存，因此 UI 层在还原成功后应引导重启应用使全部生效。
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
    private const val CLIPBOARD_PREFS = "clipboard_settings"
    private const val CLIPBOARD_MIGRATED_KEY = "clipboard_db_migrated_v2"
    private const val SALT_BYTES = 32
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val WEBDAV_PREFS = "user_dict_sync"

    /** 还原前加密快照在 filesDir 的文件名：进程被杀后凭它+口令回滚 */
    private const val RESTORE_SNAPSHOT_NAME = "restore-snapshot.jbk"

    class BackupException(message: String) : Exception(message)

    // ---------------- 备份 ----------------

    /** 采集当前数据并加密，返回完整备份文件字节（头部行 + 密文） */
    suspend fun createBackup(password: CharArray): ByteArray =
        encryptPayload(collectPayload(), password)

    /** 把已收集的载荷 JSON 加密为备份文件字节（createBackup 与还原前快照共用）。 */
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

    private suspend fun collectPayload(): JSONObject {
        val root = JSONObject()

        // —— SharedPreferences：枚举 shared_prefs 目录全部文件整表导出
        val prefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val prefsJson = JSONObject()
        val names = prefsDir.listFiles { f -> f.extension == "xml" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
        for (name in names) {
            val sp = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            for ((key, value) in sp.all) {
                if (value == null) continue
                entries.put(key, encodePrefValue(value))
            }
            prefsJson.put(name, entries)
        }
        // WebDAV 密码：从 prefs 副本删除（密文换设备解不开），改走独立字段
        prefsJson.optJSONObject(WEBDAV_PREFS)?.remove("webdav_password")
        root.put("webdavPassword", UserDictPrefs.password)
        root.put("prefs", prefsJson)

        // —— Room 四表
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)

        val clips = JSONArray()
        // 只导出有效行：历史软删除存量不进备份（删除已改物理删，此为存量兜底）
        for (r in clipDb.clipboardDao().getAllActive()) {
            clips.put(
                JSONObject()
                    .put("id", r.id).put("text", r.text).put("timestamp", r.timestamp)
                    .put("cloud", r.cloud).put("deleted", r.deleted).put("deletedAt", r.deletedAt)
                    .put("pinned", r.pinned).put("sortOrder", r.sortOrder)
            )
        }
        root.put("clipboard", clips)

        val phrases = JSONArray()
        for (r in db.phraseDao().getAll()) {
            phrases.put(
                JSONObject()
                    .put("id", r.id).put("text", r.text)
                    .put("label", r.label).put("createdAt", r.createdAt)
                    .put("sortOrder", r.sortOrder).put("pinned", r.pinned)
            )
        }
        root.put("phrases", phrases)

        val prefers = JSONArray()
        for (r in db.candidatePreferDao().getAllFull()) {
            prefers.put(
                JSONObject()
                    .put("text", r.text).put("count", r.count).put("context", "")
                    .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
            )
        }
        root.put("candidatePrefers", prefers)

        val sortings = JSONArray()
        for (r in db.candidateSortingDao().getAllFull()) {
            sortings.put(
                JSONObject()
                    .put("key", r.key)
                    .put("candidateIds", JSONArray(r.candidateIds))
            )
        }
        root.put("candidateSortings", sortings)
        return root
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
     * 解密并整体覆盖还原。口令错误/文件损坏/结构非法抛 [BackupException]，
     * 且解密与完整结构解析全部通过后才开始写任何数据（不会半还原）。
     */
    suspend fun restoreBackup(bytes: ByteArray, password: CharArray) =
        restoreInternal(bytes, password, isSnapshotRollback = false)

    /**
     * 还原本体。[isSnapshotRollback] 区分数据来源：
     * - 外部备份文件：脱敏解析（不带入 WebDAV 服务器等），并在写入前
     *   把当前状态加密落盘，作为进程被杀后的回滚手段；
     * - 落盘快照回滚：快照是本机自采的完整状态，按自有状态解析
     *   （不脱敏，与内存回滚一致），且**绝不能再落盘新快照**——那会
     *   把当前（已还原）状态覆写到快照文件上，原始还原前状态就此
     *   丢失，回滚也就失去了目标。
     */
    private suspend fun restoreInternal(
        bytes: ByteArray, password: CharArray, isSnapshotRollback: Boolean
    ) {
        val payload = decryptPayload(bytes, password)
        val parsed = parsePayload(payload, sanitizeWebdav = !isSnapshotRollback)
        // 写任何数据前，先把当前状态快照一份：写入阶段任一步失败都用
        // 快照整体回滚，绝不留下新旧混杂的半还原状态。快照解析结构
        // 直接取自当前状态的采集结果——回滚回放是把我们自己的完整
        // 状态写回去，不走「外部备份」的 WebDAV 脱敏过滤（否则回滚会
        // 把用户现有的服务器地址/同步基准整表抹掉，等于回滚本身又
        // 制造了一次数据丢失）；也不再对快照二次解密重解析（省掉
        // 一轮 60 万次 PBKDF2）。
        val snapshotJson = collectPayload()
        val snapshotParsed = parsePayload(snapshotJson, sanitizeWebdav = false)
        if (!isSnapshotRollback) {
            // 加密快照落盘到私有目录：进程若在写入中途被杀（系统回收、
            // 断电），内存回滚随之消失，下次打开备份页凭此文件+口令仍可
            // 回到还原前状态。落盘失败只降级为内存回滚，不阻塞还原。
            persistRestoreSnapshot(encryptPayload(snapshotJson, password))
        }
        try {
            // 写入与回滚都不可取消：调用方协程被取消（界面销毁等）时
            // 半途而废比继续写完更糟——半还原状态正是快照机制要防的
            withContext(NonCancellable) { applyPayload(parsed) }
        } catch (e: Exception) {
            val rolledBack = runCatching {
                withContext(NonCancellable) { applyPayload(snapshotParsed) }
            }.isSuccess
            // 外部还原失败且内存回滚成功：落盘快照已无用，删掉；
            // 落盘快照回滚本身失败时保留文件，用户可换口令/重试，
            // 它仍是回到还原前状态的唯一凭据
            if (rolledBack && !isSnapshotRollback) clearRestoreSnapshot()
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw BackupException(
                if (rolledBack) "还原失败，已恢复到还原前状态：${e.message ?: "未知错误"}"
                else "还原失败且自动回滚未成功，数据可能不完整：${e.message ?: "未知错误"}"
            )
        }
        clearRestoreSnapshot()
    }

    // —— 还原中断的落盘快照（进程被杀后的最后回滚手段） ——

    private val restoreSnapshotFile
        get() = File(appContext.filesDir, RESTORE_SNAPSHOT_NAME)

    private fun persistRestoreSnapshot(bytes: ByteArray) {
        runCatching {
            val tmp = File(appContext.filesDir, "$RESTORE_SNAPSHOT_NAME.tmp")
            tmp.writeBytes(bytes)
            check(tmp.renameTo(restoreSnapshotFile)) { "rename failed" }
        }.onFailure { Timber.w(it, "Persist restore snapshot failed") }
    }

    private fun clearRestoreSnapshot() {
        runCatching { restoreSnapshotFile.delete() }
    }

    /** 是否留有上次还原中断的落盘快照（备份页据此提示回滚）。 */
    fun hasRestoreSnapshot(): Boolean {
        // persistRestoreSnapshot 是先写 .tmp 再 rename：进程在中途被杀
        // 会留下孤儿 .tmp，顺手清掉（主文件以 rename 成功为准）
        runCatching {
            val tmp = File(appContext.filesDir, "$RESTORE_SNAPSHOT_NAME.tmp")
            if (tmp.isFile) tmp.delete()
        }
        return restoreSnapshotFile.isFile
    }

    /** 用备份口令把落盘快照还原回去（即回到上次还原之前的状态）。 */
    suspend fun rollbackToRestoreSnapshot(password: CharArray) {
        val bytes = runCatching { restoreSnapshotFile.readBytes() }
            .getOrElse { throw BackupException("回滚快照已损坏或不存在") }
        restoreInternal(bytes, password, isSnapshotRollback = true)
    }

    /** 用户确认不需要回滚时丢弃落盘快照。 */
    fun discardRestoreSnapshot() = clearRestoreSnapshot()

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

    /** 还原互斥：check-then-set 靠状态值判断不是原子操作，用 CAS 守门。 */
    private val restoreRunning =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 在应用级作用域启动还原：旋转/退出备份页都不会取消它（旧实现挂在
     * 界面作用域上，旋转即取消，还原与回滚同遭腰斩）。重复启动忽略。
     */
    fun startRestore(bytes: ByteArray, password: CharArray, rollbackSnapshot: Boolean = false) {
        if (!restoreRunning.compareAndSet(false, true)) {
            password.fill(' ')
            return
        }
        _restoreState.value = RestoreState.Running
        restoreScope.launch {
            try {
                if (rollbackSnapshot) rollbackToRestoreSnapshot(password)
                else restoreBackup(bytes, password)
                _restoreState.value = RestoreState.Done
                scheduleProcessRestart()
            } catch (e: Exception) {
                Timber.w(e, "Restore failed")
                _restoreState.value =
                    RestoreState.Failed(e.message ?: "未知错误")
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
     */
    private fun scheduleProcessRestart() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 600)
    }

    /** 备份页消费掉终态后复位，避免下次打开页面重复处理。 */
    fun consumeRestoreState() {
        if (_restoreState.value != RestoreState.Running) {
            _restoreState.value = RestoreState.Idle
        }
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
        val plain = decryptGcm(key, iv, cipherText, headerBytes)
            // 997 版备份的头部未作 AAD，无 AAD 再试一次以便旧包可还原；
            // 新包带 AAD 校验，第一试即成功，不会走到这里
            ?: decryptGcm(key, iv, cipherText, null)
            // GCM 校验失败：口令错误与文件篡改在加密上不可区分，统一提示
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
    private class ParsedBackup(
        val prefs: Map<String, Map<String, Any>>,
        val webdavPassword: String,
        val clipboard: List<ClipboardRecord>,
        val phrases: List<PhraseRecord>,
        val prefers: List<CandidatePrefer>,
        val sortings: List<CandidateSorting>,
    )

    /**
     * 把解密后的 JSON 完整解析为内存对象。任何结构/类型错误抛
     * [BackupException]，此时尚未写入任何数据。
     */
    /**
     * @param sanitizeWebdav 还原外部备份时为 true（滤掉服务器地址/
     * 明文开关/同步基准，防恶意备份改道）；回滚回放本机自采快照时
     * 为 false——那是用户自己的完整状态，必须原样写回。
     */
    private fun parsePayload(root: JSONObject, sanitizeWebdav: Boolean = true): ParsedBackup {
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
            // 还原不得带入风险/过期的 WebDAV 状态：
            // - allowHttp：备份包不应能替用户打开明文传输开关；
            // - sync_etag_* / 上次同步时间：属于另一台设备的同步基准，沿用会让冲突检测失真。
            // 整表覆盖时这些键因此被清掉，回到默认（HTTPS-only、无基准先合并）
            if (sanitizeWebdav) prefs[WEBDAV_PREFS]?.let { webdav ->
                val filtered = webdav.filterKeys { key ->
                    key != "webdav_allow_http" &&
                        // 服务器地址同样不从备份还原：恶意备份可借整表覆盖把
                        // 服务器指向攻击者，用户之后填入真实口令同步即泄露；
                        // 账号与口令仍可迁移（没有地址时它们无处发送）
                        key != "webdav_server" &&
                        key != "last_upload_time" &&
                        key != "last_download_time" &&
                        !key.startsWith("sync_etag_")
                }
                // 服务器地址与明文开关是本机配置而非备份内容：整表覆盖
                // 会连本机现值一起抹掉（还原自己的备份也丢服务器设置），
                // 用解析当下的本机现值回填这两个键。同步基准不回填——
                // 保持「无基准先全量合并」的安全方向，让还原后的词库
                // 数据与远端重新对账，而不是拿旧基准误判未变化。
                val local = appContext
                    .getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE).all
                val refilled = LinkedHashMap(filtered)
                for (key in listOf("webdav_server", "webdav_allow_http")) {
                    local[key]?.let { refilled[key] = it }
                }
                prefs[WEBDAV_PREFS] = refilled
            }
            // 还原语义以备份为准：强制剪贴板拆库迁移标记为已完成，防止重启后
            // 主库残留的旧剪贴板行被迁移逻辑合并进刚还原好的剪贴板表
            val clipSettings = LinkedHashMap(prefs[CLIPBOARD_PREFS].orEmpty())
            clipSettings[CLIPBOARD_MIGRATED_KEY] = true
            prefs[CLIPBOARD_PREFS] = clipSettings

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
                    // 旧备份无排序序号：用 -timestamp 派生，保持备份内的时间倒序
                    sortOrder = if (o.has("sortOrder")) o.optLong("sortOrder")
                    else -o.optLong("timestamp"),
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

            return ParsedBackup(
                prefs = prefs,
                webdavPassword = root.optString("webdavPassword", ""),
                clipboard = clipList,
                phrases = phraseList,
                prefers = preferList,
                sortings = sortingList,
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("备份内容解析失败")
        }
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
        else -> throw BackupException("备份内容解析失败")
    }

    /** 写入段：输入已在解析阶段全量校验，此处只做落盘，不再有解析错误 */
    private suspend fun applyPayload(parsed: ParsedBackup) {
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)

        // —— Room 四表：各自单事务内整表替换，中途失败整表回滚
        clipDb.withTransaction {
            clipDb.clipboardDao().deleteAllRaw()
            if (parsed.clipboard.isNotEmpty()) {
                // 还原入口同样限长：旧备份里的超限文本不能把毒化行带回库里
                val clipped = parsed.clipboard.map { r ->
                    if (r.text.length > ClipboardManager.MAX_TEXT_LENGTH) {
                        r.copy(text = r.text.take(ClipboardManager.MAX_TEXT_LENGTH))
                    } else r
                }
                clipDb.clipboardDao().insertAll(clipped)
            }
        }
        db.withTransaction {
            db.phraseDao().deleteAll()
            if (parsed.phrases.isNotEmpty()) db.phraseDao().insertAll(parsed.phrases)
            db.candidatePreferDao().deleteAll()
            if (parsed.prefers.isNotEmpty()) db.candidatePreferDao().insertAll(parsed.prefers)
            db.candidateSortingDao().deleteAll()
            if (parsed.sortings.isNotEmpty()) db.candidateSortingDao().insertAll(parsed.sortings)
        }

        // —— prefs 整表覆盖（先清后写，与备份时点的一致状态）
        for ((name, entries) in parsed.prefs) {
            val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            for ((key, value) in entries) putPrefValue(editor, key, value)
            // prefs 回写必须同步落盘：随后可能马上重启进程，异步 apply 有丢失窗口；
            // 同步提交失败必须让整个还原判失败（触发快照回滚），只记日志继续
            // 会留下数据库是新数据、偏好是旧数据的混杂状态
            if (!editor.commit()) throw BackupException("设置写入失败（$name），还原中止")
        }
        // WebDAV 密码最后单独回写，经加密通道在本机 Keystore 重新加密
        UserDictPrefs.password = parsed.webdavPassword

        // 引擎侧候选偏好有内存快照，还原后显式失效，不依赖随后的杀进程重启
        CandidatePreferCache.invalidate()
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
