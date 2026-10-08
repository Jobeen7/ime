package com.jobeen.ime.base.speech

import android.content.Context
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音热词：把 Rime 用户词库里的常用词生成 sherpa-onnx 热词文件，
 * 供流式 transducer 识别器以 modified_beam_search 解码时做上下文
 * 偏置，让识别优先出用户自己的词。
 *
 * 口径（2026-10-08 审计定版，见 v1.1.2.7）：
 * - 模型是 BPE 建模单元：识别器必须显式 modelingUnit="bpe" 并给出
 *   bpe.vocab（由模型自带 bpe.model 导出的真实分数表，随包 assets
 *   携带，与当前模型 tokens.txt 的 md5 绑定校验）；modelingUnit 留
 *   空时引擎在热词编码处 _Exit 直接终止进程，不可捕获；
 * - 热词文件每行一个词、字间加空格（如「礼 拜 二」），这是 bpe
 *   编码能接收的写法；不加空格不报错但无效；
 * - 词源：全部用户词典导出（词\t拼音\t权重），按权重降序取前
 *   [MAX_HOTWORDS] 个；只收纯汉字词（2–8 字），且每个字都必须在
 *   模型字表里有「▁字」词片（可编码字集），否则整词剔除；
 * - 文件放 filesDir/speech/hotwords.txt，与模型目录分离；语音
 *   服务以文件戳记入引擎指纹，文件更新后下一次初始化自动重建
 *   识别器生效。
 *
 * 刷新时机：键盘输入结束时过期（> [STALE_MS]）才刷新（主进程）；
 * 用户词典导入完成后由词典页主动调用 [regenerate]。
 */
object SpeechHotwords {

    const val MAX_HOTWORDS = 300
    const val STALE_MS = 6 * 3600_000L
    private const val SESSION_NAME = "speech-hotwords"

    private val regenerating = AtomicBoolean(false)

    fun file(context: Context): File = File(File(context.filesDir, "speech"), "hotwords.txt")

    /**
     * 语音解码方式（诊断/回退开关）：v1.1.2.3 真机出现"带热词构造识别器后
     * 语音无反应"，三个子嫌疑（束搜索本身 / 热词文件 / 加速变体组合）无法
     * 在无日志条件下区分，故做成三档可选、逐档隔离。模式经文件传递（设置
     * 在主进程、识别在 :speech 进程，SharedPreferences 跨进程不可靠），
     * 识别器指纹包含模式，改档后下一次初始化自动重建。
     */
    enum class DecodeMode { GREEDY, BEAM, BEAM_HOTWORDS }

    fun modeFile(context: Context): File =
        File(File(context.filesDir, "speech"), "decode_mode.txt")

    fun readMode(context: Context): DecodeMode {
        val name = runCatching { modeFile(context).readText().trim() }.getOrNull()
        return DecodeMode.entries.firstOrNull { it.name == name } ?: DecodeMode.GREEDY
    }

    fun writeMode(context: Context, mode: DecodeMode) {
        val f = modeFile(context)
        f.parentFile?.mkdirs()
        runCatching { f.writeText(mode.name) }
    }

    // ---- 引擎构造状态与热词降级标记（服务写、设置读，跨进程文件传递） ----

    /**
     * 最近一次识别器构造结果（人话文本，设置页原样展示）：让"语音为什么
     * 没反应"在屏幕上可查，不再黑盒。服务每次构造后写入。
     */
    fun engineStatusFile(context: Context): File =
        File(File(context.filesDir, "speech"), "engine_status.txt")

    fun writeEngineStatus(context: Context, text: String) {
        val f = engineStatusFile(context)
        f.parentFile?.mkdirs()
        runCatching { f.writeText(text) }
    }

    fun readEngineStatus(context: Context): String? =
        runCatching {
            val f = engineStatusFile(context)
            if (f.isFile) f.readText().trim().ifEmpty { null } else null
        }.getOrNull()

    /**
     * 热词档构造失败的降级标记，内容为失败时的热词档指纹：指纹不变
     * （词表/模型/模式都没变）时直接以束搜索构造，不再反复撞失败；
     * 词表或模型更新后指纹变化，自动重试热词档。
     */
    private fun degradeFile(context: Context): File =
        File(File(context.filesDir, "speech"), "hotwords_degraded.txt")

    fun readDegradeStamp(context: Context): String? =
        runCatching {
            val f = degradeFile(context)
            if (f.isFile) f.readText().trim().ifEmpty { null } else null
        }.getOrNull()

    fun writeDegradeStamp(context: Context, stamp: String) {
        val f = degradeFile(context)
        f.parentFile?.mkdirs()
        runCatching { f.writeText(stamp) }
    }

    fun clearDegradeStamp(context: Context) {
        runCatching { degradeFile(context).delete() }
    }

    // ---- bpe.vocab 绑定（热词编码的词表必须与模型配套） ----

    /** 随包 bpe.vocab 对应的模型 tokens.txt md5（模型换代时同步更新）。 */
    const val EXPECTED_TOKENS_MD5 = "2836b40b48bdf307391191c1e50786ec"

    /** 随包 bpe.vocab 自身的 md5（assets 释放后校验，防文件损坏）。 */
    const val EXPECTED_VOCAB_MD5 = "44140fceaa5ec6426a52f2e169f9f16e"
    private const val VOCAB_ASSET = "speech/bpe.vocab"

    fun vocabFile(context: Context): File =
        File(File(context.filesDir, "speech"), "bpe.vocab")

    private fun md5Hex(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 确保与当前模型配套的 bpe.vocab 已释放到 filesDir 并返回其文件：
     * 模型 tokens.txt 的 md5 与随包词表不匹配（模型已换代）时返回
     * null——宁可不用热词，也不能拿错词表喂引擎。结果在进程内缓存。
     */
    @Volatile
    private var vocabChecked: File? = null

    @Volatile
    private var vocabCheckedDone = false

    fun ensureVocab(context: Context): File? {
        if (vocabCheckedDone) return vocabChecked
        synchronized(this) {
            if (vocabCheckedDone) return vocabChecked
            vocabChecked = runCatching {
                val tokens = File(com.jobeen.ime.data.App.speechModelDir, "tokens.txt")
                if (!tokens.isFile || md5Hex(tokens) != EXPECTED_TOKENS_MD5) {
                    null
                } else {
                    val target = vocabFile(context)
                    if (!target.isFile || md5Hex(target) != EXPECTED_VOCAB_MD5) {
                        target.parentFile?.mkdirs()
                        context.assets.open(VOCAB_ASSET).use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                    }
                    if (md5Hex(target) == EXPECTED_VOCAB_MD5) target else null
                }
            }.getOrNull()
            vocabCheckedDone = true
            return vocabChecked
        }
    }

    // ---- 升级清理 ----

    private fun legacyCleanupFile(context: Context): File =
        File(File(context.filesDir, "speech"), "migrated_bpe_v1.txt")

    /**
     * 一次性清理旧版遗留：v1.1.2.3–v1.1.2.6 的 hotwords.txt 是连写
     * 格式（bpe 下无效）、降级标记与引擎状态也可能是旧语义，升级到
     * bpe 路线时全部删掉重来（词表会由主进程按新格式重新生成）。
     */
    fun cleanupLegacyIfNeeded(context: Context) {
        val marker = legacyCleanupFile(context)
        if (marker.isFile) return
        runCatching {
            file(context).delete()
            degradeFile(context).delete()
            engineStatusFile(context).delete()
            marker.parentFile?.mkdirs()
            marker.writeText("done")
        }
    }

    /**
     * 按模型字表过滤热词：当前模型是 BPE 建模，汉字在 tokens.txt 里
     * 只有「▁字」词片、没有裸字——可编码字集 = 所有「▁X」词片去掉
     * 前缀后的单字 X（外加本身即单字的符号）。含字集外生字的词整词
     * 剔除。字集为空（模型未下载等）时不过滤。（v1.1.2.4 的旧写法只
     * 认裸单字符号，会把所有中文词滤光。）
     */
    fun filterByTokenChars(words: List<String>, tokenChars: Set<String>): List<String> {
        if (tokenChars.isEmpty()) return words
        return words.filter { w -> w.all { ch -> ch.toString() in tokenChars } }
    }

    /** 读模型目录 tokens.txt 的可编码字集；读不到返回空集。 */
    fun loadTokenChars(tokensFile: File): Set<String> {
        if (!tokensFile.isFile) return emptySet()
        return runCatching {
            tokensFile.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val sym = line.substringBeforeLast(' ').trim()
                    when {
                        // 「▁字」词片：去掉 ▁ 前缀后是单字 → 该字可编码
                        sym.length > 1 && sym[0] == '▁' -> {
                            val rest = sym.substring(1)
                            if (rest.codePointCount(0, rest.length) == 1) rest else null
                        }
                        sym.codePointCount(0, sym.length) == 1 -> sym
                        else -> null
                    }
                }
                .toSet()
        }.getOrDefault(emptySet())
    }

    data class DictEntry(val word: String, val weight: Int)

    /** 解析 librime 用户词典导出文本：每行 词\t拼音\t权重（权重可缺）。 */
    fun parseExport(text: String): List<DictEntry> {
        val out = ArrayList<DictEntry>()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val cols = line.split('\t')
            if (cols.size < 2) continue
            val word = cols[0].trim()
            if (word.isEmpty()) continue
            val weight = cols.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
            out.add(DictEntry(word, weight))
        }
        return out
    }

    /** 筛选热词：纯汉字 2–8 字、按权重降序、同词取最大权重、封顶。 */
    fun selectHotwords(entries: List<DictEntry>): List<String> {
        val best = HashMap<String, Int>()
        for (e in entries) {
            if (!isHotwordCandidate(e.word)) continue
            val cur = best[e.word]
            if (cur == null || e.weight > cur) best[e.word] = e.weight
        }
        return best.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_HOTWORDS)
            .map { it.key }
    }

    private fun isHotwordCandidate(word: String): Boolean {
        val len = word.codePointCount(0, word.length)
        if (len < 2 || len > 8) return false
        for (ch in word) {
            val cjk = ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' ||
                ch in '\uF900'..'\uFAFF'
            if (!cjk) return false
        }
        return true
    }

    /**
     * 热词文件内容：每行一个词、字间加空格（「礼 拜 二」）——bpe 热词
     * 编码只接收这种写法；连写不报错但完全无效（审计实测）。
     */
    fun render(words: List<String>): String =
        words.joinToString(separator = "\n", postfix = if (words.isEmpty()) "" else "\n") { word ->
            spaced(word)
        }

    /** 按码点拆字并以空格连接（扩展区汉字是代理对，不能按 Char 拆）。 */
    fun spaced(word: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (out.isNotEmpty()) out.append(' ')
            out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** 过期或缺失时刷新（语音引擎初始化路径调用，可阻塞但有界）。 */
    suspend fun regenerateIfStale(context: Context) {
        val f = file(context)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < STALE_MS) return
        regenerate(context)
    }

    /**
     * 从用户词典重新生成热词文件。经 RimeDaemon 临时会话导出全部
     * 用户词典（与词典页同路径），完成后销毁该会话。
     */
    suspend fun regenerate(context: Context): Boolean {
        if (!regenerating.compareAndSet(false, true)) return false
        try {
            val appContext = context.applicationContext
            val words = withTimeoutOrNull(60_000L) {
                val session = RimeDaemon.createSession(SESSION_NAME)
                try {
                    val entries = ArrayList<DictEntry>()
                    session.runOnReady {
                        val dicts = runCatching { getUserDictList() }.getOrDefault(emptyList())
                        for (dict in dicts) {
                            val tmp = File(
                                appContext.cacheDir,
                                "hotwords_export_${dict.hashCode()}.txt"
                            )
                            try {
                                val count = runCatching {
                                    exportUserDictLive(dict, tmp.absolutePath)
                                }.getOrDefault(-1)
                                if (count >= 0 && tmp.isFile) {
                                    entries.addAll(parseExport(tmp.readText(Charsets.UTF_8)))
                                }
                            } finally {
                                tmp.delete()
                            }
                        }
                    }
                    selectHotwords(entries)
                } finally {
                    runCatching { RimeDaemon.destroySession(SESSION_NAME) }
                }
            } ?: return false
            // 按模型字表剔除含生字的词（模型目录主进程可直读）
            val tokenChars = loadTokenChars(
                File(com.jobeen.ime.data.App.speechModelDir, "tokens.txt")
            )
            val filtered = filterByTokenChars(words, tokenChars)
            if (filtered.isEmpty()) return false
            val target = file(appContext)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(render(filtered), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(render(filtered), Charsets.UTF_8)
                tmp.delete()
            }
            Timber.i("Speech hotwords regenerated: ${filtered.size} words")
            return true
        } catch (t: Throwable) {
            Timber.w(t, "Speech hotwords regeneration failed")
            return false
        } finally {
            regenerating.set(false)
        }
    }
}
