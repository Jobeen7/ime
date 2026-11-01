package com.jobeen.ime.skin.symbolbar

/**
 * YAML 子集解析器（符号栏配置专用；应用内无 YAML 库、禁新增依赖）。
 *
 * 支持本配置实际用到的形态，其余一律报语法错误（调用方提示
 * 「符号栏配置无效」，绝不半猜半解析）：
 * - 块映射 `key: value` / `key:`（嵌套块缩进大于父级即可，不限 2 格）
 * - 块序列 `- item` / `- key: value`（首项行内起映射，后续项对齐）
 * - 流映射 `{k: v, ...}`、流序列 `[a, b]`（单行）
 * - 标量：无引号（行内注释 `#` 须前有空白；值含 `: `、`[`、`]`、
 *   `{`、`}`、`,` 时须加引号，否则报语法错误）、单引号（`''` 表
 *   示一个单引号，无转义）、双引号（标准转义，见下）
 * - 行注释（`#` 起首的行）与 `---` / `...` 文档标记行跳过
 * - Tab 缩进、锚点／别名／标签（`&`、`*`、`!`）、块标量（`|`、
 *   `>`）、重复键、行内映射未闭合均报语法错误
 *
 * 双引号转义集：0 a b t n v f r e " \ /，以及 \xXX、\uXXXX；
 * 未知转义报语法错误。单引号内反斜杠无特殊含义（LaTeX 配置
 * 写单引号正是为此：`'\frac'` 不会被吃掉 `\f`）。
 *
 * 纯函数，可直接单测；数值／布尔由配置层从 [YamlScalar] 字符串
 * 严格解释，本层不做类型推断。
 */
sealed class YamlNode {
    /** 空值（`key:` 后无嵌套块）。 */
    data object Null : YamlNode()

    /** 标量原文（已去引号、已转义）。 */
    data class Scalar(val value: String) : YamlNode()

    /** 映射（保序；重复键为语法错误）。 */
    data class Map(val entries: LinkedHashMap<String, YamlNode>) : YamlNode()

    /** 序列。 */
    data class List(val items: kotlin.collections.List<YamlNode>) : YamlNode()
}

object SymbolBarYaml {

    /** 解析失败返回 null（调用方按「配置无效」处理）。 */
    fun parse(text: String): YamlNode? {
        return try {
            val lines = preprocess(text) ?: return null
            if (lines.isEmpty()) return null
            val parser = Parser(lines)
            val node = parser.parseNode(0) ?: return null
            if (parser.pos != lines.size) return null
            node
        } catch (e: SyntaxError) {
            null
        }
    }

    private class SyntaxError : Exception()

    private data class Line(val indent: Int, val content: String)

    /** 预处理：去 \r、分行、去注释空行、禁 Tab 缩进。 */
    private fun preprocess(text: String): kotlin.collections.List<Line>? {
        val out = ArrayList<Line>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.contains('\t')) {
                // Tab 只能出现在引号内；缩进含 Tab 即语法错误。
                // 简化判定：行首空白含 Tab 直接拒绝（配置不写此形态）。
                val leading = line.takeWhile { it == ' ' || it == '\t' }
                if (leading.contains('\t')) return null
            }
            val indent = line.takeWhile { it == ' ' }.length
            val stripped = stripComment(line.substring(indent))
            val content = stripped.trimEnd()
            if (content.isEmpty()) continue
            if (content == "---" || content == "...") continue
            out += Line(indent, content)
        }
        return out
    }

    /** 去行内注释：引号外的 `#`（行首或前有空白）起截断。 */
    private fun stripComment(content: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                inSingle -> {
                    if (c == '\'') {
                        if (i + 1 < content.length && content[i + 1] == '\'') {
                            i += 2
                            continue
                        }
                        inSingle = false
                    }
                }
                inDouble -> {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') inDouble = false
                }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '#' && (i == 0 || content[i - 1] == ' ' || content[i - 1] == '\t') -> {
                    return content.substring(0, i)
                }
            }
            i++
        }
        return content
    }

    private class Parser(private val lines: kotlin.collections.List<Line>) {
        var pos = 0

        /** 解析以当前行为起点的块节点（要求缩进 >= [minIndent]）。 */
        fun parseNode(minIndent: Int): YamlNode? {
            if (pos >= lines.size) return null
            val line = lines[pos]
            if (line.indent < minIndent) return null
            return if (isSequenceLine(line.content)) {
                parseSequence(line.indent)
            } else if (mappingKey(line.content) != null) {
                parseMapping(line.indent, null)
            } else {
                throw SyntaxError()
            }
        }

        private fun isSequenceLine(content: String): Boolean =
            content == "-" || content.startsWith("- ")

        /** 映射键解析：返回 (键, 冒号后内容)，非映射行返回 null。 */
        private fun mappingKey(content: String): Pair<String, String>? {
            if (content.startsWith("\"") || content.startsWith("'")) {
                val quote = content[0]
                val end = scanQuoted(content, 0) ?: return null
                val key = unquote(content.substring(0, end + 1)) ?: return null
                val rest = content.substring(end + 1).trimStart()
                if (!rest.startsWith(":")) return null
                val after = rest.substring(1)
                if (after.isNotEmpty() && !after.startsWith(" ")) return null
                return key to after.trim()
            }
            val colon = findPlainColon(content) ?: return null
            val key = content.substring(0, colon).trim()
            if (key.isEmpty()) return null
            return key to content.substring(colon + 1).trim()
        }

        /** 无引号键的冒号位置（冒号后须为空或空白）。 */
        private fun findPlainColon(content: String): Int? {
            var i = 0
            while (i < content.length) {
                val c = content[i]
                if (c == ':' && (i + 1 == content.length || content[i + 1] == ' ')) {
                    return i
                }
                if (c == '[' || c == '{' || c == ',' || c == '#') return null
                i++
            }
            return null
        }

        private fun parseMapping(
            indent: Int,
            firstEntry: String?,
        ): YamlNode.Map {
            val entries = LinkedHashMap<String, YamlNode>()
            var pendingFirst = firstEntry
            while (true) {
                val content: String
                if (pendingFirst != null) {
                    content = pendingFirst
                    pendingFirst = null
                } else {
                    if (pos >= lines.size) break
                    val line = lines[pos]
                    if (line.indent != indent) break
                    if (isSequenceLine(line.content)) break
                    content = line.content
                    pos++
                }
                val (key, after) = mappingKey(content) ?: throw SyntaxError()
                if (entries.containsKey(key)) throw SyntaxError()
                entries[key] = parseMappingValue(indent, after)
            }
            return YamlNode.Map(entries)
        }

        /** 映射值：行内值 / 嵌套块 / 空。 */
        private fun parseMappingValue(parentIndent: Int, after: String): YamlNode {
            if (after.isNotEmpty()) return parseInline(after)
            if (pos >= lines.size) return YamlNode.Null
            val next = lines[pos]
            if (next.indent <= parentIndent) return YamlNode.Null
            return parseNode(next.indent) ?: YamlNode.Null
        }

        private fun parseSequence(indent: Int): YamlNode.List {
            val items = ArrayList<YamlNode>()
            while (pos < lines.size) {
                val line = lines[pos]
                if (line.indent != indent || !isSequenceLine(line.content)) break
                pos++
                val rest = if (line.content == "-") "" else line.content.substring(2)
                val trimmed = rest.trimStart()
                when {
                    trimmed.isEmpty() -> {
                        if (pos < lines.size && lines[pos].indent > indent) {
                            items += parseNode(lines[pos].indent) ?: YamlNode.Null
                        } else {
                            items += YamlNode.Null
                        }
                    }
                    mappingKey(trimmed) != null -> {
                        // 行内起映射：后续项对齐到 "- " 之后（缩进 +2）。
                        items += parseMapping(indent + 2, trimmed)
                    }
                    isSequenceLine(trimmed) -> throw SyntaxError()
                    else -> items += parseInline(trimmed)
                }
            }
            return YamlNode.List(items)
        }

        /** 行内值：流映射／流序列／引号标量／无引号标量。 */
        private fun parseInline(content: String): YamlNode {
            val text = content.trim()
            if (text.isEmpty()) return YamlNode.Null
            return when (text[0]) {
                '{' -> parseFlowMap(text)
                '[' -> parseFlowList(text)
                '"', '\'' -> {
                    val end = scanQuoted(text, 0) ?: throw SyntaxError()
                    if (text.substring(end + 1).trim().isNotEmpty()) throw SyntaxError()
                    YamlNode.Scalar(unquote(text.substring(0, end + 1)) ?: throw SyntaxError())
                }
                '|', '>', '!', '&', '*', '%', '@', '`' -> throw SyntaxError()
                else -> {
                    if (text.contains(": ") || text.contains(" #")) throw SyntaxError()
                    if (text.any { it == '[' || it == ']' || it == '{' || it == '}' || it == ',' }) {
                        throw SyntaxError()
                    }
                    YamlNode.Scalar(text)
                }
            }
        }

        private fun parseFlowMap(text: String): YamlNode.Map {
            if (!text.endsWith("}")) throw SyntaxError()
            val inner = text.substring(1, text.length - 1).trim()
            val entries = LinkedHashMap<String, YamlNode>()
            if (inner.isEmpty()) return YamlNode.Map(entries)
            for (part in splitFlow(inner)) {
                val (key, after) = mappingKey(part.trim()) ?: throw SyntaxError()
                if (entries.containsKey(key)) throw SyntaxError()
                entries[key] = if (after.isEmpty()) YamlNode.Null else parseInline(after)
            }
            return YamlNode.Map(entries)
        }

        private fun parseFlowList(text: String): YamlNode.List {
            if (!text.endsWith("]")) throw SyntaxError()
            val inner = text.substring(1, text.length - 1).trim()
            if (inner.isEmpty()) return YamlNode.List(emptyList())
            return YamlNode.List(splitFlow(inner).map { parseInline(it.trim()) })
        }

        /** 流内按顶层逗号切分（引号与嵌套流内逗号不切）。 */
        private fun splitFlow(inner: String): kotlin.collections.List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var inSingle = false
            var inDouble = false
            var start = 0
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                when {
                    inSingle -> {
                        if (c == '\'') {
                            if (i + 1 < inner.length && inner[i + 1] == '\'') i++ else inSingle = false
                        }
                    }
                    inDouble -> {
                        if (c == '\\') i++ else if (c == '"') inDouble = false
                    }
                    c == '\'' -> inSingle = true
                    c == '"' -> inDouble = true
                    c == '{' || c == '[' -> depth++
                    c == '}' || c == ']' -> depth--
                    c == ',' && depth == 0 -> {
                        parts += inner.substring(start, i)
                        start = i + 1
                    }
                }
                i++
            }
            if (inSingle || inDouble || depth != 0) throw SyntaxError()
            parts += inner.substring(start)
            return parts
        }

        /** 引号起止扫描：返回闭合引号下标（未闭合返回 null）。 */
        private fun scanQuoted(text: String, start: Int): Int? {
            val quote = text[start]
            var i = start + 1
            while (i < text.length) {
                val c = text[i]
                if (quote == '\'' && c == '\'') {
                    if (i + 1 < text.length && text[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    return i
                }
                if (quote == '"' && c == '\\') {
                    i += 2
                    continue
                }
                if (quote == '"' && c == '"') return i
                i++
            }
            return null
        }

        /** 去引号并转义（双引号未知转义返回 null）。 */
        private fun unquote(quoted: String): String? {
            val quote = quoted[0]
            val inner = quoted.substring(1, quoted.length - 1)
            if (quote == '\'') return inner.replace("''", "'")
            val out = StringBuilder()
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c != '\\') {
                    out.append(c)
                    i++
                    continue
                }
                if (i + 1 >= inner.length) return null
                val e = inner[i + 1]
                when (e) {
                    '0' -> out.append('\u0000')
                    'a' -> out.append('\u0007')
                    'b' -> out.append('\b')
                    't', '\t' -> out.append('\t')
                    'n' -> out.append('\n')
                    'v' -> out.append('\u000B')
                    'f' -> out.append('\u000C')
                    'r' -> out.append('\r')
                    'e' -> out.append('\u001B')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'x' -> {
                        val hex = inner.substring(i + 2, minOf(i + 4, inner.length))
                        if (hex.length != 2) return null
                        val code = hex.toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 2
                    }
                    'u' -> {
                        val hex = inner.substring(i + 2, minOf(i + 6, inner.length))
                        if (hex.length != 4) return null
                        val code = hex.toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 4
                    }
                    else -> return null
                }
                i += 2
            }
            return out.toString()
        }
    }
}
