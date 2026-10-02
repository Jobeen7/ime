package com.jobeen.ime.base.util

import java.io.InputStream

/**
 * 简→繁转换（等价于 OpenCC s2t）。
 *
 * 数据源与 rime-wanxiang 同源：assets 内置的 `data/STCharacters.txt`（单字映射）
 * + `data/STPhrases.txt`（短语映射），不依赖 resource.zip 解压。转换采用 FMM（正向最大匹配）：
 * 优先匹配最长短语，次选单字映射，未命中原样保留。用于替换 opencc4j 的 `ZhConverterUtil.toTraditional`。
 *
 * 内存：四张 Map（正/反向 × 单字/短语）全量加载后约数 MB。改为可释放的懒加载持有者，
 * 系统内存紧张（onTrimMemory）时可整体丢弃，下次转换再按需重建，避免词表永久常驻。
 */
object TraditionalConverter {

    /** 可释放的懒加载持有者：双重检查锁定加载，clear 后下次访问重新加载。 */
    private class Cache<T>(private val loader: () -> T) {
        @Volatile
        private var value: T? = null

        fun get(): T = value ?: synchronized(this) {
            value ?: loader().also { value = it }
        }

        fun clear() {
            synchronized(this) { value = null }
        }
    }

    private val charMapCache = Cache { loadCharMap() }
    private val phraseMapCache = Cache { loadPhraseMap() }
    private val maxPhraseLenCache = Cache {
        phraseMapCache.get().keys.maxOfOrNull { it.codePointCount(0, it.length) } ?: 1
    }

    // 反向（繁→简）：由 s2t 词库推导，无需额外数据文件。
    private val t2sCharMapCache = Cache {
        charMapCache.get().entries.associate { (s, t) ->
            t.codePointAt(0) to String(Character.toChars(s))
        }
    }
    private val t2sPhraseMapCache = Cache {
        phraseMapCache.get().entries.associate { (s, t) -> t to s }
    }
    private val maxT2sPhraseLenCache = Cache {
        t2sPhraseMapCache.get().keys.maxOfOrNull { it.codePointCount(0, it.length) } ?: 1
    }

    /** 内存紧张时调用：丢弃全部转换词表，下次转换按需重建。 */
    fun releaseCaches() {
        charMapCache.clear()
        phraseMapCache.clear()
        maxPhraseLenCache.clear()
        t2sCharMapCache.clear()
        t2sPhraseMapCache.clear()
        maxT2sPhraseLenCache.clear()
    }

    private const val CHAR_FILE = "data/STCharacters.txt"
    private const val PHRASE_FILE = "data/STPhrases.txt"
    private fun loadCharMap(): Map<Int, String> {
        val map = HashMap<Int, String>()
        runCatching {
            openAsset(CHAR_FILE).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val fields = line.replace("\\t", "\t").split('\t')
                    if (fields.size < 2) continue
                    val src = fields[0]
                    if (src.codePointCount(0, src.length) != 1) continue
                    map[src.codePointAt(0)] = fields[1]
                }
            }
        }
        return map
    }

    private fun loadPhraseMap(): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            openAsset(PHRASE_FILE).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val fields = line.replace("\\t", "\t").split('\t')
                    if (fields.size < 2) continue
                    map[fields[0]] = fields[1]
                }
            }
        }
        return map
    }

    private fun openAsset(path: String): InputStream = appContext.assets.open(path)

    fun toTraditional(text: String): String =
        convert(text, phraseMapCache.get(), charMapCache.get(), maxPhraseLenCache.get())

    fun toSimplified(text: String): String =
        convert(text, t2sPhraseMapCache.get(), t2sCharMapCache.get(), maxT2sPhraseLenCache.get())

    private fun convert(
        text: String,
        phrases: Map<String, String>,
        chars: Map<Int, String>,
        maxPhraseLen: Int,
    ): String {
        if (text.isEmpty()) return text
        val cps = text.codePoints().toArray()
        val n = cps.size
        val out = StringBuilder(text.length + text.length / 4)
        var i = 0
        while (i < n) {
            var consumed = 1
            var mapped: String? = null

            val maxLen = minOf(maxPhraseLen, n - i)
            if (maxLen >= 2) {
                for (len in maxLen downTo 2) {
                    val key = String(cps, i, len)
                    val value = phrases[key]
                    if (value != null) {
                        mapped = value
                        consumed = len
                        break
                    }
                }
            }

            if (mapped == null) {
                mapped = chars[cps[i]]
                if (mapped != null) {
                    out.append(mapped)
                } else {
                    out.appendCodePoint(cps[i])
                }
            } else {
                out.append(mapped)
            }
            i += consumed
        }
        return out.toString()
    }
}
