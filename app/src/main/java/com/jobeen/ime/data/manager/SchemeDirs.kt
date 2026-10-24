// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import android.content.Context
import android.content.SharedPreferences
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
 *   renameTo（同父目录原子移动），冲突文件不动、不丢数据，失败则下次重试；
 * - 本文件只有 [migrationMoves]、[sanitizeDirName]、[isValidDirName]、
 *   [schemesInDir]、[writeDefaultCustomSchemaList] 是纯逻辑（可 JVM 单测），
 *   其余走 SharedPreferences / 磁盘，需 Android 环境。
 */
object SchemeDirs {
    const val PREFS_NAME = "scheme_dirs"
    private const val KEY_CURRENT_DIR = "current_dir"
    private const val KEY_MIGRATED = "migrated_to_dirs"

    /** 存量平铺内容迁入的初始目录名（浏览器里显示为“默认”，见界面层映射）。 */
    const val INITIAL_DIR = "default"

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

    /**
     * 导入落盘的目录名：包名净化后即目录名；但初始目录与 build 名受保护
     * （初始目录是存量集，绝不能被导入替换；build 在根下会被误认作目录），
     * 命中时加 -pkg 后缀。
     */
    fun importDirName(packageName: String): String {
        val name = sanitizeDirName(packageName)
        return if (name == INITIAL_DIR || name == "build") "$name-pkg" else name
    }

    /** 根下的方案目录（仅子目录，按名排序；平铺残留文件与隐藏目录不计）。 */
    fun listDirs(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
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

    /**
     * 迁移移动表（纯函数）：根的每个孩子（build/ 除外，部署可再生；
     * 初始目录自身除外）→ 初始目录下同名位置；目标已存在时跳过（不覆盖、
     * 不删除，失败留待下次重试）。空根或已是纯目录根时返回空表。
     */
    fun migrationMoves(root: File): List<Pair<File, File>> {
        if (!root.isDirectory) return emptyList()
        val initial = File(root, INITIAL_DIR)
        return root.listFiles().orEmpty().filter { child ->
            child.name != "build" && child.canonicalPath != initial.canonicalPath
        }.mapNotNull { child ->
            val dest = File(initial, child.name)
            if (dest.exists()) null else child to dest
        }
    }

    /**
     * 执行一次迁移（幂等）：移动表全部 renameTo 成功（或无移动）且
     * 已启用顺序迁完后才置位；任一移动失败不置位，下次读取重试。
     * 已启用顺序迁移：分目录键缺失且旧全局键存在时，把旧值抄到初始目录键。
     */
    @Synchronized
    fun ensureMigrated(root: File, context: Context = appContext) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val moves = runCatching { migrationMoves(root) }.getOrDefault(emptyList())
        var failed = false
        if (moves.isNotEmpty()) {
            File(root, INITIAL_DIR).mkdirs()
            for ((src, dest) in moves) {
                dest.parentFile?.mkdirs()
                if (!src.renameTo(dest)) {
                    failed = true
                    Timber.w("SchemeDirs migration failed: ${src.name}")
                }
            }
        }
        if (!failed) {
            runCatching {
                val schemaPrefs = context.getSharedPreferences(
                    SchemaManager.PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
                val dirKey = SchemaManager.enabledKeyFor(INITIAL_DIR)
                if (schemaPrefs.getString(dirKey, null) == null) {
                    schemaPrefs.getString(SchemaManager.KEY_ENABLED_IDS, null)?.let { legacy ->
                        schemaPrefs.edit().putString(dirKey, legacy).apply()
                    }
                }
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }
}
