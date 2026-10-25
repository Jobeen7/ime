package com.jobeen.ime.data.manager

import java.io.File

/**
 * 磁盘上的方案（schema）元数据。
 *
 * 对应 Rime schema yaml 文件 `schema:` 段内的关键字段；
 * 文件缺失的字段一律以空字符串表示，不会为 null。
 */
data class DiskSchema(
    val id: String,
    val name: String,
    val layout: String,
    val punctuation: String,
    val kind: String
)

/**
 * 把单个 schema yaml 文件解析为 [DiskSchema]。
 *
 * 契约：
 * - 文件不是普通文件或不可读时返回 null；读行过程中发生异常同样返回 null，不向外抛。
 * - 逐行扫描：忽略 `schema:` 段之前的所有行；找到顶格（行首无空白）的 `schema:` 行后进入段内，
 *   读取其后所有缩进大于 0 的 `key: value` 行，遇到顶格非空行即停止；空行跳过、不停止。
 * - value 先去除首尾空白，再脱去一层成对的单引号或双引号（仅有一侧引号则不脱）。
 * - 只采集 schema_id、name、layout、punctuation、kind 五个键，其余键忽略；缺失的键按空字符串处理。
 * - id 取 schema_id；schema_id 为空时取文件名去掉 `.schema.yaml` 后缀；结果 id 仍为空时返回 null。
 * - 整个文件找不到顶格 `schema:` 段时返回 null（视为解析失败）。
 */
fun parseSchemaFile(file: File): DiskSchema? {
    if (!file.isFile || !file.canRead()) return null

    val lines = try {
        file.readLines()
    } catch (_: Exception) {
        return null
    }

    var inSchemaSection = false
    var schemaId = ""
    var name = ""
    var layout = ""
    var punctuation = ""
    var kind = ""

    for (line in lines) {
        val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
        val topLevel = firstNonWs == 0

        if (!inSchemaSection) {
            if (topLevel && line.trim() == "schema:") {
                inSchemaSection = true
            }
            continue
        }

        // 段内：顶格非空行标志段结束；空行跳过，不算结束。
        if (line.isBlank()) continue
        if (topLevel) break

        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) continue
        val key = line.substring(0, colonIndex).trim()
        val value = line.substring(colonIndex + 1).trim().unquote()
        when (key) {
            "schema_id" -> schemaId = value
            "name" -> name = value
            "layout" -> layout = value
            "punctuation" -> punctuation = value
            "kind" -> kind = value
        }
    }

    if (!inSchemaSection) return null

    val id = schemaId.ifEmpty { file.name.removeSuffix(".schema.yaml") }
    if (id.isEmpty()) return null
    return DiskSchema(id, name, layout, punctuation, kind)
}

/**
 * 扫描磁盘上的方案文件，得到全部可用方案。
 *
 * 契约：
 * - 只读 [sharedDir] 与 [userDir] 两个目录根下、扩展名为 `*.schema.yaml` 的普通文件；
 *   绝不递归子目录，根下的旧子目录、build/ 等一律不进列表。
 * - 目录不存在、不是目录或不可读时，该侧视为空，不抛异常；列目录或读文件时的安全异常同样被吞掉。
 * - 先收集 shared 侧，再收集 user 侧；同 id 时 user 侧覆盖 shared 侧。
 * - 解析失败（返回 null）的文件跳过。
 * - 返回结果按 id 升序排列；两侧都无方案时返回空列表。
 */
fun discoverSchemes(userDir: File, sharedDir: File): List<DiskSchema> {
    val byId = LinkedHashMap<String, DiskSchema>()
    collectSchemas(sharedDir, byId)
    collectSchemas(userDir, byId)
    return byId.values.sortedBy { it.id }
}

/** 把 [dir] 根下的 `*.schema.yaml` 普通文件解析后写入 [into]，同 id 直接覆盖。 */
private fun collectSchemas(dir: File, into: MutableMap<String, DiskSchema>) {
    if (!dir.isDirectory || !dir.canRead()) return
    val files = try {
        dir.listFiles()
    } catch (_: Exception) {
        return
    } ?: return

    for (file in files) {
        if (!file.isFile) continue
        if (!file.name.endsWith(".schema.yaml")) continue
        val schema = try {
            parseSchemaFile(file)
        } catch (_: Exception) {
            null
        } ?: continue
        into[schema.id] = schema
    }
}

/**
 * 求启用列表与磁盘方案的交集。
 *
 * 契约：
 * - 返回 [prefsIds] 中同时出现在 [diskIds] 里的 id，保持 [prefsIds] 的原始顺序并去重。
 * - 若交集为空，返回 [diskIds] 全体（调用方负责在触发兜底时把结果写回存储）。
 */
fun resolveEnabledIds(prefsIds: List<String>, diskIds: List<String>): List<String> {
    val diskSet = diskIds.toSet()
    val enabled = prefsIds.filter { it in diskSet }.distinct()
    return if (enabled.isEmpty()) diskIds else enabled
}

/** 脱去一层成对的单引号或双引号；长度不足 2 或两侧引号不匹配时原样返回。 */
private fun String.unquote(): String {
    if (length >= 2 && ((startsWith("\"") && endsWith("\"")) || (startsWith("'") && endsWith("'")))) {
        return substring(1, length - 1)
    }
    return this
}
