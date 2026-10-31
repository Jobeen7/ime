// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 第二期本地导入：方案包校验、安装规划与落盘（纯逻辑，不依赖 Android 框架，
 * 可 JVM 单测；SAF 选择与界面在 SchemaSettingsScreen）。
 *
 * 约束来源（定稿文档修订版，2026-10-24）：
 * - zip 先解到临时目录再校验：zip-slip 路径穿越条目跳过并告警、体积上限、
 *   包内必须有 *.schema.yaml，否则拒绝；
 * - 只放行方案文件进 user 目录：绝不覆盖 *.userdb、user.yaml、
 *   installation.yaml、build/ 产物与已存在的 *.custom.yaml（视为用户改过）；
 * - 包内 default.custom.yaml 只合并 schema_list，绝不整文件覆盖；
 * - 共享文件（.gram / lua / opencc / wanxiang_algebra.yaml /
 *   wanxiang_symbols.yaml）按包版本比对：低版本不覆盖高版本，版本未知时
 *   保留现有并注明；版本来源取包 version.txt，缺失时取包内最高 schema
 *   version，再缺失即未知；
 * - 同 ID 替换（含 Pro 多口味共用 wanxiang_pro）：先归档旧版再覆盖；
 * - 一个包带来的是方案集合：登记结构（包→方案集合→落盘文件）留给后续
 *   期数卸载按集合清；schema_list 只合并、不整文件覆盖。
 *
 * 版本比较口径与 WanxiangUpdateManager.compareVersions 一致：去 v/V 前缀，
 * 按 . - _ 切段，非数字段按 0 计，逐段数值比较。
 */
object SchemePackageImport {

    /** zip 文件本身上限：大包几十到上百 MB，512MB 仍留足余量。 */
    const val MAX_ZIP_BYTES = 512L * 1024 * 1024

    /** 解压后总体积上限：防 zip 炸弹写满存储。 */
    const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024

    /** 单个条目解压上限。 */
    const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

    /** 解压条目数上限（与 WebDavSync 的 walk 上限同口径，防条目表无限增长）。 */
    const val MAX_EXTRACT_ENTRIES = 20000

    /** 单条目归一化相对路径长度上限（字符数）。 */
    const val MAX_EXTRACT_REL_PATH_CHARS = 1024

    /** 单条目相对路径深度上限（/ 分段数）。 */
    const val MAX_EXTRACT_PATH_DEPTH = 32

    /** 跳过条目只保留的样例条数（其余只计总数，不无限存列表）。 */
    const val MAX_SKIPPED_TRAVERSAL_SAMPLES = 20

    /** 导入前剩余空间检查的额外余量（部署还会写 build 产物）。 */
    const val FREE_SPACE_MARGIN_BYTES = 16L * 1024 * 1024

    /** 包登记偏好文件名（与方案列表偏好分开，避免键冲突）。 */
    const val PREFS_NAME = "scheme_import"

    /** 包登记键：整表一行一记录的编码字符串（见 encodeRegistry）。 */
    const val KEY_PACKAGE_REGISTRY = "package_registry"

    /** 暂存条目：相对路径（已归一化）与解压后字节数。 */
    data class StagedEntry(
        val relPath: String,
        val sizeBytes: Long,
    )

    /**
     * 暂存包：条目表 + 按相对路径读文本。读文本只用于解析 schema.yaml /
     * version.txt / default.custom.yaml，读不到按缺失处理（不抛）。
     */
    data class StagedPackage(
        val entries: List<StagedEntry>,
        val readText: (String) -> String?,
    )

    /** 条目路径归一化结果：文件、忽略（目录或空）、穿越。 */
    sealed interface NormalizedEntry {
        data class File(val relPath: String) : NormalizedEntry
        data object Ignored : NormalizedEntry
        data object Traversal : NormalizedEntry
    }

    /**
     * 归一化 zip 条目路径：反斜杠转斜杠、去 ./ 前缀与空段、解析 . 与 ..。
     * .. 逃出根目录即穿越；目录条目与空路径忽略。注意：本函数只做词法
     * 归一化，落盘时调用方仍须做 canonical 路径守卫（双保险）。
     */
    fun normalizeEntryPath(raw: String): NormalizedEntry {
        val trimmed = raw.trim().replace('\\', '/')
        // 原始条目以 / 结尾即目录条目（ZipEntry.isDirectory 之外的第二道判断）。
        if (trimmed.endsWith('/')) return NormalizedEntry.Ignored
        val cleaned = trimmed.trimEnd('/')
        if (cleaned.isEmpty()) return NormalizedEntry.Ignored
        // 绝对路径（/ 开头或盘符开头）一律按穿越处理，不静默转相对路径。
        if (cleaned.startsWith('/')) return NormalizedEntry.Traversal
        val segments = ArrayDeque<String>()
        for (segment in cleaned.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return NormalizedEntry.Traversal
                    segments.removeLast()
                }
                else -> segments.addLast(segment)
            }
        }
        if (segments.isEmpty()) return NormalizedEntry.Ignored
        if (segments.first().contains(':')) return NormalizedEntry.Traversal
        return NormalizedEntry.File(segments.joinToString("/"))
    }

    /**
     * 剥掉 GitHub codeload 式 zip 的单层顶级目录（如 rime-ice-main/…）：
     * 仅当全部条目都有至少两段且首段全相同时才剥；否则原样返回。
     * Rime user 目录布局是扁平的（方案文件在根），不剥会把方案文件
     * 藏进一层无用子目录、引擎读不到。
     */
    fun stripTopWrapper(paths: List<String>): List<String> {
        if (paths.isEmpty()) return paths
        val firstSegments = paths.map { it.substringBefore('/') }
        if (paths.any { !it.contains('/') }) return paths
        if (firstSegments.toSet().size != 1) return paths
        val prefix = firstSegments.first() + "/"
        return paths.map { it.removePrefix(prefix) }
    }

    /** schema.yaml 顶层 schema 映射里的三个标量（name / version / layout）。 */
    data class SchemaYamlFields(
        val name: String?,
        val version: String?,
        val layout: String?,
    )

    /**
     * 行级解析 schema.yaml 的顶层 schema 映射。只取 name / version /
     * layout 三个直接子键的标量值；嵌套块（如 switches 下的 - name:）
     * 因带短横前缀不会误匹配。引号与行尾注释会剥掉；块标量不支持，
     * 读不到按缺失处理（调用方回落 id / 空布局，不编造）。
     */
    fun parseSchemaYamlFields(text: String): SchemaYamlFields {
        val lines = text.lines()
        var schemaIndent: Int? = null
        var name: String? = null
        var version: String? = null
        var layout: String? = null
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            val content = line.trimStart()
            if (schemaIndent == null) {
                if (indent == 0 && content.startsWith("schema:") &&
                    content.removePrefix("schema:").trim()
                        .let { it.isEmpty() || it.startsWith("#") }
                ) {
                    schemaIndent = 0
                }
                continue
            }
            if (indent == 0) break
            val match = Regex("""^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$""").matchEntire(content)
                ?: continue
            val key = match.groupValues[1]
            val value = scalarValue(match.groupValues[2])
            when (key) {
                "name" -> if (name == null) name = value
                "version" -> if (version == null) version = value
                "layout" -> if (layout == null) layout = value
            }
        }
        return SchemaYamlFields(name, version, layout)
    }

    /** 解析 default.custom.yaml 顶层 schema_list（块列表与流式 [a, b] 均支持；块项支持 `- schema: <id>` 与旧版裸字符串两种写法）。 */
    fun parseSchemaListFromCustomYaml(text: String): List<String> {
        val lines = text.lines()
        val ids = mutableListOf<String>()
        var listIndent: Int? = null
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            val content = line.trimStart()
            if (listIndent == null) {
                val match = Regex("""^schema_list\s*:\s*(.*)$""").matchEntire(content)
                    ?: continue
                val rest = match.groupValues[1].trim()
                if (rest.startsWith("[")) {
                    rest.removePrefix("[").removeSuffix("]").split(',')
                        .map { scalarValue(it) }.filter { it.isNotEmpty() }
                        .forEach { if (it !in ids) ids.add(it) }
                    return ids
                }
                if (rest.isNotEmpty()) {
                    scalarValue(rest).takeIf { it.isNotEmpty() }?.let { if (it !in ids) ids.add(it) }
                    return ids
                }
                listIndent = indent
                continue
            }
            if (indent <= listIndent) break
            val rawItem = Regex("""^-\s+(\S.*)$""").matchEntire(content)?.groupValues?.get(1)
                ?: continue
            // 序列项双兼容：Rime 标准 map 项 `- schema: <id>` 取冒号后的值；
            // 旧版写出的裸字符串项直接取整项（存量文件规范化前仍要读对）。
            val valueText = if (rawItem == "schema:" || rawItem.startsWith("schema: ")) {
                rawItem.removePrefix("schema:").trim()
            } else {
                rawItem
            }
            scalarValue(valueText).takeIf { it.isNotEmpty() }?.let { if (it !in ids) ids.add(it) }
        }
        return ids
    }

    /**
     * 去行尾注释（# 前须有空白或行首）并剥单双引号；是 [yamlPlainScalar]
     * 的逆：双引号内反转义（\" \\ \n \r \t \uXXXX，见 [unescapeDoubleQuoted]），
     * 单引号内 '' 还原为 '。引号内的首尾空白有意义，不再二次 trim
     * （旧实现剥引号后又 trim，会把带首尾空白的 id 读走样）。
     * 注释扫描跳过双引号内的转义字符，否则 \" 会误关引号、其后的 #
     * 被误当行尾注释切掉。
     */
    private fun scalarValue(raw: String): String {
        var s = raw.trim()
        var inSingle = false
        var inDouble = false
        var cut = s.length
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inDouble && c == '\\' && i + 1 < s.length) {
                i += 2
                continue
            }
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble &&
                    (i == 0 || s[i - 1] == ' ' || s[i - 1] == '\t') -> {
                    cut = i
                    break
                }
            }
            i++
        }
        s = s.substring(0, cut).trim()
        if (s.length >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return unescapeDoubleQuoted(s.substring(1, s.length - 1))
        }
        return s
    }

    /** 双引号体反转义：只处理 [yamlPlainScalar] 实际写出的转义；未知转义
     * 宽容地保留转义字符本身（不抛，调用方按缺失/原值处理）。 */
    private fun unescapeDoubleQuoted(body: String): String {
        if (!body.contains('\\')) return body
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.append(c)
                i++
                continue
            }
            when (val e = body[i + 1]) {
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                '0' -> out.append('\u0000')
                'u' -> {
                    val hex = body.substring(i + 2, minOf(i + 6, body.length))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append('u')
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }

    /** 版本比较：与 WanxiangUpdateManager.compareVersions 同口径。 */
    fun compareVersions(left: String, right: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val r = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (r != 0) return r
        }
        return 0
    }

    /** 条目分类：决定落盘策略的唯一依据。 */
    enum class EntryKind {
        SCHEMA,
        DICT,
        SHARED,
        CUSTOM,
        DEFAULT_CUSTOM,
        PROTECTED,
        OTHER,
    }

    fun classifyEntry(relPath: String): EntryKind {
        val name = relPath.substringAfterLast('/')
        if (relPath.contains(".userdb") || name == "user.yaml" ||
            name == "installation.yaml" || relPath.startsWith("build/")
        ) {
            return EntryKind.PROTECTED
        }
        return when {
            relPath == "default.custom.yaml" -> EntryKind.DEFAULT_CUSTOM
            relPath.endsWith(".schema.yaml") -> EntryKind.SCHEMA
            relPath.endsWith(".dict.yaml") -> EntryKind.DICT
            relPath.endsWith(".custom.yaml") -> EntryKind.CUSTOM
            relPath.endsWith(".gram") || relPath.startsWith("lua/") ||
                relPath.startsWith("opencc/") ||
                name == "wanxiang_algebra.yaml" ||
                name == "wanxiang_symbols.yaml" -> EntryKind.SHARED
            else -> EntryKind.OTHER
        }
    }

    /** 跳过原因（界面文案据此映射，不直接拼字符串进规划结果）。 */
    enum class SkipReason {
        TRAVERSAL,
        PROTECTED,
        USER_CUSTOM,
        EXISTS_KEPT,
        SHARED_OLDER,
        SHARED_UNKNOWN_VERSION,
        /** 方案文件主干为空（如 ".schema.yaml"）：产不出合法 id，整条跳过。 */
        EMPTY_SCHEMA_ID,
    }

    /**
     * 目录部署校验超时（纯函数）：180 秒基线 + 每 8MiB 加 30 秒，上限
     * 1800 秒。系数是算术参考：63MiB 对应 180 + 7×30 = 390 秒；完整
     * Base 编译时长未实测，见验收说明。
     */
    fun deployTimeoutMs(totalBytes: Long): Long {
        if (totalBytes <= 0L) return 180_000L
        val steps = totalBytes / (8L * 1024 * 1024)
        return minOf(180_000L + steps * 30_000L, 1_800_000L)
    }

    /**
     * 目录部署超时（纯函数）：把目录下全部文件字节数加总后走
     * [deployTimeoutMs]。FAB 部署与切换路径共用这一套算法（切换路径
     * 以前是自己加总再调 deployTimeoutMs，口径与此相同）；枚举失败
     * 按 0 字节计（180 秒基线），不抛异常。
     */
    fun deployTimeoutForDir(dir: File): Long {
        val totalBytes = runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        return deployTimeoutMs(totalBytes)
    }

    data class SkippedFile(
        val relPath: String,
        val reason: SkipReason,
    )

    data class ImportedScheme(
        val id: String,
        val name: String,
        val version: String?,
        val layout: String,
    )

    data class ReplacementInfo(
        val schemeId: String,
        /** 旧版所属包（登记里查不到时为 null，如一期前已存在的手工文件）。 */
        val previousPackage: String?,
        /** 是否覆盖内置同名方案（仅 shared 层有、user 层无时为 true）。 */
        val overridesBuiltin: Boolean,
    )

    data class SharedFileDecision(
        val relPath: String,
        val incomingVersion: String?,
        val existingVersion: String?,
        val overwrite: Boolean,
        val unknownVersion: Boolean,
    )

    data class SchemeImportPlan(
        val packageName: String,
        val packageVersion: String?,
        val schemes: List<ImportedScheme>,
        /** 落盘文件（相对路径，含覆盖既有文件的）。 */
        val installFiles: List<String>,
        /** 暂存盘上的包装目录前缀（如 codeload 式 `rime-ice-main/`），
         * 落盘时拼在 installFiles 前；无包装时为空。 */
        val wrapperPrefix: String = "",
        val skippedFiles: List<SkippedFile>,
        val sharedDecisions: List<SharedFileDecision>,
        val replacements: List<ReplacementInfo>,
        /** 包内 default.custom.yaml 的 schema_list（只合并，不落盘）。 */
        val defaultCustomSchemaIds: List<String>,
        /** 将覆盖的既有文件（安装确认页汇总用）。 */
        val overwrittenExisting: List<String>,
        /** 解压后总字节数（剩余空间检查用）。 */
        val totalBytes: Long,
    )

    enum class PlanFailure {
        ZIP_TOO_LARGE,
        ENTRY_TOO_LARGE,
        TOTAL_TOO_LARGE,
        NO_SCHEMA,
    }

    sealed interface PlanResult {
        data class Success(val plan: SchemeImportPlan) : PlanResult
        data class Failed(val reason: PlanFailure) : PlanResult
    }

    /**
     * 由暂存包生成安装规划（纯函数，不碰磁盘）。
     *
     * @param packageName 包名（zip 文件名去 .zip 后净化，供登记与归档目录用）
     * @param zipBytes zip 文件字节数（上限检查用）
     * @param staged 暂存包（条目已归一化；顶层包装目录在此剥掉）
     * @param preSkipped 解压阶段已跳过的条目（如路径穿越，见 extractZip）
     * @param existingUserFiles user 目录现存文件相对路径集合
     * @param existingSharedSchemeIds shared 层现存方案 id 集合
     * @param registry 包登记（同 ID 替换归属与共享文件既有版本来源）
     * @param dir 目标方案目录名：登记查找按此目录过滤（多目录下同名包
     *   不得跨目录串）；null 时不过滤（旧调用方汇总语义不变）。
     */
    fun planInstall(
        packageName: String,
        zipBytes: Long,
        staged: StagedPackage,
        preSkipped: List<SkippedFile>,
        existingUserFiles: Set<String>,
        existingSharedSchemeIds: Set<String>,
        registry: SchemePackageRegistry,
        dir: String? = null,
        /**
         * 共享文件强制覆盖：直接导入口径（今天行为）——共享文件无条件覆盖，
         * 绕过版本门控。DESIGN P3 仍不写登记；门控仅在确认页流程（默认 false）生效。
         */
        forceSharedOverwrite: Boolean = false,
    ): PlanResult {
        if (zipBytes > MAX_ZIP_BYTES) return PlanResult.Failed(PlanFailure.ZIP_TOO_LARGE)
        var totalBytes = 0L
        for (entry in staged.entries) {
            if (entry.sizeBytes > MAX_ENTRY_BYTES) {
                return PlanResult.Failed(PlanFailure.ENTRY_TOO_LARGE)
            }
            totalBytes += entry.sizeBytes
            if (totalBytes > MAX_EXTRACTED_BYTES) {
                return PlanResult.Failed(PlanFailure.TOTAL_TOO_LARGE)
            }
        }
        val rawPaths = staged.entries.map { it.relPath }
        val stripped = stripTopWrapper(rawPaths)
        // 剥包装目录后，读文本仍须走暂存盘上的原始路径（带包装前缀）。
        val wrapperPrefix = if (stripped != rawPaths) {
            rawPaths.first().substringBefore('/') + "/"
        } else {
            ""
        }
        fun readStaged(rel: String): String? = staged.readText(wrapperPrefix + rel)
        val pathByIndex = rawPaths.indices.associate { i -> rawPaths[i] to stripped[i] }
        val entries = staged.entries.map { entry ->
            entry.copy(relPath = pathByIndex.getValue(entry.relPath))
        }
        val schemaEntriesAll = entries.filter {
            classifyEntry(it.relPath) == EntryKind.SCHEMA
        }
        // 主干为空的方案文件（如 ".schema.yaml"）产不出合法 id：整条跳过
        // 并记入跳过原因，不进入方案表、安装文件与替换判定；只剩空主干
        // 时与无方案同等处理（NO_SCHEMA）。
        val emptyStemEntries = schemaEntriesAll.filter {
            it.relPath.substringAfterLast('/').removeSuffix(".schema.yaml").isEmpty()
        }
        val schemaEntries = schemaEntriesAll - emptyStemEntries.toSet()
        if (schemaEntries.isEmpty()) return PlanResult.Failed(PlanFailure.NO_SCHEMA)

        val packageVersion = readStaged("version.txt")
            ?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: schemaEntries.mapNotNull {
                parseSchemaYamlFields(readStaged(it.relPath) ?: "").version
            }.maxWithOrNull { a, b -> compareVersions(a, b) }

        val schemes = schemaEntries.map { entry ->
            // id 恒等于方案文件主干（basename 去 .schema.yaml 后缀，不做字符映射）：
            // 应用按 File(dir, "${item.id}.schema.yaml") 查方案，引擎按 schema_list
            // 条目找同名文件，id 与主干不一致即找不到文件。YAML 安全不靠白名单，
            // 靠写 YAML 时的 yamlPlainScalar 转义；目录名安全靠 sanitizeDirName。
            val id = entry.relPath.substringAfterLast('/').removeSuffix(".schema.yaml")
            val fields = parseSchemaYamlFields(readStaged(entry.relPath) ?: "")
            ImportedScheme(
                id = id,
                name = fields.name?.takeIf { it.isNotBlank() } ?: id,
                version = fields.version?.takeIf { it.isNotBlank() },
                layout = fields.layout.orEmpty(),
            )
        }
        val defaultCustomSchemaIds = readStaged("default.custom.yaml")
            ?.let { parseSchemaListFromCustomYaml(it) }.orEmpty()

        val installFiles = mutableListOf<String>()
        val skippedFiles = (preSkipped + emptyStemEntries.map {
            SkippedFile(it.relPath, SkipReason.EMPTY_SCHEMA_ID)
        }).toMutableList()
        val sharedDecisions = mutableListOf<SharedFileDecision>()
        val overwrittenExisting = mutableListOf<String>()
        for (entry in entries) {
            val path = entry.relPath
            // 空主干方案文件已在上一步记入跳过，安装循环内不再落盘。
            if (entry in emptyStemEntries) continue
            val exists = path in existingUserFiles
            when (classifyEntry(path)) {
                EntryKind.SCHEMA, EntryKind.DICT -> {
                    installFiles.add(path)
                    if (exists) overwrittenExisting.add(path)
                }
                EntryKind.SHARED -> {
                    if (!exists) {
                        installFiles.add(path)
                    } else {
                        val existingVersion = registry.versionForFile(path, dir)
                        val unknown = packageVersion == null || existingVersion == null
                        val overwrite = forceSharedOverwrite ||
                            (!unknown &&
                                compareVersions(packageVersion, existingVersion) > 0)
                        sharedDecisions.add(
                            SharedFileDecision(
                                relPath = path,
                                incomingVersion = packageVersion,
                                existingVersion = existingVersion,
                                overwrite = overwrite,
                                unknownVersion = unknown,
                            ),
                        )
                        if (overwrite) {
                            installFiles.add(path)
                            overwrittenExisting.add(path)
                        } else {
                            skippedFiles.add(
                                SkippedFile(
                                    path,
                                    if (unknown) SkipReason.SHARED_UNKNOWN_VERSION
                                    else SkipReason.SHARED_OLDER,
                                ),
                            )
                        }
                    }
                }
                EntryKind.CUSTOM -> {
                    if (exists) {
                        skippedFiles.add(SkippedFile(path, SkipReason.USER_CUSTOM))
                    } else {
                        installFiles.add(path)
                    }
                }
                EntryKind.DEFAULT_CUSTOM -> Unit // 只合并 schema_list，永不落盘
                EntryKind.PROTECTED -> skippedFiles.add(SkippedFile(path, SkipReason.PROTECTED))
                EntryKind.OTHER -> {
                    if (exists) {
                        skippedFiles.add(SkippedFile(path, SkipReason.EXISTS_KEPT))
                    } else {
                        installFiles.add(path)
                    }
                }
            }
        }

        // 替换判定按方案文件的实际相对路径查现存文件，不用 id 反拼文件名。
        val replacements = schemaEntries.zip(schemes).mapNotNull { (entry, scheme) ->
            val inUser = entry.relPath in existingUserFiles
            val inShared = scheme.id in existingSharedSchemeIds
            if (!inUser && !inShared) return@mapNotNull null
            val previousPackage = registry.packageForScheme(scheme.id, dir)
            ReplacementInfo(
                schemeId = scheme.id,
                previousPackage = previousPackage,
                overridesBuiltin = !inUser && inShared,
            )
        }

        return PlanResult.Success(
            SchemeImportPlan(
                packageName = packageName,
                packageVersion = packageVersion,
                schemes = schemes,
                installFiles = installFiles,
                wrapperPrefix = wrapperPrefix,
                skippedFiles = skippedFiles,
                sharedDecisions = sharedDecisions,
                replacements = replacements,
                defaultCustomSchemaIds = defaultCustomSchemaIds,
                overwrittenExisting = overwrittenExisting,
                totalBytes = totalBytes,
            ),
        )
    }

    /** schema_list 合并：只追加缺失 id，现有顺序一字不动。 */
    fun mergeSchemaList(existing: List<String>, added: List<String>): List<String> =
        existing + added.filter { it !in existing }

    /** 包来源：本地导入（SAF）与手动 URL 下载的区分依据（第三期）。 */
    enum class PackageSource {
        LOCAL,
        URL,
    }

    /** 包登记记录：后续期数卸载按集合清的依据（本期只登记、不删）。
     * 多目录模型下记录所属目录 [dir]（旧五字段行解码后为空，按初始目录计）。 */
    data class SchemePackageRecord(
        val packageName: String,
        val version: String?,
        val schemeIds: List<String>,
        val files: List<String>,
        /** 默认 LOCAL：二期已存的四字段旧行解码后同样回落 LOCAL。 */
        val source: PackageSource = PackageSource.LOCAL,
        /** 包落盘的方案目录名；空 = 多目录前的旧记录。 */
        val dir: String = "",
    )

    data class SchemePackageRegistry(val records: List<SchemePackageRecord>) {
        /**
         * 目录匹配：dir == null 时不过滤（跨目录汇总语义保留，供无目录
         * 上下文的调用方）；dir 非空时只匹配该目录的记录，多目录下
         * 同名包不得跨目录串；dir 为空的旧记录（多目录前）按初始目录计。
         */
        private fun SchemePackageRecord.matchesDir(dir: String?): Boolean =
            dir == null || this.dir == dir ||
                (this.dir.isEmpty() && dir == SchemeDirs.INITIAL_DIR)

        fun packageForScheme(schemeId: String, dir: String? = null): String? =
            records.firstOrNull { schemeId in it.schemeIds && it.matchesDir(dir) }?.packageName

        /** 方案所属包的来源（URL 包与本地导入在列表来源处区分显示）。 */
        fun sourceForScheme(schemeId: String, dir: String? = null): PackageSource? =
            records.firstOrNull { schemeId in it.schemeIds && it.matchesDir(dir) }?.source

        fun versionForFile(relPath: String, dir: String? = null): String? =
            records.firstOrNull { relPath in it.files && it.matchesDir(dir) }?.version

        fun filesForPackage(packageName: String, dir: String? = null): List<String> =
            records.firstOrNull {
                it.packageName == packageName && it.matchesDir(dir)
            }?.files.orEmpty()

        /** 同名包登记替换旧记录（重装同一包不留两条）。 */
        fun withRecord(record: SchemePackageRecord): SchemePackageRegistry =
            SchemePackageRegistry(
                records.filter { it.packageName != record.packageName } + record,
            )

        fun withoutPackage(packageName: String): SchemePackageRegistry =
            SchemePackageRegistry(records.filter { it.packageName != packageName })
    }

    /**
     * 登记编码：一行一记录，字段以 Tab 分隔（包名 / 版本 / 方案 id 逗号串 /
     * 落盘文件逗号串 / 来源 / 目录名）。Tab 与换行在包名里净化成下划线；Rime 文件名
     * 实际不含逗号，逗号串可接受。解码容错：字段不足的行直接丢弃；来源
     * 字段缺失（二期旧行）或非 url 时一律回落 LOCAL，不改旧行语义；
     * 目录字段缺失（多目录前旧行）时为空。
     */
    fun encodeRegistry(registry: SchemePackageRegistry): String =
        registry.records.joinToString("\n") { record ->
            listOf(
                record.packageName.replace('\t', '_').replace('\n', '_').replace('\r', '_'),
                record.version.orEmpty(),
                record.schemeIds.joinToString(","),
                record.files.joinToString(","),
                if (record.source == PackageSource.URL) "url" else "local",
                record.dir,
            ).joinToString("\t")
        }

    fun decodeRegistry(raw: String?): SchemePackageRegistry {
        if (raw.isNullOrBlank()) return SchemePackageRegistry(emptyList())
        val records = raw.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            SchemePackageRecord(
                packageName = parts[0],
                version = parts[1].takeIf { it.isNotEmpty() },
                schemeIds = parts[2].split(',').filter { it.isNotEmpty() },
                files = parts[3].split(',').filter { it.isNotEmpty() },
                source = if (parts.size >= 5 && parts[4] == "url") {
                    PackageSource.URL
                } else {
                    PackageSource.LOCAL
                },
                dir = if (parts.size >= 6) parts[5] else "",
            )
        }
        return SchemePackageRegistry(records)
    }

    /** 包名净化（归档目录名与登记键用）：只留字母数字与 . _ -。 */
    fun sanitizePackageName(raw: String): String {
        val base = raw.removeSuffix(".zip").removeSuffix(".ZIP")
        val cleaned = base.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("").trim('.', '_')
        return cleaned.ifEmpty { "scheme-package" }
    }

    /**
     * YAML 纯量转义：方案 id 现为文件主干原样（含空格、引号等特殊字符时），
     * 本函数保证任意字符串拼进 YAML 时都不破结构、不被解析成别的类型。
     * - 空串 → ''；纯数字、true/false/null 等保留字 → 单引号；
     * - 含换行/回车/控制字符 → 双引号并转义（\n \r \t \\ \" 与 \uXXXX）；
     * - 含空格、引号、": "、" #"、行首指示符等 → 单引号（' 加倍转义）；
     * - 其余（首字符字母/数字/_，余下字母/数字/._-）→ 原样纯量。
     */
    fun yamlPlainScalar(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.any { it < ' ' && it != '\t' } || value.contains('\n') ||
            value.contains('\r')
        ) {
            val escaped = buildString {
                for (c in value) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c < ' ') append("\\u%04X".format(c.code)) else append(c)
                    }
                }
            }
            return "\"$escaped\""
        }
        val plainSafe = value.first().let { it.isLetterOrDigit() || it == '_' } &&
            value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } &&
            value !in setOf("true", "false", "null", "~", "yes", "no", "on", "off") &&
            !value.all { it.isDigit() } &&
            value.lowercase() !in setOf("true", "false", "null", "yes", "no", "on", "off")
        if (plainSafe) return value
        return "'" + value.replace("'", "''") + "'"
    }

    /** 生成 default.custom.yaml 文本：每项 id 都经 [yamlPlainScalar] 转义，序列项为 Rime 标准 map 格式。 */
    fun buildDefaultCustomYamlContent(ids: List<String>): String =
        buildString {
            appendLine("patch:")
            appendLine("  schema_list:")
            ids.forEach { appendLine("    - schema: ${yamlPlainScalar(it)}") }
        }

    /**
     * zip 解压结果：暂存目录、条目、解压阶段跳过的穿越条目。
     * [skippedTraversal] 只保留前 [MAX_SKIPPED_TRAVERSAL_SAMPLES] 条样例，
     * [skippedTraversalCount] 为跳过总数（含样例外的与 canonical 守卫拦截的）。
     */
    data class ExtractedPackage(
        val stagingDir: File,
        val entries: List<StagedEntry>,
        val skippedTraversal: List<String>,
        val skippedTraversalCount: Int = skippedTraversal.size,
    )

    enum class ExtractFailure {
        NOT_ZIP,
        ZIP_TOO_LARGE,
        ENTRY_TOO_LARGE,
        TOTAL_TOO_LARGE,
        /** 条目数超 [MAX_EXTRACT_ENTRIES]。 */
        TOO_MANY_ENTRIES,
        /** 归一化相对路径超 [MAX_EXTRACT_REL_PATH_CHARS] 字符。 */
        ENTRY_PATH_TOO_LONG,
        /** 相对路径深度超 [MAX_EXTRACT_PATH_DEPTH] 段。 */
        ENTRY_PATH_TOO_DEEP,
        /** 归一化后相对路径重复。 */
        DUPLICATE_ENTRY,
        /** 文件与目录冲突（文件路径是另一文件的祖先目录，或反之）。 */
        ENTRY_CONFLICT,
        /** 暂存目录删/建失败、写出时的磁盘或权限错误（与格式错误分开）。 */
        IO_ERROR,
    }

    sealed interface ExtractResult {
        data class Ok(val extracted: ExtractedPackage) : ExtractResult
        data class Failed(val reason: ExtractFailure) : ExtractResult
    }

    /**
     * zip 解到暂存目录（调用方负责事后清理）。体积上限按实际写出字节数
     * 计（不依赖 ZipEntry.size，data-descriptor 条目该值可能为 -1）；
     * 超限时删掉暂存目录并返回失败，不留半解压。路径穿越条目跳过并
     * 记入 skippedTraversal 样例（供规划页告警，见 [ExtractedPackage]）；
     * 落盘另有 canonical 守卫。边界失败一律用新增变体，不复用 NOT_ZIP；
     * 暂存删/建失败（预检）与条目写出 IO 错误走 IO_ERROR；zip 格式本身
     * 损坏（ZipException，含条目数据损坏）走 NOT_ZIP；其余未预料的
     * Exception 兜底同样走 NOT_ZIP（InterruptedException 与
     * CancellationException 除外，均重抛）——不要把本段读成“只有
     * ZipException 才走 NOT_ZIP”，那与实际兜底分支不符。
     */
    fun extractZip(zipFile: File, stagingDir: File): ExtractResult {
        if (zipFile.length() > MAX_ZIP_BYTES) return ExtractResult.Failed(ExtractFailure.ZIP_TOO_LARGE)
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
            if (stagingDir.exists()) {
                return ExtractResult.Failed(ExtractFailure.IO_ERROR)
            }
        }
        stagingDir.mkdirs()
        if (!stagingDir.isDirectory) return ExtractResult.Failed(ExtractFailure.IO_ERROR)
        val entries = mutableListOf<StagedEntry>()
        val skippedSamples = mutableListOf<String>()
        var skippedCount = 0
        fun recordSkipped(raw: String) {
            skippedCount++
            if (skippedSamples.size < MAX_SKIPPED_TRAVERSAL_SAMPLES) skippedSamples.add(raw)
        }
        try {
            ZipFile(zipFile).use { zf ->
                var totalBytes = 0L
                var entryCount = 0
                val seenFiles = mutableSetOf<String>()
                val seenDirs = mutableSetOf<String>()
                fun ancestorsOf(relPath: String): List<String> {
                    val parts = relPath.split('/')
                    return (1 until parts.size).map { parts.take(it).joinToString("/") }
                }
                for (zipEntry in zf.entries()) {
                    entryCount++
                    if (entryCount > MAX_EXTRACT_ENTRIES) {
                        throw ExtractAbortException(ExtractFailure.TOO_MANY_ENTRIES)
                    }
                    if (zipEntry.isDirectory) {
                        val dirPath = zipEntry.name.replace('\\', '/').trim().trimEnd('/')
                        if (dirPath.isNotEmpty() && dirPath !in seenDirs) {
                            if (dirPath in seenFiles) {
                                throw ExtractAbortException(ExtractFailure.ENTRY_CONFLICT)
                            }
                            seenDirs.add(dirPath)
                        }
                        continue
                    }
                    when (val normalized = normalizeEntryPath(zipEntry.name)) {
                        is NormalizedEntry.Traversal -> {
                            recordSkipped(zipEntry.name)
                            continue
                        }
                        is NormalizedEntry.Ignored -> continue
                        is NormalizedEntry.File -> {
                            val relPath = normalized.relPath
                            if (relPath.length > MAX_EXTRACT_REL_PATH_CHARS) {
                                throw ExtractAbortException(ExtractFailure.ENTRY_PATH_TOO_LONG)
                            }
                            if (relPath.split('/').size > MAX_EXTRACT_PATH_DEPTH) {
                                throw ExtractAbortException(ExtractFailure.ENTRY_PATH_TOO_DEEP)
                            }
                            if (relPath in seenFiles) {
                                throw ExtractAbortException(ExtractFailure.DUPLICATE_ENTRY)
                            }
                            if (relPath in seenDirs) {
                                throw ExtractAbortException(ExtractFailure.ENTRY_CONFLICT)
                            }
                            val ancestors = ancestorsOf(relPath)
                            if (ancestors.any { it in seenFiles }) {
                                throw ExtractAbortException(ExtractFailure.ENTRY_CONFLICT)
                            }
                            val dest = File(stagingDir, relPath).canonicalFile
                            if (!dest.path.startsWith(stagingDir.canonicalPath + File.separator)) {
                                recordSkipped(zipEntry.name)
                                continue
                            }
                            val parent = dest.parentFile
                            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                                throw ExtractAbortException(ExtractFailure.IO_ERROR)
                            }
                            var entryBytes = 0L
                            try {
                                zf.getInputStream(zipEntry).use { input ->
                                    dest.outputStream().use { out ->
                                        val buf = ByteArray(32 * 1024)
                                        while (true) {
                                            val n = input.read(buf)
                                            if (n < 0) break
                                            entryBytes += n
                                            totalBytes += n
                                            if (entryBytes > MAX_ENTRY_BYTES) {
                                                throw ExtractAbortException(
                                                    ExtractFailure.ENTRY_TOO_LARGE,
                                                )
                                            }
                                            if (totalBytes > MAX_EXTRACTED_BYTES) {
                                                throw ExtractAbortException(
                                                    ExtractFailure.TOTAL_TOO_LARGE,
                                                )
                                            }
                                            out.write(buf, 0, n)
                                        }
                                    }
                                }
                            } catch (e: ExtractAbortException) {
                                throw e
                            } catch (e: java.util.zip.ZipException) {
                                // 条目数据损坏是格式错误，不是磁盘错误。
                                throw ExtractAbortException(ExtractFailure.NOT_ZIP)
                            } catch (e: java.io.IOException) {
                                throw ExtractAbortException(ExtractFailure.IO_ERROR)
                            }
                            seenFiles.add(relPath)
                            seenDirs.addAll(ancestors)
                            entries.add(StagedEntry(relPath, entryBytes))
                        }
                    }
                }
            }
        } catch (e: ExtractAbortException) {
            stagingDir.deleteRecursively()
            return ExtractResult.Failed(e.reason)
        } catch (e: java.util.zip.ZipException) {
            stagingDir.deleteRecursively()
            return ExtractResult.Failed(ExtractFailure.NOT_ZIP)
        } catch (e: java.io.IOException) {
            stagingDir.deleteRecursively()
            return ExtractResult.Failed(ExtractFailure.IO_ERROR)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            if (e is CancellationException) throw e
            stagingDir.deleteRecursively()
            return ExtractResult.Failed(ExtractFailure.NOT_ZIP)
        }
        return ExtractResult.Ok(
            ExtractedPackage(stagingDir, entries, skippedSamples, skippedCount),
        )
    }

    private class ExtractAbortException(val reason: ExtractFailure) : Exception()

    /** 由暂存目录构造规划输入：条目按相对路径排序，读文本走文件。 */
    fun stagedPackageOf(stagingDir: File, entries: List<StagedEntry>): StagedPackage {
        val sorted = entries.sortedBy { it.relPath }
        return StagedPackage(
            entries = sorted,
            readText = { rel ->
                runCatching { File(stagingDir, rel).readText() }.getOrNull()
            },
        )
    }

    /** 递归列出目录下全部文件相对路径（/ 分隔，供现存文件集合用）。 */
    fun listRelativeFiles(root: File): Set<String> {
        if (!root.isDirectory) return emptySet()
        val base = root.canonicalPath
        return root.walkTopDown().filter { it.isFile }.mapNotNull { file ->
            runCatching {
                val path = file.canonicalPath
                if (path.startsWith(base + File.separator)) {
                    path.removePrefix(base + File.separator).replace(File.separatorChar, '/')
                } else {
                    null
                }
            }.getOrNull()
        }.toSet()
    }

    /**
     * 执行安装规划：直接落盘（同 ID 替换的旧版文件直接被新版覆盖，
     * 不再归档）。落盘双侧 canonical 守卫，逃出 user 目录的条目
     * 直接跳过。返回实际落盘文件（供登记记录用）。
     * 本函数不写登记偏好（调用方在成功后写），不碰引擎与部署。
     * 兼容保留：后续屏幕施工员改写 confirmImport 时改用下面的
     * [installTransaction]，本函数不动，供旧流程与单测继续使用。
     */
    fun applyPlan(
        stagingDir: File,
        userDir: File,
        plan: SchemeImportPlan,
    ): List<String> {
        val userCanonical = userDir.canonicalPath
        val installed = mutableListOf<String>()
        for (rel in plan.installFiles) {
            // 暂存盘保留原始路径（含包装目录），落盘路径已剥包装：
            // 读源须拼回 wrapperPrefix，否则带包装的包会静默零落盘。
            val src = File(stagingDir, plan.wrapperPrefix + rel).canonicalFile
            val dest = File(userDir, rel).canonicalFile
            if (!dest.path.startsWith(userCanonical + File.separator)) continue
            if (!src.isFile) continue
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            installed.add(rel)
        }
        return installed
    }

    // ---- 事务化安装（供后续屏幕施工员调用，confirmImport 改写用） ----
    //
    // 调用顺序（屏幕侧）：
    //   1. 启动时先调 recoverIncompleteTransactions(方案目录根)，清掉上次
    //      崩溃留下的中间态（只在无安装进行时调用）；
    //   2. 确认页调 installTransaction(暂存盘, 目标目录, 规划, 暂存条目)；
    //   3. 成功后写登记、切目录、部署；部署确认后再调
    //      cleanupBackup(backupDir) 删备份；部署/确认失败则调
    //      rollbackTransaction(backupDir, targetDir) 恢复旧版。
    //   4. installTransaction 返回 SWAP_FAILED 时，旧目录已被移走而候选
    //      未就位：不要重试安装，先调 recoverIncompleteTransactions 恢复
    //      到完整旧版，再由用户重新确认。
    // 注意：installTransaction 本身已写候选目录的 default.custom.yaml
    // （方案表见下），调用方不要再重写。

    /**
     * 受保护文件继承：把旧目录下的受保护文件拷进候选目录——实路径语义，
     * 不用 .inherit/ 中转，避免中转缺失导致恢复时丢文件。
     * - 拷：任意层级的 *.userdb（文件或 userdb 目录内的文件）、
     *   *.custom.yaml（含 default.custom.yaml，见下）、顶层 user.yaml、
     *   顶层 installation.yaml；
     * - 已存在的 *.custom.yaml 视为用户改过：旧文件覆盖候选内同名包文件；
     * - 不拷 build/：它是可再生的编译产物，部署时重编；拷旧 build/
     *   有新旧 schema 失配风险，且失败时备份恢复会把旧 build/ 带回来。
     * 顺序语义（installTransaction 内）：先装包文件、再 inherit（旧 custom
     * 压过包文件）、最后生成 default.custom.yaml（新方案表压过继承来的
     * 旧 default.custom.yaml）。单独调用本函数时，继承来的
     * default.custom.yaml 仍是旧方案表，调用方须随后重写方案表。
     * 返回实际拷入的相对路径（已排序）；旧目录不存在返回空表；
     * 拷失败抛 IOException（installTransaction 据此整体失败并删候选）。
     */
    fun inheritProtected(oldDir: File, candidateDir: File): List<String> {
        if (!oldDir.isDirectory) return emptyList()
        val copied = mutableListOf<String>()
        for (rel in listRelativeFiles(oldDir).sorted()) {
            if (rel.startsWith("build/")) continue
            val name = rel.substringAfterLast('/')
            val keep = rel.contains(".userdb") ||
                rel.endsWith(".custom.yaml") ||
                rel == "user.yaml" ||
                rel == "installation.yaml"
            if (!keep) continue
            val src = File(oldDir, rel)
            val dest = File(candidateDir, rel)
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            copied.add(rel)
        }
        return copied
    }

    /**
     * union 继承：旧目标独有文件全量拷入候选（不含已装文件、已继承保护文件与
     * default.custom.yaml——后者由方案表步骤重建；含 build/，切换时不删 build/
     * 的原则在导入同样适用）。目标不存在返回空；任一拷失败抛（调用方转 COPY_FAILED）。
     */
    private fun inheritPreserved(
        oldDir: File,
        candidateDir: File,
        installFiles: Set<String>,
        inherited: Set<String>,
    ): List<String> {
        if (!oldDir.isDirectory) return emptyList()
        val copied = mutableListOf<String>()
        for (rel in listRelativeFiles(oldDir).sorted()) {
            if (rel in installFiles || rel in inherited || rel == "default.custom.yaml") continue
            val src = File(oldDir, rel)
            val dest = File(candidateDir, rel)
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = false)
            copied.add(rel)
        }
        return copied
    }

    /** 事务化安装的换位产物：成功后备份与日志保留，供回滚/清理用。 */
    data class InstallTransaction(
        val operationId: String,
        val targetDir: File,
        val candidateDir: File,
        val backupDir: File,
        val journalFile: File,
        val installedFiles: List<String>,
        val inheritedFiles: List<String>,
        /** union 合并时继承的旧目标独有文件（非 union 时为空）。 */
        val preservedFiles: List<String> = emptyList(),
    )

    enum class TransactionFailure {
        /** 预校验失败：目标被文件挡住、候选/备份名已占、条目越界、源缺失、条目重复。 */
        PREVALIDATION,
        /** 拷入候选（或继承）中途失败：候选已删、旧目录原样。 */
        COPY_FAILED,
        /** 候选校验失败（存在/大小/方案 yaml/方案表回读）：候选已删、旧目录原样。 */
        VERIFICATION_FAILED,
        /** 规划非空却零落盘：候选已删、旧目录原样（原 applyPlan 守卫的事务版）。 */
        NOTHING_INSTALLED,
        /** 换位失败：日志保留，调用方须调 recoverIncompleteTransactions 恢复。 */
        SWAP_FAILED,
    }

    sealed interface InstallTransactionResult {
        data class Success(val transaction: InstallTransaction) : InstallTransactionResult
        data class Failed(
            val failure: TransactionFailure,
            val message: String,
        ) : InstallTransactionResult
    }

    /** 安装日志阶段（写进父目录的 .install-<operationId>.journal）。 */
    enum class JournalStage {
        CANDIDATE_CREATED,
        FILES_INSTALLED,
        VERIFIED,
        TARGET_MOVED,
        SWAPPED,
    }

    private fun journalFileFor(parentDir: File, operationId: String): File =
        File(parentDir, ".install-$operationId.journal")

    /**
     * 写安装日志：先写同目录 tmp 再 rename 覆盖（同一文件系统内原子换位）。
     * 直接 writeText 一旦写一半被杀，日志即截断、恢复时无法解析；
     * tmp＋rename 则要么是旧阶段的完整日志、要么是新阶段的完整日志，
     * 不存在半截日志。rename 失败删 tmp 并抛错（旧日志原样保留，
     * 恢复按旧阶段处置，不会把一次失败的阶段推进误当成已推进）。
     */
    private fun writeJournal(
        journalFile: File,
        operationId: String,
        targetDir: File,
        candidateDir: File,
        backupDir: File,
        stage: JournalStage,
    ) {
        val content = buildString {
            appendLine("operationId=$operationId")
            appendLine("target=${targetDir.absolutePath}")
            appendLine("candidate=${candidateDir.absolutePath}")
            appendLine("backup=${backupDir.absolutePath}")
            appendLine("stage=${stage.name}")
        }
        val tmpFile = File(journalFile.parentFile, "${journalFile.name}.tmp-${System.nanoTime()}")
        tmpFile.writeText(content)
        if (!tmpFile.renameTo(journalFile)) {
            tmpFile.delete()
            throw IOException("journal rename failed: ${tmpFile.path} -> ${journalFile.path}")
        }
    }

    private data class ParsedJournal(
        val operationId: String,
        val target: File,
        val candidate: File,
        val backup: File,
        val stage: String,
    )

    private fun parseJournal(journalFile: File): ParsedJournal? {
        val values = mutableMapOf<String, String>()
        runCatching {
            journalFile.readLines().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) values[line.substring(0, idx)] = line.substring(idx + 1)
            }
        }.getOrNull() ?: return null
        val operationId = values["operationId"] ?: return null
        val target = values["target"]?.let { File(it) } ?: return null
        val candidate = values["candidate"]?.let { File(it) } ?: return null
        val backup = values["backup"]?.let { File(it) } ?: return null
        return ParsedJournal(operationId, target, candidate, backup, values["stage"].orEmpty())
    }

    /**
     * 事务化安装：候选目录 + 原子换位（同父目录 rename，同文件系统）。
     *
     * @param stagingDir 暂存盘（extractZip 的产物，含 wrapperPrefix 前缀路径）
     * @param targetDir 目标目录（将被替换；不存在时为全新安装）
     * @param plan 安装规划（installFiles 为落盘相对路径，已剥包装）
     * @param stagedEntries 暂存条目（extractZip 的原始相对路径与字节数，
     *   供逐项大小核对；缺失对应条目即预校验失败）
     * @param schemaList 写入候选 default.custom.yaml 的最终方案表；
     *   null 时取 plan.defaultCustomSchemaIds，非空则用它，否则取
     *   plan.schemes 的 id（与旧 confirmImport 的 tableIds 口径一致）
     * @param operationId 调用方传入或自动生成；候选/备份/日志名由此派生
     *
     * 步骤：预校验全部条目（目标在候选树内、源存在、无重复）→ 建候选 →
     * 拷包文件 → inheritProtected（旧受保护文件压过包文件）→ 生成
     * default.custom.yaml → 逐项核对存在与大小、每个 .schema.yaml
     * 非空可读、方案表回读一致 → 旧目录 rename 为备份、候选 rename 为
     * 目标。换位前任一步失败：删候选与日志、旧目录原样；换位失败：
     * 日志保留，须由 recoverIncompleteTransactions 恢复。
     * 规划 installFiles 非空但实际落盘为零时整体失败（NOTHING_INSTALLED）；
     * 中途任一文件拷失败即整体失败，不存在部分成功。
     */
    fun installTransaction(
        stagingDir: File,
        targetDir: File,
        plan: SchemeImportPlan,
        stagedEntries: List<StagedEntry>,
        schemaList: List<String>? = null,
        operationId: String? = null,
        /**
         * 旧 custom 全文（换位前由调用方读好）：非空时候选 custom 用 patch 语义
         * 生成（保留非表内容，与今天 importPackage 的 patch 口径一致）；
         * 空时用 whole-file 重建。调用方不传则一律重建。
         */
        existingCustomText: String? = null,
        /**
         * union 合并：候选除安装文件与继承保护文件外，再继承旧目标全部
         * 独有文件（不含 default.custom.yaml，它由方案表步骤重建）；
         * 含 build/（DESIGN“切换时绝不删各目录 build/”同口径）。
         * 默认 false（确认页流程不改语义）。
         */
        unionMerge: Boolean = false,
    ): InstallTransactionResult {
        val opId = operationId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val parentDir = targetDir.parentFile
            ?: return InstallTransactionResult.Failed(
                TransactionFailure.PREVALIDATION,
                "target has no parent dir",
            )
        if (!parentDir.isDirectory && !parentDir.mkdirs()) {
            return InstallTransactionResult.Failed(
                TransactionFailure.PREVALIDATION,
                "parent dir not creatable: ${parentDir.absolutePath}",
            )
        }
        if (targetDir.exists() && !targetDir.isDirectory) {
            return InstallTransactionResult.Failed(
                TransactionFailure.PREVALIDATION,
                "target blocked by file: ${targetDir.absolutePath}",
            )
        }
        val candidateDir = File(parentDir, ".install-$opId")
        val backupDir = File(parentDir, ".backup-$opId")
        val journalFile = journalFileFor(parentDir, opId)
        if (candidateDir.exists() || backupDir.exists() || journalFile.exists()) {
            return InstallTransactionResult.Failed(
                TransactionFailure.PREVALIDATION,
                "stale transaction dirs for operationId: $opId",
            )
        }
        // 预校验：全部条目先验，不合格一个都不装（杜绝静默 continue 部分安装）。
        if (plan.installFiles.size != plan.installFiles.toSet().size) {
            return InstallTransactionResult.Failed(
                TransactionFailure.PREVALIDATION,
                "duplicate install entries",
            )
        }
        val stagedSizeByRel = stagedEntries.associateBy(
            { it.relPath },
            { it.sizeBytes },
        )
        val candidateCanonicalBase = runCatching {
            candidateDir.canonicalPath
        }.getOrNull()
            ?: return InstallTransactionResult.Failed(
                TransactionFailure.PREVALIDATION,
                "candidate path not resolvable",
            )
        for (rel in plan.installFiles) {
            val dest = runCatching {
                File(candidateDir, rel).canonicalFile
            }.getOrNull()
            if (dest == null ||
                !dest.path.startsWith(candidateCanonicalBase + File.separator)
            ) {
                return InstallTransactionResult.Failed(
                    TransactionFailure.PREVALIDATION,
                    "install entry outside candidate tree: $rel",
                )
            }
            val src = File(stagingDir, plan.wrapperPrefix + rel)
            if (!src.isFile) {
                return InstallTransactionResult.Failed(
                    TransactionFailure.PREVALIDATION,
                    "install source missing: $rel",
                )
            }
            if (plan.wrapperPrefix + rel !in stagedSizeByRel) {
                return InstallTransactionResult.Failed(
                    TransactionFailure.PREVALIDATION,
                    "install entry missing staged size: $rel",
                )
            }
        }
        val effectiveSchemaList = (schemaList
            ?: plan.defaultCustomSchemaIds.ifEmpty { plan.schemes.map { it.id } })
            // 方案表写盘口径与 patch 一致：白名单外 id 不进 custom（今天
            // writeEnabledCustomFile 同样过滤；镜像与选方案仍用调用方原表）。
            .filter { SchemeEnabled.isValidSchemaId(it) }
        if (!candidateDir.mkdirs()) {
            return InstallTransactionResult.Failed(
                TransactionFailure.PREVALIDATION,
                "candidate dir not creatable",
            )
        }
        fun failBeforeSwap(
            failure: TransactionFailure,
            message: String,
        ): InstallTransactionResult.Failed {
            candidateDir.deleteRecursively()
            journalFile.delete()
            return InstallTransactionResult.Failed(failure, message)
        }
        writeJournal(
            journalFile, opId, targetDir, candidateDir, backupDir,
            JournalStage.CANDIDATE_CREATED,
        )
        // 拷包文件：任一失败即停，整体失败（已拷的随候选一起删）。
        // 首文件即失败 → 落盘为零，走 NOTHING_INSTALLED（原守卫的事务版）；
        // 后续文件失败 → COPY_FAILED；两者都不存在部分成功。
        val installed = mutableListOf<String>()
        var copyFailure: String? = null
        for (rel in plan.installFiles) {
            try {
                val src = File(stagingDir, plan.wrapperPrefix + rel)
                val dest = File(candidateDir, rel)
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = false)
                installed.add(rel)
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                if (e is CancellationException) throw e
                copyFailure = "copy failed: ${e.message}"
                break
            }
        }
        if (copyFailure != null) {
            return failBeforeSwap(
                if (installed.isEmpty()) TransactionFailure.NOTHING_INSTALLED
                else TransactionFailure.COPY_FAILED,
                copyFailure,
            )
        }
        val inherited = try {
            inheritProtected(targetDir, candidateDir)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            if (e is CancellationException) throw e
            return failBeforeSwap(
                TransactionFailure.COPY_FAILED,
                "inherit failed: ${e.message}",
            )
        }
        // union 合并：旧目标独有文件全量继承（不含已装文件、已继承文件与
        // default.custom.yaml）；任一失败整体失败，候选随失败删。
        val preserved = if (unionMerge) {
            try {
                inheritPreserved(
                    targetDir,
                    candidateDir,
                    plan.installFiles.toSet(),
                    inherited.toSet(),
                )
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                if (e is CancellationException) throw e
                return failBeforeSwap(
                    TransactionFailure.COPY_FAILED,
                    "preserve failed: ${e.message}",
                )
            }
        } else {
            emptyList()
        }
        // 生成方案表（压过继承来的旧 default.custom.yaml）；空表则删文件。
        // 旧 custom 非空时用 patch 语义（保留非表内容），否则 whole-file 重建。
        val customFile = File(candidateDir, "default.custom.yaml")
        try {
            if (effectiveSchemaList.isEmpty()) {
                customFile.delete()
            } else if (existingCustomText != null) {
                customFile.writeText(
                    SchemeEnabled.patchSchemaListYaml(existingCustomText, effectiveSchemaList),
                )
            } else {
                customFile.writeText(buildDefaultCustomYamlContent(effectiveSchemaList))
            }
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            if (e is CancellationException) throw e
            return failBeforeSwap(
                TransactionFailure.COPY_FAILED,
                "schema list write failed: ${e.message}",
            )
        }
        writeJournal(
            journalFile, opId, targetDir, candidateDir, backupDir,
            JournalStage.FILES_INSTALLED,
        )
        // 校验：零落盘守卫、逐项存在与大小、方案 yaml 非空可读、方案表回读。
        if (plan.installFiles.isNotEmpty() && installed.isEmpty()) {
            return failBeforeSwap(
                TransactionFailure.NOTHING_INSTALLED,
                "plan non-empty but nothing installed",
            )
        }
        for (rel in installed) {
            val dest = File(candidateDir, rel)
            val expected = stagedSizeByRel.getValue(plan.wrapperPrefix + rel)
            if (!dest.isFile || dest.length() != expected) {
                return failBeforeSwap(
                    TransactionFailure.VERIFICATION_FAILED,
                    "installed file mismatch: $rel",
                )
            }
        }
        for (rel in installed) {
            if (!rel.endsWith(".schema.yaml")) continue
            val text = runCatching { File(candidateDir, rel).readText() }.getOrNull()
            if (text.isNullOrBlank()) {
                return failBeforeSwap(
                    TransactionFailure.VERIFICATION_FAILED,
                    "schema yaml unreadable: $rel",
                )
            }
            // 复用既有行级解析：解析本身不抛，读到空文本已在上一步拦截；
            // 这里只确认解析可执行（字段缺失时规划已用 id 回落，不另判）。
            parseSchemaYamlFields(text)
        }
        if (effectiveSchemaList.isNotEmpty()) {
            val roundTrip = runCatching {
                parseSchemaListFromCustomYaml(customFile.readText())
            }.getOrNull()
            if (roundTrip != effectiveSchemaList) {
                return failBeforeSwap(
                    TransactionFailure.VERIFICATION_FAILED,
                    "schema list round-trip mismatch",
                )
            }
        } else if (customFile.exists()) {
            return failBeforeSwap(
                TransactionFailure.VERIFICATION_FAILED,
                "empty schema list but custom file present",
            )
        }
        writeJournal(
            journalFile, opId, targetDir, candidateDir, backupDir,
            JournalStage.VERIFIED,
        )
        // 换位：旧目录 → 备份，候选 → 目标（同父目录 rename）。
        if (targetDir.exists()) {
            if (!targetDir.renameTo(backupDir)) {
                return InstallTransactionResult.Failed(
                    TransactionFailure.SWAP_FAILED,
                    "rename target to backup failed",
                )
            }
            writeJournal(
                journalFile, opId, targetDir, candidateDir, backupDir,
                JournalStage.TARGET_MOVED,
            )
        }
        if (!candidateDir.renameTo(targetDir)) {
            // 日志保留在 TARGET_MOVED（或 VERIFIED，目标本就不存在时），
            // 供 recoverIncompleteTransactions 恢复到完整旧版。
            return InstallTransactionResult.Failed(
                TransactionFailure.SWAP_FAILED,
                "rename candidate to target failed",
            )
        }
        writeJournal(
            journalFile, opId, targetDir, candidateDir, backupDir,
            JournalStage.SWAPPED,
        )
        return InstallTransactionResult.Success(
            InstallTransaction(
                operationId = opId,
                targetDir = targetDir,
                candidateDir = candidateDir,
                backupDir = backupDir,
                journalFile = journalFile,
                installedFiles = installed,
                inheritedFiles = inherited,
                preservedFiles = preserved,
            ),
        )
    }

    /**
     * 回滚：把备份恢复为目标（三次 rename，无 delete 换位），
     * 并清掉引用该备份的日志。备份不存在返回 false；其余失败同样 false。
     * 调用时机：部署/确认失败后，确认新版不要了才调（不可逆地丢弃新版）。
     * 每一步失败都能回到上一步的完整状态，不存在“目标已删一半、
     * 备份还没就位”的中间态：目标 rename 走先失败则目标原样、直接
     * 返回；备份 rename 失败则把刚挪走的目标挪回来再返回；最后删
     * 挪走的旧目标，删失败只是残留、不影响正确性。
     */
    fun rollbackTransaction(backupDir: File, targetDir: File): Boolean {
        if (!backupDir.exists()) return false
        val discarded = if (targetDir.exists()) {
            File(targetDir.parentFile, ".discarded-${System.nanoTime()}").also { discardedDir ->
                if (!targetDir.renameTo(discardedDir)) return false
            }
        } else {
            null
        }
        if (!backupDir.renameTo(targetDir)) {
            discarded?.renameTo(targetDir)
            return false
        }
        discarded?.deleteRecursively()
        deleteJournalsReferencing(backupDir)
        return true
    }

    /**
     * 清备份：部署确认后删备份与引用它的日志。备份已不存在时仍算成功
     * （幂等，日志照清）；删除失败返回 false。
     */
    fun cleanupBackup(backupDir: File): Boolean {
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
            if (backupDir.exists()) return false
        }
        deleteJournalsReferencing(backupDir)
        return true
    }

    private fun deleteJournalsReferencing(backupDir: File) {
        val parentDir = backupDir.parentFile ?: return
        parentDir.listFiles()
            ?.filter { it.name.startsWith(".install-") && it.name.endsWith(".journal") }
            ?.forEach { journal ->
                if (parseJournal(journal)?.backup?.absolutePath == backupDir.absolutePath) {
                    journal.delete()
                }
            }
    }

    /** 恢复结果：一次日志对应一条；restoredOld=true 表示恢复到了完整旧版。 */
    data class RecoveredTransaction(
        val operationId: String,
        /** true=恢复到完整旧版（或装前无目录态），false=已是完整新版、原样保留。 */
        val restoredOld: Boolean,
    )

    /**
     * 恢复未完成事务：扫描父目录下全部 .install-*.journal。
     * - 阶段在换位前（CANDIDATE_CREATED / FILES_INSTALLED / VERIFIED）：
     *   目标还在 → 删候选与日志（旧版原样）；目标缺失且备份在 → 备份
     *   移回目标、删候选与日志；目标与备份都不在 → 删候选与日志
     *   （回到装前无目录态）；
     * - 阶段 TARGET_MOVED（旧目录已移走、候选未就位）：备份移回目标、
     *   删候选与日志，恢复到完整旧版（不擅自补完换位，因为安装从未成功上报）；
     *   备份移回失败时日志与备份/候选原样保留、不上报 restoredOld，
     *   留待下次启动重试（绝不删日志销毁恢复记录）；
     * - 目标已存在时（任一非 SWAPPED 阶段）：删候选与日志；若是
     *   TARGET_MOVED 且备份还在，备份随日志一并清掉——目标已重建，
     *   旧备份已无人认领（sweep 只认 SWAPPED），不删即成永久孤儿；
     * - 阶段 SWAPPED：已是完整新版，备份与日志原样保留（供回滚/清理用）；
     * - 未知阶段：按“目标在则删候选、目标缺失且备份在则备份移回”处置；
     * - 日志无法解析：保留文件并记诊断日志，不上报、不阻塞其他日志。
     * 绝不留“目标缺失且候选与备份都不在”的半吊子之外的状态：该情形
     * 本身已是装前无目录态，删日志即完成。本函数只在无安装进行时调用
     * （启动时、或 SWAP_FAILED 后重试前）。
     */
    fun recoverIncompleteTransactions(parentDir: File): List<RecoveredTransaction> {
        if (!parentDir.isDirectory) return emptyList()
        val results = mutableListOf<RecoveredTransaction>()
        val journals = parentDir.listFiles()
            ?.filter { it.name.startsWith(".install-") && it.name.endsWith(".journal") }
            ?.sortedBy { it.name }
            .orEmpty()
        for (journal in journals) {
            // 日志无法解析（截断/缺键）：保留文件并记诊断日志，不上报、
            // 不阻塞其他日志恢复。删日志会丢掉可能是唯一线索的恢复记录，
            // 且写日志已改 tmp＋rename，半截日志本不应再出现；残留的
            // 坏日志以后每次启动都会被重扫跳过，不影响其他恢复。
            val parsed = parseJournal(journal) ?: run {
                Timber.w("Unparseable install journal kept for diagnosis: ${journal.absolutePath}")
                continue
            }
            when (parsed.stage) {
                JournalStage.SWAPPED.name -> {
                    // 完整新版：保留备份与日志，不动。
                    results.add(RecoveredTransaction(parsed.operationId, restoredOld = false))
                }
                else -> {
                    if (parsed.target.exists()) {
                        // TARGET_MOVED 且目标已重建（同进程重试导入成功之
                        // 后）：旧备份已无人认领——sweep 只认 SWAPPED，本
                        // 分支删日志后它即成永久孤儿，故随日志一并清掉。
                        // 删不动则整条日志原样保留、下次启动重试。
                        if (parsed.stage == JournalStage.TARGET_MOVED.name &&
                            parsed.backup.exists()
                        ) {
                            parsed.backup.deleteRecursively()
                            if (parsed.backup.exists()) continue
                        }
                        if (parsed.candidate.exists()) parsed.candidate.deleteRecursively()
                        journal.delete()
                        results.add(
                            RecoveredTransaction(parsed.operationId, restoredOld = true),
                        )
                    } else if (parsed.backup.exists()) {
                        // 备份移回失败时绝不删日志：否则备份失去日志索引、
                        // 后续启动不再认它。失败时整条状态原样保留
                        // （不上报 restoredOld），留待下次启动重试。
                        if (!parsed.backup.renameTo(parsed.target)) continue
                        if (parsed.candidate.exists()) parsed.candidate.deleteRecursively()
                        journal.delete()
                        results.add(
                            RecoveredTransaction(parsed.operationId, restoredOld = true),
                        )
                    } else {
                        if (parsed.candidate.exists()) parsed.candidate.deleteRecursively()
                        journal.delete()
                        results.add(
                            RecoveredTransaction(parsed.operationId, restoredOld = true),
                        )
                    }
                }
            }
        }
        return results
    }

    /**
     * SWAPPED 备份扫尾（启动时）：指针已不在该目录的 SWAPPED 备份+日志可删
     * （新版已确认或用户已切走）；指针仍在该目录的保留（部署未确认时保旧版）。
     * 返回删除的备份数。不可解析的日志不动（由 recoverIncompleteTransactions 处置）。
     */
    fun sweepSwappedBackups(parentDir: File, currentDirName: String): Int {
        if (!parentDir.isDirectory) return 0
        var cleaned = 0
        parentDir.listFiles()
            ?.filter { it.name.startsWith(".install-") && it.name.endsWith(".journal") }
            ?.forEach { journal ->
                val parsed = parseJournal(journal) ?: return@forEach
                if (parsed.stage != JournalStage.SWAPPED.name) return@forEach
                if (parsed.target.name == currentDirName) return@forEach
                if (parsed.backup.exists()) {
                    parsed.backup.deleteRecursively()
                    if (parsed.backup.exists()) return@forEach
                }
                journal.delete()
                cleaned++
            }
        return cleaned
    }

    /**
     * 某目录部署成功后的残留清理：该目录的 SWAPPED 备份已由本次部署成功确认，
     * 删备份+日志。返回删除的备份数。
     */
    fun cleanupSwappedBackupsForDir(parentDir: File, dirName: String): Int {
        if (!parentDir.isDirectory) return 0
        var cleaned = 0
        parentDir.listFiles()
            ?.filter { it.name.startsWith(".install-") && it.name.endsWith(".journal") }
            ?.forEach { journal ->
                val parsed = parseJournal(journal) ?: return@forEach
                if (parsed.stage != JournalStage.SWAPPED.name) return@forEach
                if (parsed.target.name != dirName) return@forEach
                if (parsed.backup.exists()) {
                    parsed.backup.deleteRecursively()
                    if (parsed.backup.exists()) return@forEach
                }
                journal.delete()
                cleaned++
            }
        return cleaned
    }

    private fun mapExtractFailure(reason: ExtractFailure): ImportFailure = when (reason) {
        ExtractFailure.NOT_ZIP -> ImportFailure.NOT_ZIP
        ExtractFailure.ZIP_TOO_LARGE,
        ExtractFailure.ENTRY_TOO_LARGE,
        ExtractFailure.TOTAL_TOO_LARGE,
        -> ImportFailure.TOO_LARGE
        ExtractFailure.TOO_MANY_ENTRIES,
        ExtractFailure.ENTRY_PATH_TOO_LONG,
        ExtractFailure.ENTRY_PATH_TOO_DEEP,
        ExtractFailure.DUPLICATE_ENTRY,
        ExtractFailure.ENTRY_CONFLICT,
        -> ImportFailure.BAD_PACKAGE
        ExtractFailure.IO_ERROR -> ImportFailure.IO_ERROR
    }

    private fun mapPlanFailure(reason: PlanFailure): ImportFailure = when (reason) {
        PlanFailure.ZIP_TOO_LARGE,
        PlanFailure.ENTRY_TOO_LARGE,
        PlanFailure.TOTAL_TOO_LARGE,
        -> ImportFailure.TOO_LARGE
        PlanFailure.NO_SCHEMA -> ImportFailure.NO_SCHEMA
    }

    private fun mapTransactionFailure(failure: TransactionFailure): ImportFailure = when (failure) {
        // SWAP_FAILED 同样走 IO_ERROR：可见恢复路径是启动恢复（AppStartup 接线），
        // 换位失败 → 下次启动恢复到旧版可用。
        TransactionFailure.PREVALIDATION,
        TransactionFailure.NOTHING_INSTALLED,
        TransactionFailure.COPY_FAILED,
        TransactionFailure.VERIFICATION_FAILED,
        TransactionFailure.SWAP_FAILED,
        -> ImportFailure.IO_ERROR
    }

    /**
     * UI 导入统一事务编排（runImport 唯一入口，替代原位 importPackage）：
     * 解压→规划→事务换位→切指针→镜像→换位后注入→部署→选首位新方案。
     * 失败语义：换位前任何失败目标原样；换位后注入任一文件 skipped 时
     * 在部署前返回 IO_ERROR（不部署、不选方案，不静默成功）；此时已换位
     * 内容与 SWAPPED 备份/日志原样保留，指针＋镜像已切新目录且本路径
     * 不回滚（调用方仅在 DEPLOY_FAILED/DEPLOY_TIMEOUT 时回滚指针＋镜像）。
     * 部署 FAILED 时内容回滚（备份恢复）；部署超时只由调用方回滚指针+镜像，
     * 内容不动（孤儿仍在跑，绝不能删它脚下的目录）。超时孤儿随后完成时
     * 由引擎侧孤儿收尾当场对齐（RimeEngine deployAndAwait 作业体在发
     * Finish 前重读指针＋镜像、变了则重编当前指针目录），不一致不留到
     * 下次部署；本函数的部署 lambda 只返回一次结果，仍无孤儿完成回调。
     * 暂存 staging 成功失败都删（extractZip 失败时已自删，finally 删是幂等）；
     * 成功路径调 cleanupBackup + 清同目录 SWAPPED 残留；暂存 zip 由调用方 finally 删。
     * 取消重抛（部署等待处交付），不转失败 toast。
     */
    suspend fun importPackageTransaction(
        zipFile: File,
        fallbackName: String,
        schemeDirsRoot: File,
        sharedDir: File,
        cacheDir: File,
        writeMirror: (List<String>) -> Unit,
        setCurrentDir: (String) -> Unit,
        deploy: suspend (Long) -> DeployAwaitResult,
        selectSchema: (String) -> Unit,
    ): ImportOutcome {
        try {
            if (!zipFile.isFile) return ImportOutcome.Failed(ImportFailure.IO_ERROR)
            // UI 层 256MiB 压缩包预检（保留口径；旧管线 512MiB 不得放宽到此处）。
            if (zipFile.length() > MAX_IMPORT_ZIP_BYTES) {
                return ImportOutcome.Failed(ImportFailure.TOO_LARGE)
            }
            val stagingDir = File(
                cacheDir,
                "import-staging-" +
                    java.util.UUID.randomUUID().toString().replace("-", "").take(12),
            )
            try {
                val extract = extractZip(zipFile, stagingDir)
                if (extract is ExtractResult.Failed) {
                    return ImportOutcome.Failed(mapExtractFailure(extract.reason))
                }
                val extracted = (extract as ExtractResult.Ok).extracted
                val staged = stagedPackageOf(stagingDir, extracted.entries)
                // 包装目录与今天 inspectZip 同口径（stripTopWrapper 规则与
                // stripTopDir 等价：全部条目共享单一顶段才剥）。
                val rawPaths = extracted.entries.map { it.relPath }
                val stripped = stripTopWrapper(rawPaths)
                val topDir = if (stripped != rawPaths) {
                    rawPaths.first().substringBefore('/')
                } else {
                    null
                }
                val dirName = determineDirName(topDir, fallbackName)
                val targetDir = File(schemeDirsRoot, dirName)
                val existingUserFiles = listRelativeFiles(targetDir)
                val existingSharedSchemeIds =
                    discoverSchemes(sharedDir, sharedDir).map { it.id }.toSet()
                val preSkipped = extracted.skippedTraversal.map {
                    SkippedFile(it, SkipReason.TRAVERSAL)
                }
                val planResult = planInstall(
                    packageName = fallbackName,
                    zipBytes = zipFile.length(),
                    staged = staged,
                    preSkipped = preSkipped,
                    existingUserFiles = existingUserFiles,
                    existingSharedSchemeIds = existingSharedSchemeIds,
                    registry = SchemePackageRegistry(emptyList()),
                    dir = dirName,
                    forceSharedOverwrite = true,
                )
                if (planResult is PlanResult.Failed) {
                    return ImportOutcome.Failed(mapPlanFailure(planResult.reason))
                }
                val plan = (planResult as PlanResult.Success).plan
                // 根级新方案（zip 顺序，与今天 newSchemaIds 同口径）：
                // 嵌套方案文件照常落盘（今天同样装），但 id 不进启用表
                // （磁盘发现只根扫描，嵌套 id 引擎找不到文件）。
                val newSchemaIds = stripped.mapNotNull { rel ->
                    if (!rel.endsWith(".schema.yaml") || rel.contains('/')) return@mapNotNull null
                    val stem = rel.removeSuffix(".schema.yaml")
                    if (stem.isEmpty()) null else stem
                }
                if (newSchemaIds.isEmpty()) {
                    return ImportOutcome.Failed(ImportFailure.NO_SCHEMA)
                }
                val oldEnabled = if (targetDir.isDirectory) {
                    val diskIds = discoverSchemes(targetDir, sharedDir).map { it.id }
                    effectiveEnabledIds(targetDir, sharedDir, diskIds)
                } else {
                    emptyList()
                }
                val enabled = (newSchemaIds + oldEnabled).distinct()
                val existingCustomText = File(targetDir, "default.custom.yaml")
                    .takeIf { it.isFile }
                    ?.let { runCatching { it.readText() }.getOrNull() }
                val tx = installTransaction(
                    stagingDir = stagingDir,
                    targetDir = targetDir,
                    plan = plan,
                    stagedEntries = extracted.entries,
                    schemaList = enabled,
                    existingCustomText = existingCustomText,
                    unionMerge = true,
                )
                if (tx is InstallTransactionResult.Failed) {
                    return ImportOutcome.Failed(
                        mapTransactionFailure(tx.failure),
                        RuntimeException(tx.message),
                    )
                }
                val transaction = (tx as InstallTransactionResult.Success).transaction
                // 换位后、部署前：切指针→镜像（D2 顺序），再注入（D13a）。
                setCurrentDir(dirName)
                writeMirror(enabled)
                val inject = injectT9Fields(targetDir, newSchemaIds)
                val optionsInject = injectOptionsSegments(targetDir, newSchemaIds)
                // 换位后注入成功路径校验：任一文件 skipped 即整体失败，
                // 不静默成功、不部署、不选方案。此时部署尚未启动，故无
                // 孤儿风险；已换位内容与 SWAPPED 备份/日志原样保留
                // （与 DEPLOY_FAILED 的新目录孤儿口径一致）；指针＋镜像
                // 已切新目录，本路径不回滚（调用方仅在 DEPLOY_* 时回滚）。
                val injectSkipped = (inject.skipped + optionsInject.skipped).distinct()
                if (injectSkipped.isNotEmpty()) {
                    return ImportOutcome.Failed(
                        ImportFailure.IO_ERROR,
                        RuntimeException("inject skipped: ${injectSkipped.joinToString(",")}"),
                    )
                }
                // 部署超时按已安装字节和（D3b），不含跳过文件与注入增量。
                val sizeByRaw = extracted.entries.associate { it.relPath to it.sizeBytes }
                val installedBytes = transaction.installedFiles.sumOf {
                    sizeByRaw[plan.wrapperPrefix + it] ?: 0L
                }
                val deployed = deploy(deployTimeoutMs(installedBytes))
                if (deployed == DeployAwaitResult.TIMEOUT_RUNNING) {
                    // 超时：内容不动、备份与日志保留（SWAPPED 设计），只由调用方
                    // 回滚指针+镜像；孤儿完成不写指针与镜像。孤儿随后完成时
                    // 由引擎侧孤儿收尾当场对齐（见 RimeEngine deployAndAwait），
                    // 本函数无孤儿完成回调，对齐不在此处做。
                    return ImportOutcome.Failed(ImportFailure.DEPLOY_TIMEOUT)
                }
                if (deployed != DeployAwaitResult.COMPLETED) {
                    // 仅 FAILED 内容回滚（作业已结束，安全）；回滚失败也照常
                    // 返回 DEPLOY_FAILED（调用方仍回滚指针+镜像，内容退化到今天口径）。
                    rollbackTransaction(transaction.backupDir, targetDir)
                    return ImportOutcome.Failed(ImportFailure.DEPLOY_FAILED)
                }
                cleanupBackup(transaction.backupDir)
                cleanupSwappedBackupsForDir(schemeDirsRoot, dirName)
                selectSchema(newSchemaIds[0])
                return ImportOutcome.Success(
                    dirName,
                    newSchemaIds,
                    inject.injected,
                    inject.skipped + optionsInject.skipped,
                )
            } finally {
                stagingDir.deleteRecursively()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ImportOutcome.Failed(ImportFailure.IO_ERROR, e)
        }
    }
}
