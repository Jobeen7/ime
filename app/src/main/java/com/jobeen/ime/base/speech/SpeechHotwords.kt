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
 * 口径：
 * - 词源：全部用户词典导出（词\t拼音\t权重），按权重降序取前
 *   [MAX_HOTWORDS] 个；
 * - 只收纯汉字词（2–8 字）：保证能按字切分对齐模型的 cjkchar
 *   建模单元；含字母/数字/符号的词跳过，避免热词编码失败连累
 *   识别器创建；
 * - 文件放 filesDir/speech/hotwords.txt，与模型目录分离；语音
 *   服务以文件戳记入引擎指纹，文件更新后下一次初始化自动重建
 *   识别器生效。
 *
 * 刷新时机：语音引擎初始化时过期（> [STALE_MS]）才刷新；用户
 * 词典导入完成后由词典页主动调用 [regenerate]。
 * 全部失败路径静默降级（无热词文件时识别器回退 greedy 解码）。
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

    /**
     * 按模型字表过滤热词：tokens.txt 每行「符号 id」，取其中单字符号为
     * 可编码字集；含字集外生字的词整词剔除——避免热词编码在 native 侧
     * 失败连累识别器构造。字集为空（模型未下载等）时不过滤。
     */
    fun filterByTokenChars(words: List<String>, tokenChars: Set<String>): List<String> {
        if (tokenChars.isEmpty()) return words
        return words.filter { w -> w.all { ch -> ch.toString() in tokenChars } }
    }

    /** 读模型目录 tokens.txt 的单字符号集；读不到返回空集。 */
    fun loadTokenChars(tokensFile: File): Set<String> {
        if (!tokensFile.isFile) return emptySet()
        return runCatching {
            tokensFile.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val sym = line.substringBeforeLast(' ').trim()
                    if (sym.codePointCount(0, sym.length) == 1) sym else null
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

    fun render(words: List<String>): String =
        words.joinToString(separator = "\n", postfix = if (words.isEmpty()) "" else "\n")

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
