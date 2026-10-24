// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 第四期归档 / 恢复逃生舱：归档规划、恢复规划与清单编解码（纯逻辑，
 * 不依赖 Android 框架，可 JVM 单测；界面与文件 IO 在 SchemaSettingsScreen）。
 *
 * 定位（定稿文档修订版，2026-10-24）：归档 / 恢复只是低频逃生舱，
 * 不是常规切换机制。默认始终是单目录 + selectSchema；本期不给常规
 * 切换加第二套机制，不做按方案集卸载（卸载按集合清是遗留项）。
 *
 * 约束来源：
 * - 归档集存应用私有存储内 profiles 区，复用二期归档位置
 *   user/scheme-archive/<名>-<时间戳>/；四期归档以清单文件存在为
 *   识别标志，与二期同 ID 替换归档（无清单）区分，不混进列表；
 * - 归档 = 当前 user 层方案集快照：除引擎管理文件（user.yaml、
 *   installation.yaml、build/ 产物）与归档区自身外全部收进归档，
 *   含 *.custom.yaml（用户定制属于方案集状态）；*.userdb 单独标记，
 *   绝不以任何其他形式散落在归档之外（不进暂存、不另建备份目录）；
 * - userdb 恢复策略（按最优解）：默认不覆盖现存 userdb（只恢复缺失
 *   的），仅当用户在恢复确认页明确勾选时才覆盖现有 userdb；
 * - 恢复 = 归档集文件回到唯一 user 目录 + 按二期登记口径写回
 *   package_registry + schema_list 重建为恢复集方案 + 内置兜底
 *   （兜底走一期底部 Lite 行机制，不追加进顺序）+ 走一期部署编排
 *   （后台部署 + 进度 + 失败回上一方案）；
 * - 恢复时清理的只能是当前登记里有、且不在归档集内的包文件；
 *   未登记的零散文件不动（引擎只读 schema_list 方案，它们是惰性的）；
 * - 归档重试安全：创建失败删掉未完成目录后可重试；已完成的同名
 *   目录存在时拒绝覆盖（调用方提示用户换名）。
 */
object SchemeArchive {

    /** 四期归档清单文件名（归档目录内，恢复时不拷回 user 目录）。 */
    const val MANIFEST_FILE_NAME = "archive-manifest.txt"

    /** 四期归档登记文件名（归档时登记原文，恢复时原样写回偏好）。 */
    const val REGISTRY_FILE_NAME = "package-registry.txt"

    private const val MANIFEST_VERSION = "v1"

    /** 归档清单：名 + 时间 + 方案 id 串（文件清单以归档目录实测为准）。 */
    data class ArchiveManifest(
        val name: String,
        val createdAtMs: Long,
        val schemeIds: List<String>,
    )

    /** 归档规划：目录名 + 待拷文件（方案文件与 userdb 分开）。 */
    data class ArchivePlan(
        val name: String,
        val dirName: String,
        val createdAtMs: Long,
        val schemeIds: List<String>,
        val files: List<String>,
        val userdbFiles: List<String>,
    )

    /** 恢复规划：拷回 / 覆盖 / 清理 / userdb 处理 / 重建顺序。 */
    data class RestorePlan(
        /** 拷回的方案文件（不含 userdb）。 */
        val restoreFiles: List<String>,
        /** 其中已存在于 user 目录的（将被覆盖数，确认页用）。 */
        val replaceExisting: List<String>,
        /** 当前登记里有、归档集内无的包文件（恢复时删除）。 */
        val cleanFiles: List<String>,
        /** 归档内有、user 目录缺失的 userdb（默认即恢复）。 */
        val userdbRestoreMissing: List<String>,
        /** 归档内有、user 目录已存在的 userdb（默认跳过不覆盖）。 */
        val userdbSkippedExisting: List<String>,
        /** 仅勾选覆盖时才覆盖的已存在 userdb（默认空）。 */
        val userdbOverwrite: List<String>,
        /** 重建后的已启用顺序（恢复集方案 id，去重保序）。 */
        val newOrder: List<String>,
    )

    /** userdb 判定与二期分类同口径：路径含 .userdb 即算。 */
    fun isUserdbPath(relPath: String): Boolean = relPath.contains(".userdb")

    /**
     * 不进归档的文件：归档区自身、build/ 产物、引擎管理的
     * user.yaml / installation.yaml（任何层级同名都排除，与二期
     * PROTECTED 口径一致）。
     */
    fun isExcludedFromArchive(relPath: String): Boolean {
        val name = relPath.substringAfterLast('/')
        return relPath.startsWith(SchemePackageImport.ARCHIVE_DIR_NAME + "/") ||
            relPath.startsWith("build/") ||
            name == "user.yaml" || name == "installation.yaml"
    }

    /** 归档目录名：净化名 + 时间戳（与二期 <包名>-<时间戳> 同格式）。 */
    fun dirNameFor(displayName: String, createdAtMs: Long): String =
        "${SchemePackageImport.sanitizePackageName(displayName)}-$createdAtMs"

    /**
     * 由当前 user 目录文件集生成归档规划。方案 id 为空、名为空、
     * 或无方案文件可归时返回 null（调用方按“无可归档”处理，不建空归档）。
     */
    fun planArchive(
        displayName: String,
        createdAtMs: Long,
        userFiles: Set<String>,
        schemeIds: List<String>,
    ): ArchivePlan? {
        val name = displayName.trim()
        val ids = schemeIds.filter { it.isNotBlank() }.distinct()
        if (name.isEmpty() || ids.isEmpty()) return null
        val kept = userFiles.filter { !isExcludedFromArchive(it) }
        val files = kept.filter { !isUserdbPath(it) }.sorted()
        if (files.isEmpty()) return null
        return ArchivePlan(
            name = name,
            dirName = dirNameFor(name, createdAtMs),
            createdAtMs = createdAtMs,
            schemeIds = ids,
            files = files,
            userdbFiles = kept.filter { isUserdbPath(it) }.sorted(),
        )
    }

    /**
     * 归档目录实测文件拆分：去掉清单与登记文件本身，余下按 userdb
     * 分流。返回（方案文件，userdb 文件）。
     */
    fun splitArchiveListing(allFiles: Set<String>): Pair<List<String>, List<String>> {
        val kept = allFiles.filter {
            it != MANIFEST_FILE_NAME && it != REGISTRY_FILE_NAME
        }
        return kept.filter { !isUserdbPath(it) }.sorted() to
            kept.filter { isUserdbPath(it) }.sorted()
    }

    /** 清单编码：首行版本，其后 key\tvalue 行（名内 Tab / 换行转空格）。 */
    fun encodeManifest(manifest: ArchiveManifest): String {
        val safeName = manifest.name
            .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        return listOf(
            MANIFEST_VERSION,
            "name\t$safeName",
            "created\t${manifest.createdAtMs}",
            "schemes\t${manifest.schemeIds.joinToString(",")}",
        ).joinToString("\n")
    }

    /** 清单解码：版本不对、时间非法、方案 id 为空时一律按无效处理。 */
    fun decodeManifest(text: String?): ArchiveManifest? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines()
        if (lines.firstOrNull() != MANIFEST_VERSION) return null
        var name: String? = null
        var created: Long? = null
        var schemes: List<String>? = null
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) continue
            when (parts[0]) {
                "name" -> name = parts[1].trim().takeIf { it.isNotEmpty() }
                "created" -> created = parts[1].trim().toLongOrNull()
                "schemes" -> schemes =
                    parts[1].split(',').filter { it.isNotEmpty() }.distinct()
            }
        }
        if (name == null || created == null || schemes.isNullOrEmpty()) return null
        return ArchiveManifest(name, created, schemes)
    }

    /** 重建顺序：恢复集方案 id 去重保序（内置兜底不追加进顺序）。 */
    fun rebuildOrder(restoredIds: List<String>): List<String> =
        restoredIds.filter { it.isNotBlank() }.distinct()

    /**
     * 由归档实测文件与当前状态生成恢复规划。
     *
     * @param archiveFiles 归档内方案文件（已拆出 userdb）
     * @param archiveUserdb 归档内 userdb 文件
     * @param currentUserFiles user 目录现存文件
     * @param currentRegistryFiles 当前登记的包文件（清理范围的唯一依据）
     * @param schemeIds 清单记录的恢复集方案 id
     * @param overwriteUserdb 确认页勾选：覆盖已存在的 userdb
     */
    fun planRestore(
        archiveFiles: List<String>,
        archiveUserdb: List<String>,
        currentUserFiles: Set<String>,
        currentRegistryFiles: Set<String>,
        schemeIds: List<String>,
        overwriteUserdb: Boolean,
    ): RestorePlan {
        val restoreFiles = archiveFiles.filter { it.isNotBlank() }.distinct().sorted()
        val restoreSet = restoreFiles.toSet()
        val userdbSet = archiveUserdb.filter { it.isNotBlank() }.distinct().toSet()
        val cleanFiles = currentRegistryFiles
            .filter { it !in restoreSet && it !in userdbSet }
            .filter { !isUserdbPath(it) && !isExcludedFromArchive(it) }
            .sorted()
        val existingUserdb = userdbSet.filter { it in currentUserFiles }
        return RestorePlan(
            restoreFiles = restoreFiles,
            replaceExisting = restoreFiles.filter { it in currentUserFiles },
            cleanFiles = cleanFiles,
            userdbRestoreMissing = (userdbSet - currentUserFiles).sorted(),
            userdbSkippedExisting = if (overwriteUserdb) emptyList() else existingUserdb.sorted(),
            userdbOverwrite = if (overwriteUserdb) existingUserdb.sorted() else emptyList(),
            newOrder = rebuildOrder(schemeIds),
        )
    }
}
