// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import android.content.Context
import android.content.SharedPreferences
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.base.util.appContext
import java.io.File
import timber.log.Timber

/**
 * 多目录模型（Lee 2026-10-25 定，推翻单目录 Model A）：
 * 每套方案住一个独立子目录，目录 = 方案集。原 user/ 改为方案目录根，
 * 引擎 user 目录 = 根 / 当前目录名（DataManager.userDataDir）。
 *
 * - 当前目录名存偏好（scheme_dirs/current_dir），缺失或非法时回落 [INITIAL_DIR]；
 * - 每个目录自带 default.custom.yaml，其 schema_list = 该目录的方案集
 *   （引擎部署只认这个表；空目录无此文件时引擎回落 shared 层内置 Lite）；
 * - 旧单目录用户：首次读取时把 user/ 下的平铺内容（build/ 除外，部署可再生）
 *   移进 [INITIAL_DIR]，已启用顺序同步迁到该目录的分目录键；迁移只做
 *   renameTo（同父目录原子移动），失败不置位、下次重试；
 * - 目标已存在的冲突项不再静默跳过：内容相同（文件大小 + SHA-256，
 *   目录按相对路径逐文件比对）只清理源；内容不同则目标与源双方保留，
 *   源移进根下 [MIGRATION_BACKUP_DIR]；任一步失败都不写总标志，
 *   并把原因写进 [KEY_MIGRATION_ERROR] 供设置页显示（成功时清除）；
 * - 本文件只有 [migrationPlan]、[migrationMoves]、[sanitizeDirName]、
 *   [isValidDirName]、[schemesInDir]、[writeDefaultCustomSchemaList]、
 *   [sameContent] 是纯逻辑（可 JVM 单测），其余走 SharedPreferences /
 *   磁盘，需 Android 环境；[ensureMigrated] 的 JVM 可测核心是接受
 *   [MigrationStore] 的内部重载。
 */
object SchemeDirs {
    const val PREFS_NAME = "scheme_dirs"
    private const val KEY_CURRENT_DIR = "current_dir"
    private const val KEY_MIGRATED = "migrated_to_dirs"

    /** 迁移失败原因（设置页显示用）：成功时清除，失败时覆盖为最新原因。 */
    const val KEY_MIGRATION_ERROR = "migration_error"

    /** 存量平铺内容迁入的初始目录名（浏览器里显示为“默认”，见界面层映射）。 */
    const val INITIAL_DIR = "default"

    /** 冲突备份目录名（根下隐藏目录，浏览器与迁移规划均排除）。 */
    const val MIGRATION_BACKUP_DIR = ".migration-backup"

    /** 目录名净化：只留字母数字与 . _ -，与包名净化同口径；空结果回落初始目录。 */
    fun sanitizeDirName(raw: String): String {
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("").trim('.', '_')
        return cleaned.ifEmpty { INITIAL_DIR }
    }

    fun isValidDirName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name == sanitizeDirName(name)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前目录名：偏好缺失或非法时回落初始目录（目录被删时由调用方 mkdirs 重建空目录）。 */
    fun currentDirName(context: Context = appContext): String {
        val raw = prefs(context).getString(KEY_CURRENT_DIR, null)
        return if (raw.isNullOrBlank() || !isValidDirName(raw)) INITIAL_DIR else raw
    }

    fun setCurrentDirName(context: Context, name: String) {
        val safe = if (isValidDirName(name)) name else INITIAL_DIR
        prefs(context).edit().putString(KEY_CURRENT_DIR, safe).apply()
    }

    /** 最近一次迁移失败的原因，成功或从未失败时为 null（设置页显示接线见后续屏幕施工）。 */
    fun migrationError(context: Context): String? =
        prefs(context).getString(KEY_MIGRATION_ERROR, null)

    /**
     * 导入落盘的目录名：包名净化后即目录名；但初始目录与 build 名受保护
     * （初始目录是存量集，绝不能被导入替换；build 在根下会被误认作目录），
     * 命中时加 -pkg 后缀。
     */
    fun importDirName(packageName: String): String {
        val name = sanitizeDirName(packageName)
        return if (name == INITIAL_DIR || name == "build") "$name-pkg" else name
    }

    /** 根下的方案目录（仅子目录，按名排序；平铺残留文件、隐藏目录与 build 不计）。 */
    fun listDirs(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** 目录的方案 id：优先 default.custom.yaml 的 schema_list 顺序，缺失时按文件名排序。 */
    fun schemesInDir(dir: File): List<String> {
        val customFile = File(dir, "default.custom.yaml")
        if (customFile.isFile) {
            val ids = runCatching {
                SchemePackageImport.parseSchemaListFromCustomYaml(customFile.readText())
            }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) return ids
        }
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.mapNotNull { file ->
                file.name.removeSuffix(".schema.yaml")
                    .takeIf { file.name.endsWith(".schema.yaml") }
            }
            ?.sorted()
            .orEmpty()
    }

    /**
     * 写目录的 default.custom.yaml：patch 包裹的 schema_list（librime 用户层
     * 覆盖口径）。空表时删文件（引擎回落 shared 层内置 Lite）。
     */
    fun writeDefaultCustomSchemaList(dir: File, ids: List<String>) {
        val file = File(dir, "default.custom.yaml")
        if (ids.isEmpty()) {
            file.delete()
            return
        }
        dir.mkdirs()
        file.writeText(
            buildString {
                appendLine("patch:")
                appendLine("  schema_list:")
                ids.forEach { appendLine("    - $it") }
            },
        )
    }

    /** 迁移规划：可移动项与目标已存在的冲突项。冲突项绝不静默丢弃。 */
    data class MigrationPlan(
        val moves: List<Pair<File, File>>,
        val conflicts: List<Pair<File, File>>,
    )

    /**
     * 迁移规划（纯函数）：根的每个孩子（build/、初始目录自身、备份目录
     * 除外）→ 初始目录下同名位置；目标已存在时进冲突表（不覆盖、
     * 不删除，由执行侧按内容比对处置）。空根或已是纯目录根时返回空表。
     */
    fun migrationPlan(root: File): MigrationPlan {
        if (!root.isDirectory) return MigrationPlan(emptyList(), emptyList())
        val initial = File(root, INITIAL_DIR)
        val moves = mutableListOf<Pair<File, File>>()
        val conflicts = mutableListOf<Pair<File, File>>()
        for (child in root.listFiles().orEmpty()) {
            if (child.name == "build" ||
                child.name == INITIAL_DIR ||
                child.name == MIGRATION_BACKUP_DIR
            ) {
                continue
            }
            val dest = File(initial, child.name)
            if (dest.exists()) conflicts += child to dest else moves += child to dest
        }
        return MigrationPlan(moves, conflicts)
    }

    /**
     * 迁移移动表（纯函数，兼容旧签名）：即 [migrationPlan] 的 moves 部分；
     * 目标已存在的项不在此表，见 [migrationPlan] 的 conflicts。
     */
    fun migrationMoves(root: File): List<Pair<File, File>> = migrationPlan(root).moves

    /**
     * 内容是否相同：文件比大小 + SHA-256（仓库已有 DigestUtil，不引新依赖）；
     * 目录按相对路径逐项比对（文件比大小 + SHA-256，子目录比结构）；
     * 类型不同或任一摘要读失败都算不同（无法证明相同就不删源，宁可备份）。
     */
    fun sameContent(a: File, b: File): Boolean {
        if (a.isDirectory && b.isDirectory) {
            return runCatching { dirSnapshot(a) == dirSnapshot(b) }.getOrDefault(false)
        }
        if (a.isFile && b.isFile) {
            if (a.length() != b.length()) return false
            return runCatching { DigestUtil.sha256Hex(a) == DigestUtil.sha256Hex(b) }
                .getOrDefault(false)
        }
        return false
    }

    private fun dirSnapshot(dir: File): Map<String, String> {
        val snapshot = mutableMapOf<String, String>()
        dir.walkTopDown().forEach { entry ->
            if (entry == dir) return@forEach
            val rel = entry.relativeTo(dir).path
            snapshot[rel] = if (entry.isDirectory) {
                "dir"
            } else {
                "${entry.length()}:${DigestUtil.sha256Hex(entry)}"
            }
        }
        return snapshot
    }

    /** 迁移状态读写：生产实现走 SharedPreferences，单测用内存假实现。 */
    interface MigrationStore {
        fun isMigrated(): Boolean
        fun setMigrated()
        fun recordError(message: String)
        fun legacyEnabledIds(): String?
        fun dirEnabledIds(): String?
        fun setDirEnabledIds(value: String)
    }

    private class SharedPreferencesMigrationStore(context: Context) : MigrationStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val schemaPrefs = context.getSharedPreferences(
            SchemaManager.PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        override fun isMigrated(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)

        override fun setMigrated() {
            prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_MIGRATION_ERROR).apply()
        }

        override fun recordError(message: String) {
            prefs.edit().putString(KEY_MIGRATION_ERROR, message).apply()
        }

        override fun legacyEnabledIds(): String? =
            schemaPrefs.getString(SchemaManager.KEY_ENABLED_IDS, null)

        override fun dirEnabledIds(): String? =
            schemaPrefs.getString(SchemaManager.enabledKeyFor(INITIAL_DIR), null)

        override fun setDirEnabledIds(value: String) {
            schemaPrefs.edit().putString(SchemaManager.enabledKeyFor(INITIAL_DIR), value).apply()
        }
    }

    /**
     * 执行一次迁移（幂等）：移动、冲突处置、偏好复制读回校验、终检查
     * 全部通过才置位；任一步失败不置位、记录失败原因，下次读取重试。
     * 已启用顺序迁移：分目录键缺失且旧全局键存在时，把旧值抄到初始目录键
     * 并重读校验；分目录键已存在时不动（用户在初始目录的已有选择优先）。
     * 已置位时直接返回（原有行为保留）。
     */
    @Synchronized
    fun ensureMigrated(root: File, context: Context = appContext) {
        ensureMigrated(root, SharedPreferencesMigrationStore(context))
    }

    @Synchronized
    internal fun ensureMigrated(
        root: File,
        store: MigrationStore,
        planOf: (File) -> MigrationPlan = ::migrationPlan,
    ) {
        if (store.isMigrated()) return
        val plan = try {
            planOf(root)
        } catch (e: Exception) {
            val reason = "migration plan failed: ${e.message}"
            Timber.w(reason)
            store.recordError(reason)
            return
        }
        val failure = try {
            runMigration(root, plan, store)
        } catch (e: Exception) {
            "migration failed: ${e.message}"
        }
        if (failure != null) {
            Timber.w("SchemeDirs migration failed: $failure")
            store.recordError(failure)
            return
        }
        store.setMigrated()
    }

    /**
     * 执行一次已规划的迁移，返回失败原因，成功返回 null。
     * 失败原因字符串同时是写进 [KEY_MIGRATION_ERROR] 的显示文本。
     */
    internal fun runMigration(root: File, plan: MigrationPlan, store: MigrationStore): String? {
        val initial = File(root, INITIAL_DIR)
        if (initial.exists() && !initial.isDirectory) {
            return "initial dir blocked by file: $INITIAL_DIR"
        }
        if (plan.moves.isNotEmpty() || plan.conflicts.isNotEmpty()) initial.mkdirs()
        for ((src, dest) in plan.moves) {
            dest.parentFile?.mkdirs()
            if (!src.renameTo(dest)) return "move failed: ${src.name}"
        }
        val backupDir = File(root, MIGRATION_BACKUP_DIR)
        for ((src, dest) in plan.conflicts) {
            if (sameContent(src, dest)) {
                val deleted = if (src.isDirectory) src.deleteRecursively() else src.delete()
                if (!deleted && src.exists()) return "conflict cleanup failed: ${src.name}"
            } else if (!preserveAsBackup(src, backupDir)) {
                return "conflict backup failed: ${src.name}"
            }
        }
        // 偏好复制：写后重读，与旧值一致才算成功。
        val legacy = runCatching { store.legacyEnabledIds() }.getOrElse {
            return "enabled ids read failed: ${it.message}"
        }
        val dirBefore = runCatching { store.dirEnabledIds() }.getOrElse {
            return "enabled ids read failed: ${it.message}"
        }
        if (dirBefore == null && legacy != null) {
            runCatching { store.setDirEnabledIds(legacy) }.getOrElse {
                return "enabled ids copy failed: ${it.message}"
            }
            val dirAfterCopy = runCatching { store.dirEnabledIds() }.getOrElse {
                return "enabled ids read failed: ${it.message}"
            }
            if (dirAfterCopy != legacy) return "enabled ids verify failed"
        }
        // 终检查：源平铺目录除 build/、初始目录自身、备份目录外无未处理文件；
        // 分目录启用列表与迁移前状态一致（旧值存在且本轮复制时须等于旧值）。
        if (root.isDirectory) {
            val leftover = root.listFiles().orEmpty().filter { child ->
                child.name != "build" &&
                    child.name != INITIAL_DIR &&
                    child.name != MIGRATION_BACKUP_DIR
            }
            if (leftover.isNotEmpty()) {
                return "leftover files: ${leftover.joinToString { it.name }}"
            }
        }
        if (legacy != null) {
            val dirAfter = runCatching { store.dirEnabledIds() }.getOrElse {
                return "enabled ids read failed: ${it.message}"
            }
            if (dirAfter == null) return "enabled ids missing after migration"
            if (dirBefore == null && dirAfter != legacy) return "enabled ids verify failed"
            if (dirBefore != null && dirAfter != dirBefore) {
                return "enabled ids changed during migration"
            }
        }
        return null
    }

    /**
     * 冲突源备份：源内容移进备份目录（同根 renameTo）；备份名已存在时，
     * 已有备份内容相同则直接清理源（内容已有备份，不重复占盘），不同则
     * 顺延 -1、-2……找空位；全部失败返回 false。
     */
    private fun preserveAsBackup(src: File, backupDir: File): Boolean {
        backupDir.mkdirs()
        var candidate = File(backupDir, src.name)
        var suffix = 0
        while (candidate.exists()) {
            if (sameContent(src, candidate)) {
                val deleted = if (src.isDirectory) src.deleteRecursively() else src.delete()
                return deleted || !src.exists()
            }
            suffix++
            candidate = File(backupDir, "${src.name}-$suffix")
        }
        return src.renameTo(candidate)
    }
}
