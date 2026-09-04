package com.ninthsoft.ime.base.util

import java.io.InputStream

/**
 * 简→繁转换（等价于 OpenCC s2t）。
 *
 * 数据源与 rime-wanxiang 同源：assets 内置的 `data/STCharacters.txt`（单字映射）
 * + `data/STPhrases.txt`（短语映射），不依赖 resource.zip 解压。转换采用 FMM（正向最大匹配）：
 * 优先匹配最长短语，次选单字映射，未命中原样保留。用于替换 opencc4j 的 `ZhConverterUtil.toTraditional`。
 */
object TraditionalConverter {
    private val charMap: Map<Int, String> by lazy { loadCharMap() }
    private val phraseMap: Map<String, String> by lazy { loadPhraseMap() }
    private val maxPhraseLen: Int by lazy {
        phraseMap.keys.maxOfOrNull { it.codePointCount(0, it.length) } ?: 1
    }

    // 反向（繁→简）：由 s2t 词库推导，无需额外数据文件。
    private val t2sCharMap: Map<Int, String> by lazy {
        charMap.entries.associate { (s, t) ->
            t.codePointAt(0) to String(Character.toChars(s))
        }
    }
    private val t2sPhraseMap: Map<String, String> by lazy {
        phraseMap.entries.associate { (s, t) -> t to s }
    }
    private val maxT2sPhraseLen: Int by lazy {
        t2sPhraseMap.keys.maxOfOrNull { it.codePointCount(0, it.length) } ?: 1
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

    fun toTraditional(text: String): String = convert(text, phraseMap, charMap, maxPhraseLen)

    fun toSimplified(text: String): String =
        convert(text, t2sPhraseMap, t2sCharMap, maxT2sPhraseLen)

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