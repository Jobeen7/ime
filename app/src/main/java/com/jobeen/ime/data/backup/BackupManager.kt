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
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import org.json.JSONArray
import org.json.JSONObject
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
 * format/version/kdf/iterations/salt/iv，明文可见但不含任何数据；密文为
 * AES-256-GCM（口令经 PBKDF2 派生），口令错误或文件被篡改时 GCM 校验
 * 失败，还原拒绝执行。KDF 算法随文件记录（新设备 SHA-256、低版本
 * 设备 SHA-1），跨设备可迁移。
 *
 * WebDAV 密码特殊处理：prefs 原样导出时它是 Keystore 密文、换设备解不开。
 * 因此采集时单独把解密后的密码放进备份包的 webdavPassword 字段并从 prefs
 * 副本中删掉密码键；还原时 prefs 先整表回写、密码最后经 UserDictPrefs
 * 的加密 setter 在本机重新加密落盘。密码的明文只存在于内存与加密包内部，
 * 两个落盘点（本机 prefs、备份文件）都不是明文。
 *
 * 还原是整体覆盖（prefs 先清后写、四张表先清后插），且各管理器有内存
 * 缓存，因此 UI 层在还原成功后应引导重启应用使全部生效。
 */
object BackupManager {

    const val FILE_EXTENSION = "jbk"
    private const val FORMAT = "jime-backup"
    private const val FORMAT_VERSION = 1
    private const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
    private const val KDF_SHA1 = "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 200_000
    private const val SALT_BYTES = 32
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val WEBDAV_PREFS = "user_dict_sync"

    class BackupException(message: String) : Exception(message)

    // ---------------- 备份 ----------------

    /** 采集当前数据并加密，返回完整备份文件字节（头部行 + 密文） */
    suspend fun createBackup(password: CharArray): ByteArray {
        val payload = collectPayload().toString().toByteArray(Charsets.UTF_8)
        val kdf = if (Build.VERSION.SDK_INT >= 26) KDF_SHA256 else KDF_SHA1
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS, kdf)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(payload)

        val header = JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("kdf", kdf)
            .put("iterations", ITERATIONS)
            .put("salt", salt.toBase64())
            .put("iv", iv.toBase64())
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
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
        for (r in clipDb.clipboardDao().getAllRaw()) {
            clips.put(
                JSONObject()
                    .put("id", r.id).put("text", r.text).put("timestamp", r.timestamp)
                    .put("cloud", r.cloud).put("deleted", r.deleted).put("deletedAt", r.deletedAt)
            )
        }
        root.put("clipboard", clips)

        val phrases = JSONArray()
        for (r in db.phraseDao().getAll()) {
            phrases.put(
                JSONObject()
                    .put("id", r.id).put("text", r.text)
                    .put("label", r.label).put("createdAt", r.createdAt)
            )
        }
        root.put("phrases", phrases)

        val prefers = JSONArray()
        for (r in db.candidatePreferDao().getAllFull()) {
            prefers.put(
                JSONObject()
                    .put("text", r.text).put("count", r.count).put("context", r.context)
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
     * 解密并整体覆盖还原。口令错误/文件损坏抛 [BackupException]，
     * 且解密与结构校验全部通过后才开始写任何数据（不会半还原）。
     */
    suspend fun restoreBackup(bytes: ByteArray, password: CharArray) {
        val payload = decryptPayload(bytes, password)
        applyPayload(payload)
    }

    private fun decryptPayload(bytes: ByteArray, password: CharArray): JSONObject {
        val nl = bytes.indexOf('\n'.code.toByte())
        if (nl <= 0) throw BackupException("备份文件格式不正确")
        val header = try {
            JSONObject(String(bytes, 0, nl, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("备份文件格式不正确")
        }
        if (header.optString("format") != FORMAT) throw BackupException("不是 Jime 备份文件")
        if (header.optInt("version", -1) != FORMAT_VERSION) {
            throw BackupException("备份版本不受支持")
        }
        val kdf = header.optString("kdf")
        if (kdf != KDF_SHA256 && kdf != KDF_SHA1) throw BackupException("备份加密参数不受支持")
        val salt = header.optString("salt").fromBase64()
        val iv = header.optString("iv").fromBase64()
        if (salt == null || iv == null || salt.isEmpty() || iv.isEmpty()) {
            throw BackupException("备份文件格式不正确")
        }
        val key = deriveKey(password, salt, header.optInt("iterations", ITERATIONS), kdf)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(bytes.copyOfRange(nl + 1, bytes.size))
        } catch (e: Exception) {
            // GCM 校验失败：口令错误与文件篡改在加密上不可区分，统一提示
            throw BackupException("口令错误或备份文件已损坏")
        }
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("备份内容解析失败")
        }
    }

    private suspend fun applyPayload(root: JSONObject) {
        // —— prefs 整表覆盖（先清后写，与备份时点的一致状态）
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        val names = prefsJson.keys()
        while (names.hasNext()) {
            val name = names.next()
            val entries = prefsJson.getJSONObject(name)
            val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                putPrefValue(editor, key, entries.getJSONObject(key))
            }
            // prefs 回写必须同步落盘：随后可能马上重启进程，异步 apply 有丢失窗口
            if (!editor.commit()) Timber.w("Backup restore: commit prefs $name returned false")
        }
        // WebDAV 密码最后单独回写，经加密通道在本机 Keystore 重新加密
        UserDictPrefs.password = root.optString("webdavPassword", "")

        val db = AppDatabase.getInstance(appContext)
        val clipDb = ClipboardDatabase.getInstance(appContext)

        val clips = root.optJSONArray("clipboard") ?: JSONArray()
        val clipList = ArrayList<ClipboardRecord>(clips.length())
        for (i in 0 until clips.length()) {
            val o = clips.getJSONObject(i)
            clipList += ClipboardRecord(
                id = o.optLong("id", 0),
                text = o.optString("text"),
                timestamp = o.optLong("timestamp"),
                cloud = o.optBoolean("cloud"),
                deleted = o.optBoolean("deleted"),
                deletedAt = o.optLong("deletedAt"),
            )
        }
        clipDb.clipboardDao().deleteAllRaw()
        for (r in clipList) clipDb.clipboardDao().insert(r)

        val phrases = root.optJSONArray("phrases") ?: JSONArray()
        val phraseList = ArrayList<PhraseRecord>(phrases.length())
        for (i in 0 until phrases.length()) {
            val o = phrases.getJSONObject(i)
            phraseList += PhraseRecord(
                id = o.optLong("id", 0),
                text = o.optString("text"),
                label = o.optString("label"),
                createdAt = o.optLong("createdAt"),
            )
        }
        db.phraseDao().deleteAll()
        for (r in phraseList) db.phraseDao().insert(r)

        val prefers = root.optJSONArray("candidatePrefers") ?: JSONArray()
        val preferList = ArrayList<CandidatePrefer>(prefers.length())
        for (i in 0 until prefers.length()) {
            val o = prefers.getJSONObject(i)
            preferList += CandidatePrefer(
                text = o.optString("text"),
                count = o.optInt("count", 1),
                context = o.optString("context"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
        db.candidatePreferDao().deleteAll()
        if (preferList.isNotEmpty()) db.candidatePreferDao().insertAll(preferList)

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
        db.candidateSortingDao().deleteAll()
        if (sortingList.isNotEmpty()) db.candidateSortingDao().insertAll(sortingList)
    }

    private fun putPrefValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        obj: JSONObject,
    ) {
        when (obj.optString("t")) {
            "s" -> editor.putString(key, obj.optString("v"))
            "i" -> editor.putInt(key, obj.optInt("v"))
            "l" -> editor.putLong(key, obj.optLong("v"))
            "f" -> editor.putFloat(key, obj.optDouble("v").toFloat())
            "b" -> editor.putBoolean(key, obj.optBoolean("v"))
            "ss" -> {
                val arr = obj.optJSONArray("v") ?: JSONArray()
                editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
            }
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
