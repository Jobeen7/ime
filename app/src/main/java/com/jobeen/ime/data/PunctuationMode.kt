package com.jobeen.ime.data

sealed class PunctuationMode {
    companion object {
        fun from(punctuation: String): PunctuationMode {
            return when (punctuation) {
                "FullWidth" -> FullWidth
                else -> HalfWidth
            }
        }
    }

    data object FullWidth : PunctuationMode()
    data object HalfWidth : PunctuationMode()

    open class CharacterSet {
        companion object {
            val half2full = mapOf(
                "~" to "～",
                "!" to "！",
                "@" to "＠",
                "#" to "＃",
                "$" to "＄",
                "%" to "％",
                "^" to "＾",
                "&" to "＆",
                "*" to "＊",
                "(" to "（",
                ")" to "）",
                ":" to "：",
                ";" to "；",
                "," to "，",
                "." to "。",
                "?" to "？",
                "/" to "／",
                "\"" to "＂",
                "'" to "＇",
                "[" to "［",
                "]" to "］",
                "{" to "｛",
                "}" to "｝",
                "+" to "＋",
                "-" to "－",
                "=" to "＝",
                "<" to "＜",
                ">" to "＞",
                "_" to "＿",
                "|" to "｜",
                "\\" to "＼",
                // 「、」(U+3001) 的单字符半角形是「､」(U+FF64
                // HALFWIDTH IDEOGRAPHIC COMMA，兼容分解 <narrow>
                // 3001，NFKC→U+3001；WO33 核实：交付门 .11 第 9 条
                // “顿号无单字符半角形”是错的）。full2half 由本表
                // 反推，故一半角、半角两向同补。「。」不补：它已有
                // ASCII 半角等价形 "."（本表既有映射），与「、」
                // 无 ASCII 等价形不同。
                "､" to "、",
            )
            val full2half = half2full.map { it.value to it.key }.toMap()

            fun halfWidth(f: String): String {
                return convert(f, full2half)
            }

            fun fullWidth(h: String): String {
                return convert(h, half2full)
            }

            private fun convert(text: String, characterSet: Map<String, String>): String {
                return characterSet[text] ?: text
            }
        }
    }
}
