package com.jobeen.ime.data.manager

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.io.IOException

object SchemeEnabled {
    private val ITEM_PATTERN = Regex("[A-Za-z0-9_.\\-]+")

    /**
     * 根据传入的 ID 列表生成或修改 YAML 文本中的 schema_list 序列。
     *
     * - 过滤 ID：仅保留全匹配 [A-Za-z0-9_.\-]+ 且不等于 "."、".." 的项。
     * - 若 existingText 为 null 或空白，返回以 "# Written by Jime scheme settings." 开头的全新文本。
     * - 若无 patch 段，在末尾追加 patch: 及其下的 schema_list: 和序列项。
     * - 若有 patch 段但无 schema_list 行，在 patch: 行之后、下一顶格行之前插入。
     * - 若有 schema_list 行，丢弃冒号后的非空白内容，替换随后的连续 `- ` 项。
     * - 已知取舍：序列在第一个不是 `- ` 项的行处结束，空行、注释行同样截断序列。
     * - 返回文本始终以单个换行符结尾。
     */
    fun patchSchemaListYaml(existingText: String?, ids: List<String>): String {
        val validIds = ids.filter { id ->
            ITEM_PATTERN.matches(id) && id != "." && id != ".."
        }
        
        val newLines = validIds.map { "    - $it" }
        
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
                lines[schemaListIdx] = "  schema_list:"
                
                var seqEnd = schemaListIdx + 1
                while (seqEnd < lines.size && lines[seqEnd].trim().startsWith("- ")) {
                    seqEnd++
                }
                
                for (i in seqEnd - 1 downTo schemaListIdx + 1) {
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
     * 经 patchSchemaListYaml 算新文本后写同目录 .tmp 再 rename 覆盖；rename 失败删 tmp
     * 并抛 IOException；父目录按需创建；ID 白名单过滤与 patchSchemaListYaml 内一致。
     */
    fun writeEnabledCustomFile(customFile: File, ids: List<String>) {
        val validIds = ids.filter { id ->
            ITEM_PATTERN.matches(id) && id != "." && id != ".."
        }

        customFile.parentFile?.mkdirs()

        val existingText = if (customFile.exists()) {
            customFile.readText()
        } else {
            null
        }

        val newText = patchSchemaListYaml(existingText, validIds)
        val parent = customFile.parentFile
        val tmpFile = if (parent != null) {
            File(parent, customFile.name + ".tmp")
        } else {
            File(customFile.name + ".tmp")
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
     * 写全局镜像偏好（单键，供键盘跟随）：以逗号连接 ID 写入
     * SchemaManager.KEY_ENABLED_IDS。绝不写按目录键 enabled_schema_ids_<目录>。
     */
    fun writeEnabledMirror(prefs: SharedPreferences, ids: List<String>) {
        prefs.edit {
            putString(SchemaManager.KEY_ENABLED_IDS, ids.joinToString(","))
        }
    }
}
