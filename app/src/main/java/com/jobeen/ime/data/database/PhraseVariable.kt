// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

/**
 * 常用语变量（WO52 6.1）：与脚本 `ScriptVariable` 同形——key/value/isSecure。
 *
 * 注意：脚本侧的 `ScriptVariable` 实体属 8.1（QuickJS 脚本引擎，三段），
 * 本树此刻尚无该类；此处按单子给出的三字段形状独立定义，8.1 落地后
 * 两边形状一致（key/value/isSecure），不再另行对齐。
 *
 * 存法：以 JSON 数组串存在 `PhraseRecord.variables` 列（TEXT），
 * 如 `[{"key":"name","value":"张三","isSecure":false}]`；空串＝无变量。
 * isSecure 只作展示口径（编辑页打码显示），不另做加密——与单子一致，
 * 变量值本身随常用语一起备份（与对端 `backup_plaintext_warning`
 * 点名的明文变量同口径，不另造加密机制）。
 *
 * 编解码手写（不用 org.json）：android.jar 的 org.json 在主机单测里
 * 是桩（方法未 mock 即抛），手写编解码让同一份代码在真机与主机单测
 * 下行为一致；形状固定为扁平对象数组，解析器只认该形状，畸形串
 * 一律按无变量处理（损坏数据不让整表读取抛错，与 Converters
 * 的容错口径一致）。
 */
data class PhraseVariable(
    val key: String,
    val value: String,
    val isSecure: Boolean = false,
)

object PhraseVariables {

    fun encode(variables: List<PhraseVariable>): String {
        val items = variables.filter { it.key.trim().isNotEmpty() }
        if (items.isEmpty()) return ""
        val sb = StringBuilder("[")
        items.forEachIndexed { index, v ->
            if (index > 0) sb.append(',')
            sb.append("{\"key\":")
            appendJsonString(sb, v.key.trim())
            sb.append(",\"value\":")
            appendJsonString(sb, v.value)
            sb.append(",\"isSecure\":")
            sb.append(if (v.isSecure) "true" else "false")
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(raw: String): List<PhraseVariable> {
        if (raw.isBlank()) return emptyList()
        return runCatching { JsonParser(raw).parseVariables() }.getOrDefault(emptyList())
    }

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** 仅够解析 `[{"key": "...", "value": "...", "isSecure": bool}]` 的小解析器。 */
    private class JsonParser(private val s: String) {
        private var i = 0

        fun parseVariables(): List<PhraseVariable> {
            skipWs()
            if (!consume('[')) return emptyList()
            val out = ArrayList<PhraseVariable>()
            skipWs()
            if (consume(']')) return out
            while (true) {
                skipWs()
                val obj = parseObject() ?: return emptyList()
                val key = obj["key"]?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    out += PhraseVariable(key, obj["value"].orEmpty(), obj["isSecure"] == "true")
                }
                skipWs()
                if (consume(',')) continue
                if (consume(']')) return out
                return emptyList()
            }
        }

        private fun parseObject(): Map<String, String>? {
            if (!consume('{')) return null
            val map = HashMap<String, String>()
            skipWs()
            if (consume('}')) return map
            while (true) {
                skipWs()
                val key = parseString() ?: return null
                skipWs()
                if (!consume(':')) return null
                skipWs()
                val value = parseValue() ?: return null
                map[key] = value
                skipWs()
                if (consume(',')) continue
                if (consume('}')) return map
                return null
            }
        }

        private fun parseValue(): String? {
            if (i >= s.length) return null
            return when (s[i]) {
                '"' -> parseString()
                't' -> if (s.startsWith("true", i)) { i += 4; "true" } else null
                'f' -> if (s.startsWith("false", i)) { i += 5; "false" } else null
                'n' -> if (s.startsWith("null", i)) { i += 4; "" } else null
                else -> {
                    // 数字等其他值：本形状不出现，按到分隔符为止跳过
                    val start = i
                    while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++
                    if (i == start) null else s.substring(start, i).trim()
                }
            }
        }

        private fun parseString(): String? {
            if (i >= s.length || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) return null
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                                i += 4
                                sb.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun consume(c: Char): Boolean {
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }
    }
}
