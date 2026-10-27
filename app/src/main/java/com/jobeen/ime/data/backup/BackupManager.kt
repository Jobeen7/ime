package com.jobeen.ime.data.backup

import android.content.Context
import android.os.Build
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.CandidatePrefer
import com.jobeen.ime.data.database.CandidateSorting
import com.jobeen.ime.data.database.ClipboardDatabase
import com.jobeen.ime.data.database.ClipboardRecord
import com.jobeen.ime.data.database.dedupeClipboardByText
import com.jobeen.ime.data.database.PhraseRecord
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.data.manager.ClipboardManager
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
 * WebDAV 配置整表（user_dict_sync）按设备本地状态处理：导出照常整表
 * 采集（包本身有口令加密），还原时整表跳过回写——服务器地址、账号
 * 口令与同步时刻都是本机配置/状态，不随备份跨设备走（清点 S8 定论，
 * 替代原脱敏＋回填五层规则与密码专用迁移通道）。
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

    class BackupException(message: String) : Exception(message)

    // ---------------- 备份 ----------------

    /** 采集当前数据并加密，返回完整备份文件字节（头部行 + 密文） */
    suspend fun createBackup(password: CharArray): ByteArray =
        encryptPayload(collectPayload(), password)

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
        // WebDAV 整表照常采集进包（S8）：还原侧按设备本地表整表跳过，
        // 采集侧无脱敏——密码在 prefs 里本就是 Keystore 密文、包又有
        // 口令加密，不再需要剔键＋独立字段迁移通道
        root.put("prefs", prefsJson)

        // —— Room 四表
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)
        // 拆库迁移本体已随清点 Q2 退场（现行设备早已迁完），采集直接
        // 读新库；原「迁移不成中止备份」的耦合随之解除

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
     * 写入前先采一份当前状态的内存快照：写入阶段任一步失败都用它整
     * 体回滚，绝不留下新旧混杂的半还原状态。原「加密快照落盘以防进
     * 程被杀」的第二层已随清点 S7 退场——进程被杀的还原中断无自动
     * 恢复，用户重导备份即可。
     */
    suspend fun restoreBackup(bytes: ByteArray, password: CharArray) {
        val payload = decryptPayload(bytes, password)
        val parsed = parsePayload(payload)
        // 快照解析结构直接取自当前状态的采集结果——回滚回放是把我们
        // 自己的完整状态写回去；也不再对快照二次解密重解析（省掉一
        // 轮 60 万次 PBKDF2）
        val snapshotParsed = parsePayload(collectPayload())
        try {
            // 写入与回滚都不可取消：调用方协程被取消（界面销毁等）时
            // 半途而废比继续写完更糟——半还原状态正是快照机制要防的
            withContext(NonCancellable) { applyPayload(parsed) }
        } catch (e: Exception) {
            val rolledBack = runCatching {
                withContext(NonCancellable) { applyPayload(snapshotParsed) }
            }.isSuccess
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw BackupException(
                if (rolledBack) "还原失败，已恢复到还原前状态：${e.message ?: "未知错误"}"
                else "还原失败且自动回滚未成功，数据可能不完整：${e.message ?: "未知错误"}"
            )
        }
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

    /** 还原互斥：check-then-set 靠状态值判断不是原子操作，用 CAS 守门。 */
    private val restoreRunning =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 在应用级作用域启动还原：旋转/退出备份页都不会取消它（旧实现挂在
     * 界面作用域上，旋转即取消，还原会遭腰斩）。重复启动忽略。
     */
    fun startRestore(bytes: ByteArray, password: CharArray) {
        if (!restoreRunning.compareAndSet(false, true)) {
            password.fill(' ')
            return
        }
        _restoreState.value = RestoreState.Running
        restoreScope.launch {
            try {
                restoreBackup(bytes, password)
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
    private class ParsedBackup(
        val prefs: Map<String, Map<String, Any>>,
        val clipboard: List<ClipboardRecord>,
        val phrases: List<PhraseRecord>,
        val prefers: List<CandidatePrefer>,
        val sortings: List<CandidateSorting>,
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
                // 剪贴板表 text 唯一（ClipboardDatabase v4 起）：备份内
                // 同 text 多行（旧版本应用层唯一性约定失效时期的产物）
                // 直插会撞唯一索引、整表事务回滚让还原全灭。解析阶段
                // 先按统一口径去重（pinned 优先、timestamp 最新、
                // id 最大，与库内迁移去重一致）
                clipboard = dedupeClipboardByText(clipList),
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

    private suspend fun applyPayload(parsed: ParsedBackup) {
        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)
        // 拆库迁移本体已随清点 Q2 退场（现行设备早已迁完）：原「迁移
        // 不成中止还原」的耦合随之解除，直接整表替换

        // —— Room 四表：各自单事务内整表替换，中途失败整表回滚
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
