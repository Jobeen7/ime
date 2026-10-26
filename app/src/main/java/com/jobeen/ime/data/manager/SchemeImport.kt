// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

/**
 * 多目录方案导入：SAF/下载的 zip 解压到独立新目录（同名合并覆盖），
 * 再做 T9 字段注入、写启用表、切指针、部署、选首位方案。
 *
 * extractZip/stripTopDir/isProtectedEntry/ExtractResult 移植自 .6 单目录导入
 * （feature/single-dir-scheme 的 SingleSchemeImport.kt），语义不变；
 * 其余（目录名、T9 注入、导入编排）为本包新写。
 * 调用方负责：zipFile 是已落盘的暂存文件（用后自删）；部署与选方案、
 * 指针写盘、镜像写盘以 lambda 传入（本文件不碰引擎与 Context，便于单测）。
 */

/** 导入 zip 上限：256MiB（DESIGN P3；复制期与编排期双重检查）。 */
const val MAX_IMPORT_ZIP_BYTES = 256L * 1024 * 1024

/**
 * zip magic 纯谓词：PK\x03\x04（普通 zip）、PK\x05\x06（空 zip）、
 * PK\x07\x08（分卷）通过；其余一律不通过。.cskin 本身就是 zip，
 * 过 magic 后走正常校验流程、无方案时应为 NO_SCHEMA，绝不特判为
 * NOT_ZIP。
 */
fun hasZipMagic(header: ByteArray): Boolean {
    if (header.size < 4) return false
    if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) return false
    return (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
        (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
        (header[2] == 0x07.toByte() && header[3] == 0x08.toByte())
}

/**
 * 一次方案包解压的汇总结果。
 *
 * @property installed 实际写出的文件数。
 * @property skippedProtected 因命中用户保护规则而跳过的文件数。
 * @property skippedUnsafe 因路径不安全（穿越、绝对路径、canonical 校验失败）而跳过的文件数。
 * @property newSchemaIds 根级方案文件（形如 `xxx.schema.yaml`）的主干名，按条目顺序，不去重。
 * @property totalBytes 实际写出的总字节数。
 */
data class ExtractResult(
    val installed: Int,
    val skippedProtected: Int,
    val skippedUnsafe: Int,
    val newSchemaIds: List<String>,
    val totalBytes: Long,
)

/**
 * 计算可剥离的顶层目录前缀。
 *
 * 契约：
 * - [names] 为空时返回 null。
 * - 取首个名字的 `/` 之前部分为候选前缀；首个名字不含 `/` 时返回 null。
 * - 全部名字都以 `前缀 + "/"` 开头时返回该前缀，否则返回 null。
 */
fun stripTopDir(names: List<String>): String? {
    if (names.isEmpty()) return null
    val first = names.first()
    val slashIdx = first.indexOf('/')
    if (slashIdx <= 0) return null
    val prefix = first.substring(0, slashIdx)
    val prefixSlash = prefix + "/"
    if (prefix.isEmpty()) return null
    for (name in names) {
        if (!name.startsWith(prefixSlash)) return null
    }
    return prefix
}

/**
 * 判断相对路径是否为受保护的用户文件（永不覆盖）。
 *
 * 契约：
 * - rel 按 `/` 分段，任一段以 `.userdb` 结尾时返回 true。
 * - 取最后一段为文件名：根级（只有一段）的 `default.yaml` 返回 true；
 *   文件名以 `.custom.yaml` 结尾返回 true；文件名为 `custom_phrase.txt` 返回 true；
 *   文件名以 `.userdict.txt` 结尾返回 true。
 * - 其余返回 false。
 */
fun isProtectedEntry(rel: String): Boolean {
    if (rel.isEmpty()) return false
    val segments = rel.split('/')
    for (segment in segments) {
        if (segment.endsWith(".userdb")) return true
    }
    val fileName = segments.last()
    if (segments.size == 1 && fileName == "default.yaml") return true
    if (fileName.endsWith(".custom.yaml")) return true
    if (fileName == "custom_phrase.txt") return true
    if (fileName.endsWith(".userdict.txt")) return true
    return false
}

/**
 * 将 ZIP 方案包直接解压到目标目录（无事务与回滚）。
 *
 * 契约：
 * - 用 [ZipFile] 打开；打不开或条目损坏时抛 [IOException]，由调用方处理。
 * - 只处理文件条目；目录条目（名字以 `/` 结尾）忽略。
 * - 用全部文件条目名计算 [stripTopDir]；有前缀时剥离 `前缀 + "/"`；剥离后为空的条目跳过（不计数）。
 * - 相对路径含 `..` 段或为绝对路径（以 `/` 开头）时记 skippedUnsafe，不写盘。
 * - [isProtectedEntry] 为真时记 skippedProtected，不写盘。
 * - 其余条目写入 targetDir 下的同相对路径：写盘前做 canonical 前缀校验，失败记 skippedUnsafe；
 *   父目录按需创建；同名直接覆盖（不删目标独有文件）。
 * - 写盘成功记 installed 并累计写出字节数；相对路径只有一段且以 `.schema.yaml` 结尾时，
 *   把文件名去掉后缀的主干记入 newSchemaIds（保持顺序，不去重）。
 * - ZipFile 必须可靠关闭。
 */
fun extractZip(zipFile: File, targetDir: File): ExtractResult {
    targetDir.mkdirs()
    ZipFile(zipFile).use { zip ->
        val fileEntries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
        val entryNames = fileEntries.map { it.name }
        val topDir = stripTopDir(entryNames)
        val prefixSlash = topDir?.let { it + "/" } ?: ""

        var installed = 0
        var skippedProtected = 0
        var skippedUnsafe = 0
        var totalBytes = 0L
        val newSchemaIds = mutableListOf<String>()

        for (entry in fileEntries) {
            var relativePath = entry.name
            if (prefixSlash.isNotEmpty() && relativePath.startsWith(prefixSlash)) {
                relativePath = relativePath.substring(prefixSlash.length)
            }

            if (relativePath.isEmpty()) continue

            if (relativePath.startsWith("/") || relativePath.split("/").any { it == ".." }) {
                skippedUnsafe += 1
                continue
            }

            if (isProtectedEntry(relativePath)) {
                skippedProtected += 1
                continue
            }

            val outFile = File(targetDir, relativePath)
            val targetCanonical = targetDir.canonicalFile
            val outCanonical = outFile.canonicalFile
            if (outCanonical != targetCanonical && !outCanonical.path.startsWith(targetCanonical.path + File.separator)) {
                skippedUnsafe += 1
                continue
            }

            outFile.parentFile?.mkdirs()

            val bytes = zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            totalBytes += bytes
            installed += 1

            val parts = relativePath.split("/")
            if (parts.size == 1 && relativePath.endsWith(".schema.yaml")) {
                newSchemaIds.add(relativePath.removeSuffix(".schema.yaml"))
            }
        }

        return ExtractResult(installed, skippedProtected, skippedUnsafe, newSchemaIds, totalBytes)
    }
}

/**
 * 新目录名：包内单一顶层目录名优先，否则 zip 文件名去 .zip 后净化。
 * 净化与保留名（build、初始目录）走 [SchemeDirs.importDirName]；结果为空、
 * `.`、`..` 时回落为 `imported`（绝不让解压目标落到根本身）。
 */
fun determineDirName(topDir: String?, fallbackName: String): String {
    val raw = topDir?.takeIf { it.isNotBlank() }
        ?: fallbackName.removeSuffix(".zip").removeSuffix(".ZIP")
    val cleaned = raw.takeIf { it.isNotBlank() } ?: "imported"
    val named = SchemeDirs.importDirName(cleaned)
    return named.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "imported"
}

/**
 * 预检 zip：一次开包返回（顶层目录，解压后会落到根级的方案主干名）。
 * 根级判定与 [extractZip] 同口径（剥离顶层前缀后单段、`.schema.yaml` 结尾、
 * 非穿越、非绝对路径）；受保护规则不影响 `.schema.yaml`（它从不是保护项）。
 */
private fun inspectZip(zipFile: File): Pair<String?, List<String>> {
    ZipFile(zipFile).use { zip ->
        val fileNames = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        val topDir = stripTopDir(fileNames)
        val prefix = topDir?.let { it + "/" } ?: ""
        val stems = fileNames.mapNotNull { name ->
            val rel = if (prefix.isNotEmpty() && name.startsWith(prefix)) {
                name.substring(prefix.length)
            } else {
                name
            }
            if (rel.isEmpty()) return@mapNotNull null
            if (rel.startsWith("/") || rel.split("/").any { it == ".." }) return@mapNotNull null
            val parts = rel.split("/")
            if (parts.size == 1 && rel.endsWith(".schema.yaml")) {
                rel.removeSuffix(".schema.yaml")
            } else {
                null
            }
        }
        return topDir to stems
    }
}

/** T9 字段注入映射：按 [parseSchemaFile] 读出的 schema_id 命中，与文件名无关。 */
private val T9_FIELD_INJECTIONS: Map<String, Map<String, String>> = mapOf(
    "wanxiang_t9" to mapOf(
        "layout" to "T9",
        "punctuation" to "FullWidth",
        "kind" to "PinYin",
        "candidateKind" to "T9PinYin",
    ),
    // t9i 与 t9 同为九键拼音：缺 candidateKind 时左列“可能拼音”门控必空，
    // 故与 t9 同口径注入；其 t9_processor 在当前 librime 内无注册，
    // 部署时被软跳过（engine.cc 记错后 continue，不阻断部署）。
    "wanxiang_t9i" to mapOf(
        "layout" to "T9",
        "punctuation" to "FullWidth",
        "kind" to "PinYin",
        "candidateKind" to "T9PinYin",
    ),
    "wanxiang_lite" to mapOf(
        "layout" to "Qwerty",
        "punctuation" to "FullWidth",
        "kind" to "PinYin",
        "candidateKind" to "PinYin",
    ),
    "wanxiang_english" to mapOf(
        "layout" to "Qwerty",
        "punctuation" to "HalfWidth",
        "kind" to "English",
    ),
)

/** 一次注入的汇总：注入后正确的文件数（已改写或原本已正确），与读/写失败而跳过的文件主干名。 */
data class InjectResult(
    val injected: Int,
    val skipped: List<String>,
)

/**
 * 对本次解压出的根级方案文件做 T9 字段注入（只改 schema: 段内四键）。
 *
 * 契约：
 * - 每个主干名对应 `dir/<主干>.schema.yaml`；解析不到 schema_id（无 schema: 段、
 *   读失败）时记 skipped，不中断。
 * - schema_id 不在三映射内时原样不动（不计 injected、不计 skipped）。
 * - 映射键：段内有则替换值（保留原缩进、值加双引号），无则插到段尾
 *   （用段内首个键的缩进，无则两空格）；其余行（含 options/speller）原样不动。
 * - wanxiang_english 不写 candidateKind，段内已有则删行。
 * - 写盘异常记 skipped，不中断。
 */
fun injectT9Fields(dir: File, filenameStems: List<String>): InjectResult {
    var injected = 0
    val skipped = mutableListOf<String>()
    for (stem in filenameStems) {
        val file = File(dir, "$stem.schema.yaml")
        val disk = try {
            parseSchemaFile(file)
        } catch (_: Exception) {
            null
        }
        if (disk == null) {
            skipped.add(stem)
            continue
        }
        val fields = T9_FIELD_INJECTIONS[disk.id] ?: continue
        val ok = try {
            injectSchemaFields(file, fields, disk.id == "wanxiang_english")
        } catch (_: Exception) {
            false
        }
        if (ok) injected++ else skipped.add(stem)
    }
    return InjectResult(injected, skipped)
}

/** 一项 options 段条目：name/key/keys/lock/value 与内置三文件逐方案旧值一致。 */
private data class OptionItem(
    val name: String,
    val key: String,
    val keys: String,
    val lock: Boolean,
    val value: Boolean,
)

/**
 * options 段注入映射：开关到引擎的唯一桥。导入的上游包本来就没有
 * options: 段，不补则英文方案的 ascii_mode 等开关再次脱节（2026-10-19
 * 旧修在多目录下的复发）。t9i 取与 t9 相同的口径。
 */
private val OPTIONS_INJECTIONS: Map<String, List<OptionItem>> = mapOf(
    "wanxiang_t9" to listOf(
        OptionItem("traditional_chinese_enabled", "s2t", "[s2s, s2t, s2hk, s2tw]", false, false),
        OptionItem("emoji_enabled", "emoji", "[emoji]", false, false),
        OptionItem("ascii_mode_enabled", "ascii_mode", "[ascii_mode]", true, false),
    ),
    "wanxiang_lite" to listOf(
        OptionItem("traditional_chinese_enabled", "s2t", "[s2s, s2t, s2hk, s2tw]", false, false),
        OptionItem("emoji_enabled", "emoji", "[emoji]", false, false),
        OptionItem("ascii_mode_enabled", "ascii_mode", "[ascii_mode]", true, false),
    ),
    "wanxiang_english" to listOf(
        OptionItem("traditional_chinese_enabled", "s2t", "[s2s, s2t, s2hk, s2tw]", true, false),
        OptionItem("emoji_enabled", "emoji", "[emoji]", false, false),
        OptionItem("ascii_mode_enabled", "ascii_mode", "[ascii_mode]", false, false),
    ),
    "wanxiang_t9i" to listOf(
        OptionItem("traditional_chinese_enabled", "s2t", "[s2s, s2t, s2hk, s2tw]", false, false),
        OptionItem("emoji_enabled", "emoji", "[emoji]", false, false),
        OptionItem("ascii_mode_enabled", "ascii_mode", "[ascii_mode]", true, false),
    ),
)

/**
 * 对本次解压出的根级方案文件做 options 段注入（只改顶格 options: 段）。
 *
 * 契约（与 [injectT9Fields] 同口径）：
 * - schema_id 不在映射内时原样不动（不计 injected、不计 skipped）。
 * - 无 options: 段时在文件尾追加整段；有则逐项比对：缺项补整项，
 *   已有项的 lock/value 与旧值不符则纠正，key/keys 原样不动。
 * - 已正确的文件不改写（幂等，不 churn mtime）。
 * - 解析不到 schema_id 或写盘异常记 skipped，不中断。
 */
fun injectOptionsSegments(dir: File, filenameStems: List<String>): InjectResult {
    var injected = 0
    val skipped = mutableListOf<String>()
    for (stem in filenameStems) {
        val file = File(dir, "$stem.schema.yaml")
        val disk = try {
            parseSchemaFile(file)
        } catch (_: Exception) {
            null
        }
        if (disk == null) {
            skipped.add(stem)
            continue
        }
        val expected = OPTIONS_INJECTIONS[disk.id] ?: continue
        val ok = try {
            val lines = file.readText().split("\n")
            val out = transformOptionsLines(lines, expected)
            if (out != lines) file.writeText(out.joinToString("\n"))
            true
        } catch (_: Exception) {
            false
        }
        if (ok) injected++ else skipped.add(stem)
    }
    return InjectResult(injected, skipped)
}

/**
 * 纯行变换：对方案文件行做 options 段注入，返回新行（调用方比对后写盘）。
 * 顶格 `options:` 行缺失时在尾部追加整段；已有时逐项补齐/纠正 lock/value，
 * 未知条目与空行原样保留。实现为整段重建，不做行号算术。
 */
private fun transformOptionsLines(
    lines: List<String>,
    expected: List<OptionItem>,
): List<String> {
    val optionsIdx = lines.indexOfFirst { line ->
        val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
        firstNonWs == 0 && line.trim() == "options:"
    }
    if (optionsIdx < 0) {
        val out = lines.toMutableList()
        // split 尾空行（文件以换行结尾时）会把追加段顶到空行之后，
        // 属正常空行分隔，不影响 YAML 解析。
        out.add("options:")
        for (item in expected) out.addAll(optionItemLines(item))
        return out
    }
    var blockEnd = lines.size
    for (i in optionsIdx + 1 until lines.size) {
        val line = lines[i]
        if (line.isBlank()) continue
        val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs == 0) {
            blockEnd = i
            break
        }
    }
    val expectedByName = expected.associateBy { it.name }
    val out = mutableListOf<String>()
    out.addAll(lines.subList(0, optionsIdx + 1))
    val seen = mutableSetOf<String>()
    var i = optionsIdx + 1
    while (i < blockEnd) {
        val line = lines[i]
        val m = ITEM_NAME_REGEX.find(line)
        if (m == null) {
            out.add(line)
            i++
            continue
        }
        var j = i + 1
        while (j < blockEnd && ITEM_NAME_REGEX.find(lines[j]) == null) j++
        val name = m.groupValues[2].trim()
        val itemLines = lines.subList(i, j)
        val want = expectedByName[name]
        if (want != null) {
            seen.add(name)
            out.addAll(correctOptionItemLines(itemLines, want))
        } else {
            out.addAll(itemLines)
        }
        i = j
    }
    for (item in expected) {
        if (item.name !in seen) out.addAll(optionItemLines(item))
    }
    out.addAll(lines.subList(blockEnd, lines.size))
    return out
}

private val ITEM_NAME_REGEX = Regex("""^(\s*)- name: (.+)$""")

/** 纠正单条目内的 lock/value 行（保留原缩进；缺失则补在条目尾）。 */
private fun correctOptionItemLines(
    itemLines: List<String>,
    want: OptionItem,
): List<String> {
    val out = itemLines.toMutableList()
    val lockIdx = out.indexOfFirst { it.trim().startsWith("lock:") }
    if (lockIdx >= 0) {
        val indent = out[lockIdx].substringBefore("lock:")
        out[lockIdx] = indent + "lock: " + want.lock
    } else {
        out.add("    lock: " + want.lock)
    }
    val valueIdx = out.indexOfFirst { it.trim().startsWith("value:") }
    if (valueIdx >= 0) {
        val indent = out[valueIdx].substringBefore("value:")
        out[valueIdx] = indent + "value: " + want.value
    } else {
        out.add("    value: " + want.value)
    }
    return out
}

private fun optionItemLines(item: OptionItem): List<String> = listOf(
    "  - name: " + item.name,
    "    key: " + item.key,
    "    keys: " + item.keys,
    "    lock: " + item.lock,
    "    value: " + item.value,
)

/**
 * 改写单个方案文件的 schema: 段：映射键替换/插入，english 删 candidateKind。
 * 返回 false 当且仅当找不到顶格 `schema:` 行（调用方记 skipped）。
 * 行尾换行按原文保留（split/join 不增不减）。已正确的文件不改写（幂等）。
 */
private fun injectSchemaFields(
    file: File,
    fields: Map<String, String>,
    deleteCandidateKind: Boolean,
): Boolean {
    val lines = file.readText().split("\n")
    val out = transformSchemaLines(lines, fields, deleteCandidateKind) ?: return false
    if (out != lines) file.writeText(out.joinToString("\n"))
    return true
}

/** 纯行变换：对方案文件行做 schema: 段注入，返回新行；找不到顶格 schema: 行返回 null。 */
internal fun transformSchemaLines(
    lines: List<String>,
    fields: Map<String, String>,
    deleteCandidateKind: Boolean,
): List<String>? {
    val schemaIdx = lines.indexOfFirst { line ->
        val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
        firstNonWs == 0 && line.trim() == "schema:"
    }
    if (schemaIdx < 0) return null
    var blockEnd = lines.size
    for (i in schemaIdx + 1 until lines.size) {
        val line = lines[i]
        if (line.isBlank()) continue
        val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs == 0) {
            blockEnd = i
            break
        }
    }
    val found = mutableSetOf<String>()
    val newBlock = mutableListOf<String>()
    var blockIndent: String? = null
    for (i in schemaIdx + 1 until blockEnd) {
        val line = lines[i]
        if (line.isBlank()) {
            newBlock.add(line)
            continue
        }
        val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs <= 0) {
            newBlock.add(line)
            continue
        }
        val colon = line.indexOf(':')
        if (colon <= 0) {
            newBlock.add(line)
            continue
        }
        val key = line.substring(0, colon).trim()
        if (blockIndent == null) blockIndent = line.substring(0, firstNonWs)
        if (deleteCandidateKind && key == "candidateKind") continue
        val value = fields[key]
        if (value != null) {
            found.add(key)
            newBlock.add(line.substring(0, firstNonWs) + key + ": \"" + value + "\"")
        } else {
            newBlock.add(line)
        }
    }
    val indent = blockIndent ?: "  "
    for ((key, value) in fields) {
        if (key !in found) newBlock.add(indent + key + ": \"" + value + "\"")
    }
    val out = mutableListOf<String>()
    out.addAll(lines.subList(0, schemaIdx + 1))
    out.addAll(newBlock)
    out.addAll(lines.subList(blockEnd, lines.size))
    return out
}

/** 导入失败种类（调用方据此 toast；DEPLOY_FAILED/DEPLOY_TIMEOUT 时调用方回滚指针＋镜像）。 */
enum class ImportFailure {
    NOT_ZIP,
    TOO_LARGE,
    NO_SCHEMA,
    /** 怪包：重复/冲突/超长/超深/超条目——包本身有问题，与磁盘错误区分。 */
    BAD_PACKAGE,
    IO_ERROR,
    DEPLOY_FAILED,
    DEPLOY_TIMEOUT,
}

/** 导入结果：成功带新目录名、新方案 id、注入汇总；失败带种类与原始异常（可空）。 */
sealed interface ImportOutcome {
    data class Success(
        val dirName: String,
        val newSchemaIds: List<String>,
        val injected: Int,
        val injectSkipped: List<String>,
    ) : ImportOutcome

    data class Failed(
        val failure: ImportFailure,
        val cause: Throwable? = null,
    ) : ImportOutcome
}

/**
 * 存量修复：对目录内已有的根级方案文件补齐 schema: 字段与 options: 段
 * （与导入注入同口径，仅写内容有变的文件）。只修传入的目录，不动 shared。
 * 调用点：目录切换前、设置页加载当前目录时——覆盖导入前已落盘的旧目录。
 */
fun repairSchemeFiles(dir: File) {
    val stems = dir.listFiles { f -> f.isFile && f.name.endsWith(".schema.yaml") }
        ?.map { it.name.removeSuffix(".schema.yaml") }
        ?: return
    if (stems.isEmpty()) return
    injectT9Fields(dir, stems)
    injectOptionsSegments(dir, stems)
}

/**
 * 导入编排：校验→定目录→（合并时读旧启用）→解压→T9 注入＋options 注入→写启用表→切指针→部署→选首位。
 *
 * 契约：
 * - 大小上限与魔数在写盘前检查；无根级方案在写盘前拦（预检），不留孤文件。
 * - 同名目录已存在则合并覆盖：解压不删目标独有文件；启用表＝(新方案＋旧启用).distinct()。
 * - 启用表写新目录 custom＋镜像（镜像经 [writeMirror]，调用方接偏好）。
 * - 指针经 [setCurrentDir] 切到新目录后才部署；部署超时沿用
 *   [SchemePackageImport.deployTimeoutMs]（写出字节数）。
 * - 部署失败返回 [ImportFailure.DEPLOY_FAILED]、超时返回
 *   [ImportFailure.DEPLOY_TIMEOUT]，指针回滚由调用方负责（见 runImport，
 *   与切换失败同口径）；成功才选首个新方案。
 * - zipFile 本身由调用方负责删除（暂存）；取消（[CancellationException]）重抛。
 */
suspend fun importPackage(
    zipFile: File,
    fallbackName: String,
    schemeDirsRoot: File,
    sharedDir: File,
    writeMirror: (List<String>) -> Unit,
    setCurrentDir: (String) -> Unit,
    deploy: suspend (Long) -> DeployAwaitResult,
    selectSchema: (String) -> Unit,
): ImportOutcome {
    try {
        if (!zipFile.isFile) return ImportOutcome.Failed(ImportFailure.IO_ERROR)
        if (zipFile.length() > MAX_IMPORT_ZIP_BYTES) {
            return ImportOutcome.Failed(ImportFailure.TOO_LARGE)
        }
        val header = ByteArray(4)
        var read = 0
        zipFile.inputStream().use { input ->
            while (read < 4) {
                val n = input.read(header, read, 4 - read)
                if (n < 0) break
                read += n
            }
        }
        if (!hasZipMagic(header.copyOf(read))) {
            return ImportOutcome.Failed(ImportFailure.NOT_ZIP)
        }
        val (topDir, rootStems) = inspectZip(zipFile)
        if (rootStems.isEmpty()) return ImportOutcome.Failed(ImportFailure.NO_SCHEMA)
        val dirName = determineDirName(topDir, fallbackName)
        val targetDir = File(schemeDirsRoot, dirName)
        val oldEnabled = if (targetDir.isDirectory) {
            val diskIds = discoverSchemes(targetDir, sharedDir).map { it.id }
            effectiveEnabledIds(targetDir, sharedDir, diskIds)
        } else {
            emptyList()
        }
        val extract = extractZip(zipFile, targetDir)
        if (extract.newSchemaIds.isEmpty()) {
            return ImportOutcome.Failed(ImportFailure.NO_SCHEMA)
        }
        val inject = injectT9Fields(targetDir, extract.newSchemaIds)
        val optionsInject = injectOptionsSegments(targetDir, extract.newSchemaIds)
        val enabled = (extract.newSchemaIds + oldEnabled).distinct()
        SchemeEnabled.writeEnabledCustomFile(
            File(targetDir, "default.custom.yaml"),
            enabled,
        )
        writeMirror(enabled)
        setCurrentDir(dirName)
        val deployed = deploy(SchemePackageImport.deployTimeoutMs(extract.totalBytes))
        if (deployed == DeployAwaitResult.TIMEOUT_RUNNING) {
            return ImportOutcome.Failed(ImportFailure.DEPLOY_TIMEOUT)
        }
        if (deployed != DeployAwaitResult.COMPLETED) {
            return ImportOutcome.Failed(ImportFailure.DEPLOY_FAILED)
        }
        selectSchema(enabled[0])
        return ImportOutcome.Success(
            dirName,
            extract.newSchemaIds,
            inject.injected,
            inject.skipped + optionsInject.skipped,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ImportOutcome.Failed(ImportFailure.IO_ERROR, e)
    }
}
