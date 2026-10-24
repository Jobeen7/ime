// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import java.io.File
import java.util.zip.ZipFile

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

    /** 导入前剩余空间检查的额外余量（部署还会写 build 产物）。 */
    const val FREE_SPACE_MARGIN_BYTES = 16L * 1024 * 1024

    /** 旧版归档区（user 目录下，安装时按包名 + 时间戳分子目录）。 */
    const val ARCHIVE_DIR_NAME = "scheme-archive"

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

    /** 解析 default.custom.yaml 顶层 schema_list（块列表与流式 [a, b] 均支持）。 */
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
            val item = Regex("""^-\s+(\S.*)$""").matchEntire(content)?.groupValues?.get(1)
                ?: continue
            scalarValue(item).takeIf { it.isNotEmpty() }?.let { if (it !in ids) ids.add(it) }
        }
        return ids
    }

    /** 去行尾注释（# 前须有空白或行首）并剥单双引号。 */
    private fun scalarValue(raw: String): String {
        var s = raw.trim()
        var inSingle = false
        var inDouble = false
        var cut = s.length
        var i = 0
        while (i < s.length) {
            val c = s[i]
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
        if (s.length >= 2 && ((s.startsWith("'") && s.endsWith("'")) ||
                (s.startsWith("\"") && s.endsWith("\"")))
        ) {
            s = s.substring(1, s.length - 1)
        }
        return s.trim()
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
        /** 归档文件（旧包登记的文件与现存文件的交集；未登记时回落方案本体）。 */
        val archivedFiles: List<String>,
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
     */
    fun planInstall(
        packageName: String,
        zipBytes: Long,
        staged: StagedPackage,
        preSkipped: List<SkippedFile>,
        existingUserFiles: Set<String>,
        existingSharedSchemeIds: Set<String>,
        registry: SchemePackageRegistry,
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
        val schemaEntries = entries.filter {
            classifyEntry(it.relPath) == EntryKind.SCHEMA
        }
        if (schemaEntries.isEmpty()) return PlanResult.Failed(PlanFailure.NO_SCHEMA)

        val packageVersion = readStaged("version.txt")
            ?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: schemaEntries.mapNotNull {
                parseSchemaYamlFields(readStaged(it.relPath) ?: "").version
            }.maxWithOrNull { a, b -> compareVersions(a, b) }

        val schemes = schemaEntries.map { entry ->
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
        val skippedFiles = preSkipped.toMutableList()
        val sharedDecisions = mutableListOf<SharedFileDecision>()
        val overwrittenExisting = mutableListOf<String>()
        for (entry in entries) {
            val path = entry.relPath
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
                        val existingVersion = registry.versionForFile(path)
                        val unknown = packageVersion == null || existingVersion == null
                        val overwrite = !unknown &&
                            compareVersions(packageVersion, existingVersion) > 0
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

        val replacements = schemes.mapNotNull { scheme ->
            val schemaFile = "${scheme.id}.schema.yaml"
            val inUser = schemaFile in existingUserFiles
            val inShared = scheme.id in existingSharedSchemeIds
            if (!inUser && !inShared) return@mapNotNull null
            val previousPackage = registry.packageForScheme(scheme.id)
            val archivedFiles = if (inUser) {
                val recorded = previousPackage?.let { registry.filesForPackage(it) }.orEmpty()
                    .filter { it in existingUserFiles }
                if (recorded.isNotEmpty()) recorded
                else listOf(schemaFile) +
                    listOf("${scheme.id}.custom.yaml").filter { it in existingUserFiles }
            } else {
                emptyList()
            }
            ReplacementInfo(
                schemeId = scheme.id,
                previousPackage = previousPackage,
                overridesBuiltin = !inUser && inShared,
                archivedFiles = archivedFiles,
            )
        }

        return PlanResult.Success(
            SchemeImportPlan(
                packageName = packageName,
                packageVersion = packageVersion,
                schemes = schemes,
                installFiles = installFiles,
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

    /** 包登记记录：后续期数卸载按集合清的依据（本期只登记、不删）。 */
    data class SchemePackageRecord(
        val packageName: String,
        val version: String?,
        val schemeIds: List<String>,
        val files: List<String>,
        /** 默认 LOCAL：二期已存的四字段旧行解码后同样回落 LOCAL。 */
        val source: PackageSource = PackageSource.LOCAL,
    )

    data class SchemePackageRegistry(val records: List<SchemePackageRecord>) {
        fun packageForScheme(schemeId: String): String? =
            records.firstOrNull { schemeId in it.schemeIds }?.packageName

        /** 方案所属包的来源（URL 包与本地导入在列表来源处区分显示）。 */
        fun sourceForScheme(schemeId: String): PackageSource? =
            records.firstOrNull { schemeId in it.schemeIds }?.source

        fun versionForFile(relPath: String): String? =
            records.firstOrNull { relPath in it.files }?.version

        fun filesForPackage(packageName: String): List<String> =
            records.firstOrNull { it.packageName == packageName }?.files.orEmpty()

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
     * 落盘文件逗号串 / 来源）。Tab 与换行在包名里净化成下划线；Rime 文件名
     * 实际不含逗号，逗号串可接受。解码容错：字段不足的行直接丢弃；来源
     * 字段缺失（二期旧行）或非 url 时一律回落 LOCAL，不改旧行语义。
     */
    fun encodeRegistry(registry: SchemePackageRegistry): String =
        registry.records.joinToString("\n") { record ->
            listOf(
                record.packageName.replace('\t', '_').replace('\n', '_').replace('\r', '_'),
                record.version.orEmpty(),
                record.schemeIds.joinToString(","),
                record.files.joinToString(","),
                if (record.source == PackageSource.URL) "url" else "local",
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

    /** zip 解压结果：暂存目录、条目、解压阶段跳过的穿越条目。 */
    data class ExtractedPackage(
        val stagingDir: File,
        val entries: List<StagedEntry>,
        val skippedTraversal: List<String>,
    )

    enum class ExtractFailure {
        NOT_ZIP,
        ZIP_TOO_LARGE,
        ENTRY_TOO_LARGE,
        TOTAL_TOO_LARGE,
    }

    sealed interface ExtractResult {
        data class Ok(val extracted: ExtractedPackage) : ExtractResult
        data class Failed(val reason: ExtractFailure) : ExtractResult
    }

    /**
     * zip 解到暂存目录（调用方负责事后清理）。体积上限按实际写出字节数
     * 计（不依赖 ZipEntry.size，data-descriptor 条目该值可能为 -1）；
     * 超限时删掉暂存目录并返回失败，不留半解压。路径穿越条目跳过并
     * 记入 skippedTraversal（供规划页告警）；落盘另有 canonical 守卫。
     */
    fun extractZip(zipFile: File, stagingDir: File): ExtractResult {
        if (zipFile.length() > MAX_ZIP_BYTES) return ExtractResult.Failed(ExtractFailure.ZIP_TOO_LARGE)
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        val entries = mutableListOf<StagedEntry>()
        val skippedTraversal = mutableListOf<String>()
        try {
            ZipFile(zipFile).use { zf ->
                var totalBytes = 0L
                for (zipEntry in zf.entries()) {
                    if (zipEntry.isDirectory) continue
                    when (val normalized = normalizeEntryPath(zipEntry.name)) {
                        is NormalizedEntry.Traversal -> {
                            skippedTraversal.add(zipEntry.name)
                            continue
                        }
                        is NormalizedEntry.Ignored -> continue
                        is NormalizedEntry.File -> {
                            val dest = File(stagingDir, normalized.relPath).canonicalFile
                            if (!dest.path.startsWith(stagingDir.canonicalPath + File.separator)) {
                                skippedTraversal.add(zipEntry.name)
                                continue
                            }
                            dest.parentFile?.mkdirs()
                            var entryBytes = 0L
                            zf.getInputStream(zipEntry).use { input ->
                                dest.outputStream().use { out ->
                                    val buf = ByteArray(32 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        entryBytes += n
                                        totalBytes += n
                                        if (entryBytes > MAX_ENTRY_BYTES) {
                                            throw ExtractLimitException(ExtractFailure.ENTRY_TOO_LARGE)
                                        }
                                        if (totalBytes > MAX_EXTRACTED_BYTES) {
                                            throw ExtractLimitException(ExtractFailure.TOTAL_TOO_LARGE)
                                        }
                                        out.write(buf, 0, n)
                                    }
                                }
                            }
                            entries.add(StagedEntry(normalized.relPath, entryBytes))
                        }
                    }
                }
            }
        } catch (e: ExtractLimitException) {
            stagingDir.deleteRecursively()
            return ExtractResult.Failed(e.reason)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            stagingDir.deleteRecursively()
            return ExtractResult.Failed(ExtractFailure.NOT_ZIP)
        }
        return ExtractResult.Ok(ExtractedPackage(stagingDir, entries, skippedTraversal))
    }

    private class ExtractLimitException(val reason: ExtractFailure) : Exception()

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
     * 执行安装规划：先归档（同 ID 替换的旧版文件，目标已存在时首次胜出，
     * 重试不覆盖已归档内容），再落盘。落盘双侧 canonical 守卫，逃出
     * user 目录的条目直接跳过。返回实际落盘文件（供登记记录用）。
     * 本函数不写登记偏好（调用方在成功后写），不碰引擎与部署。
     */
    fun applyPlan(
        stagingDir: File,
        userDir: File,
        archiveDir: File,
        plan: SchemeImportPlan,
    ): List<String> {
        val userCanonical = userDir.canonicalPath
        for (replacement in plan.replacements) {
            for (rel in replacement.archivedFiles) {
                val src = File(userDir, rel).canonicalFile
                if (!src.path.startsWith(userCanonical + File.separator) || !src.isFile) continue
                val dest = File(archiveDir, rel).canonicalFile
                if (!dest.path.startsWith(archiveDir.canonicalPath + File.separator)) continue
                if (dest.exists()) continue
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = false)
            }
        }
        val installed = mutableListOf<String>()
        for (rel in plan.installFiles) {
            val src = File(stagingDir, rel).canonicalFile
            val dest = File(userDir, rel).canonicalFile
            if (!dest.path.startsWith(userCanonical + File.separator)) continue
            if (!src.isFile) continue
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            installed.add(rel)
        }
        return installed
    }
}
