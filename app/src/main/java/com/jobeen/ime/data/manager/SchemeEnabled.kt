package com.jobeen.ime.data.manager

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.io.IOException

object SchemeEnabled {
    private val ITEM_PATTERN = Regex("[A-Za-z0-9_.\\-]+")

    /** 启用表 ID 白名单：全匹配 [A-Za-z0-9_.\-]+ 且不等于 "."、".."。 */
    fun isValidSchemaId(id: String): Boolean =
        ITEM_PATTERN.matches(id) && id != "." && id != ".."

    /**
     * 根据传入的 ID 列表生成或修改 YAML 文本中的 schema_list 序列。
     *
     * 序列项一律写 Rime 标准 map 格式 `    - schema: <id>`（裸字符串项
     * 不是合法 schema_list 项：librime 部署时读不到方案、新目录编不出
     * 词库，表现为组字有码无候选；旧版写出的裸字符串文件由读盘侧
     * 双兼容解析、由 normalizeCustomFileToMapFormat 规范化）。
     * - 过滤 ID：仅保留全匹配 [A-Za-z0-9_.\-]+ 且不等于 "."、".." 的项。
     * - 若 existingText 为 null 或空白，返回以 "# Written by Jime scheme settings." 开头的全新文本。
     * - 若无 patch 段，在末尾追加 patch: 及其下的 schema_list: 和序列项。
     * - 若有 patch 段但无 schema_list 行，在 patch: 行之后、下一顶格行之前插入。
     * - 若有 schema_list 行，丢弃冒号后的非空白内容，替换其后解析侧序列
     *   段内的全部项：扫描在解析侧的终结行处停止（首个非空行、非注释行
     *   且缩进 <= schema_list 缩进的行，或文件尾），其间空行、注释行、
     *   全角空格行等解析侧跳过的行只跳过不终结，段内旧项无论隔着多少
     *   跳过行都不复活；跳过行本身原样保留，后续键不受影响。
     * - 对互异的有效 ID，patch 后经解析侧读到的结果恰好等于传入的
     *   过滤后 ID 列表，顺序与传入一致。
     * - 返回文本始终以单个换行符结尾。
     */
    fun patchSchemaListYaml(existingText: String?, ids: List<String>): String {
        val validIds = ids.filter { id -> isValidSchemaId(id) }

        val newLines = validIds.map { "    - schema: $it" }
        
        val text = existingText
        if (text.isNullOrBlank()) {
            val builder = StringBuilder()
            builder.append("# Written by Jime scheme settings.\n")
            builder.append("patch:\n")
            builder.append("  schema_list:\n")
            newLines.forEach { builder.append("$it\n") }
            return builder.toString()
        }
        
        val lines = text.lines().toMutableList()
        
        fun isTopLevel(line: String): Boolean {
            if (line.isEmpty()) return false
            if (line.firstOrNull()?.isWhitespace() == true) return false
            val trimmed = line.trim()
            return !trimmed.startsWith("#")
        }
        
        var patchIdx = -1
        for (i in lines.indices) {
            if (isTopLevel(lines[i]) && lines[i].trim() == "patch:") {
                patchIdx = i
                break
            }
        }
        
        if (patchIdx == -1) {
            if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                lines.add("")
            }
            lines.add("patch:")
            lines.add("  schema_list:")
            lines.addAll(newLines)
        } else {
            var schemaListIdx = -1
            for (i in patchIdx + 1 until lines.size) {
                if (isTopLevel(lines[i])) {
                    break
                }
                val trimmed = lines[i].trim()
                if (trimmed == "schema_list:" || trimmed.startsWith("schema_list: ")) {
                    schemaListIdx = i
                    break
                }
            }
            
            if (schemaListIdx == -1) {
                lines.addAll(patchIdx + 1, listOf("  schema_list:") + newLines)
            } else {
                // 截断边界与解析侧 [SchemePackageImport.parseSchemaListFromCustomYaml]
                // 对齐：解析侧跳过空行/注释行，且只在缩进 <= schema_list 缩进
                // 的首个非跳过行处结束；其间缩进更深的非项行（如全角空格行、
                // 裸键行、单独破折号行）同样只跳过不结束。旧实现在首个
                // 非 `- ` 行处截断，空行/注释行之后的旧项会留在文本里，
                // 解析时又被读到，即旧项复活。此处扫描到同一终结行，
                // 只删其中解析侧认作序列项的行，其余行原样保留。
                val listIndent = lines[schemaListIdx].takeWhile { it == ' ' || it == '\t' }.length
                lines[schemaListIdx] = "  schema_list:"

                val itemIndices = mutableListOf<Int>()
                var scan = schemaListIdx + 1
                while (scan < lines.size) {
                    val line = lines[scan].trimEnd()
                    if (line.isBlank() || line.trimStart().startsWith("#")) {
                        scan++
                        continue
                    }
                    val indent = line.takeWhile { it == ' ' || it == '\t' }.length
                    if (indent <= listIndent) break
                    val content = line.trimStart()
                    if (Regex("""^-\s+(\S.*)$""").matchEntire(content) != null) {
                        itemIndices.add(scan)
                    }
                    scan++
                }

                for (i in itemIndices.asReversed()) {
                    lines.removeAt(i)
                }

                lines.addAll(schemaListIdx + 1, newLines)
            }
        }
        
        while (lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.size - 1)
        }
        return lines.joinToString("\n") + "\n"
    }
    
    /**
     * 写目录启用表：读 customFile 现有文本（不存在视为空，读失败抛 IOException），
     * 经 patchSchemaListYaml 算新文本后写同目录 tmp 再 rename 覆盖；rename 失败删 tmp
     * 并抛 IOException；父目录按需创建；ID 白名单过滤与 patchSchemaListYaml 内一致。
     * tmp 名带 nanoTime 后缀（与 httpGetToFile 同口径）：固定 .tmp 名在并发写时
     * 会互踩（后写者删 tmp 会删掉先写者的 tmp），带时间戳后各写各的 tmp。
     */
    fun writeEnabledCustomFile(customFile: File, ids: List<String>) {
        val validIds = ids.filter { id -> isValidSchemaId(id) }

        customFile.parentFile?.mkdirs()

        val existingText = if (customFile.exists()) {
            customFile.readText()
        } else {
            null
        }

        val newText = patchSchemaListYaml(existingText, validIds)
        val parent = customFile.parentFile
        val tmpFile = if (parent != null) {
            File(parent, customFile.name + ".tmp-${System.nanoTime()}")
        } else {
            File(customFile.name + ".tmp-${System.nanoTime()}")
        }
        try {
            tmpFile.writeText(newText)
            if (!tmpFile.renameTo(customFile)) {
                throw IOException("rename failed: ${tmpFile.path} -> ${customFile.path}")
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    /**
     * 把既有 custom 文件的 schema_list 规范化为 Rime 标准 map 项：
     * 以双兼容解析读出现有 ID，算出新文本，与原文不同才经
     * [writeEnabledCustomFile] 写回（tmp＋rename）。原文已是 map
     * 格式、文件缺失、不可读、无有效 ID 时返回 false 且不写盘。
     * 供部署前对当前目录调用：旧版裸字符串文件若不先规范化，
     * 本次部署仍按旧格式读不到方案、编不出词库。
     */
    fun normalizeCustomFileToMapFormat(customFile: File): Boolean {
        if (!customFile.isFile) return false
        val existing = try {
            customFile.readText()
        } catch (_: Exception) {
            return false
        }
        val ids = SchemePackageImport.parseSchemaListFromCustomYaml(existing)
        if (ids.isEmpty()) return false
        val newText = patchSchemaListYaml(existing, ids)
        if (newText == existing) return false
        return try {
            writeEnabledCustomFile(customFile, ids)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 写全局镜像偏好（单键，供键盘跟随）：以逗号连接 ID 写入
     * SchemaManager.KEY_ENABLED_IDS。绝不写按目录键 enabled_schema_ids_<目录>。
     */
    fun writeEnabledMirror(prefs: SharedPreferences, ids: List<String>) {
        prefs.edit {
            putString(SchemaManager.KEY_ENABLED_IDS, ids.joinToString(","))
        }
    }
}
