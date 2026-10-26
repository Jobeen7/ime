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

/** 一次 T9 注入的汇总：实际改写的文件数，与读/写失败而跳过的文件主干名。 */
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

/**
 * 改写单个方案文件的 schema: 段：映射键替换/插入，english 删 candidateKind。
 * 返回 false 当且仅当找不到顶格 `schema:` 行（调用方记 skipped）。
 * 行尾换行按原文保留（split/join 不增不减）。
 */
private fun injectSchemaFields(
    file: File,
    fields: Map<String, String>,
    deleteCandidateKind: Boolean,
): Boolean {
    val lines = file.readText().split("\n").toMutableList()
    val schemaIdx = lines.indexOfFirst { line ->
        val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
        firstNonWs == 0 && line.trim() == "schema:"
    }
    if (schemaIdx < 0) return false
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
    file.writeText(out.joinToString("\n"))
    return true
}

/** 导入失败种类（调用方据此 toast；DEPLOY_FAILED 时指针已留新目录，不回滚）。 */
enum class ImportFailure {
    NOT_ZIP,
    TOO_LARGE,
    NO_SCHEMA,
    IO_ERROR,
    DEPLOY_FAILED,
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
 * 导入编排：校验→定目录→（合并时读旧启用）→解压→T9 注入→写启用表→切指针→部署→选首位。
 *
 * 契约：
 * - 大小上限与魔数在写盘前检查；无根级方案在写盘前拦（预检），不留孤文件。
 * - 同名目录已存在则合并覆盖：解压不删目标独有文件；启用表＝(新方案＋旧启用).distinct()。
 * - 启用表写新目录 custom＋镜像（镜像经 [writeMirror]，调用方接偏好）。
 * - 指针经 [setCurrentDir] 切到新目录后才部署；部署超时沿用
 *   [SchemePackageImport.deployTimeoutMs]（写出字节数）。
 * - 部署失败返回 [ImportFailure.DEPLOY_FAILED]，指针留在新目录、不重选
 *   （重部署或重导修复）；成功才选首个新方案。
 * - zipFile 本身由调用方负责删除（暂存）；取消（[CancellationException]）重抛。
 */
suspend fun importPackage(
    zipFile: File,
    fallbackName: String,
    schemeDirsRoot: File,
    sharedDir: File,
    writeMirror: (List<String>) -> Unit,
    setCurrentDir: (String) -> Unit,
    deploy: suspend (Long) -> Boolean,
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
        val enabled = (extract.newSchemaIds + oldEnabled).distinct()
        SchemeEnabled.writeEnabledCustomFile(
            File(targetDir, "default.custom.yaml"),
            enabled,
        )
        writeMirror(enabled)
        setCurrentDir(dirName)
        val deployed = deploy(SchemePackageImport.deployTimeoutMs(extract.totalBytes))
        if (!deployed) return ImportOutcome.Failed(ImportFailure.DEPLOY_FAILED)
        selectSchema(enabled[0])
        return ImportOutcome.Success(
            dirName,
            extract.newSchemaIds,
            inject.injected,
            inject.skipped,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ImportOutcome.Failed(ImportFailure.IO_ERROR, e)
    }
}
