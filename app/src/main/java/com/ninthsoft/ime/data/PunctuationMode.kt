package com.ninthsoft.ime.data

sealed class PunctuationMode {
    companion object {
        fun from(punctuation: String): PunctuationMode {
            return when (punctuation) {
                "full-width" -> FullWidth
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
