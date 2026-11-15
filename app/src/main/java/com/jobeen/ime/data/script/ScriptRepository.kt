// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.script

import android.content.Context
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.ScriptItem
import com.jobeen.ime.data.database.ScriptSeeds
import com.jobeen.ime.data.database.ScriptTag
import com.jobeen.ime.data.database.ScriptTagCrossRef
import com.jobeen.ime.data.database.ScriptVariable
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 脚本数据仓库（WO52 8.1）：4 张表的 DAO 门面＋种子兜底。
 *
 * 种子三重保障（与 9.1 的 AI 种子同制，任一层失败都不影响首启）：
 * ① 新库 onCreate 回调播种；② 升级库 MIGRATION_16_17 播种；
 * ③ 首次访问时 [ensureSeeded] 发现 scripts 为空即补种。补种用
 * INSERT OR IGNORE，不盖用户改过的数据。
 */
class ScriptRepository(
    private val db: AppDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val items get() = db.scriptItemDao()
    private val tags get() = db.scriptTagDao()
    private val refs get() = db.scriptTagCrossRefDao()
    private val variables get() = db.scriptVariableDao()

    companion object {
        @Volatile
        private var INSTANCE: ScriptRepository? = null

        fun get(context: Context): ScriptRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScriptRepository(AppDatabase.getInstance(context.applicationContext))
                    .also { INSTANCE = it }
            }
    }

    suspend fun ensureSeeded() {
        if (items.count() == 0) {
            db.runInTransaction { db.openHelper.writableDatabase.let { ScriptSeeds.insertAll(it) } }
        }
    }

    /** 首启在后台补种一次（设置页打开时不阻塞等库）。 */
    fun ensureSeededAsync() {
        scope.launch { runCatching { ensureSeeded() } }
    }

    suspend fun getScripts(): List<ScriptItem> = items.getAll()

    /** 新建脚本（默认内容见 [ScriptSeeds.DEFAULT_NEW_SCRIPT_CONTENT]）。 */
    suspend fun insertScript(
        name: String,
        content: String = ScriptSeeds.DEFAULT_NEW_SCRIPT_CONTENT,
        iconName: String = "code",
        allowPasteboard: Boolean = false,
    ): Long {
        val now = System.currentTimeMillis()
        return items.insert(
            ScriptItem(
                uuid = UUID.randomUUID().toString(),
                iconName = iconName,
                iconType = 0,
                name = name,
                content = content,
                isFavorite = false,
                sort = 0,
                date = now,
                allowPasteboard = allowPasteboard,
                resultInsertState = false,
                closeWindowState = false,
                downloadURL = null,
            ),
        )
    }

    suspend fun updateScript(item: ScriptItem): Int = items.update(item)

    suspend fun deleteScript(id: Long): Int = items.deleteById(id)

    suspend fun setFavorite(id: Long, favorite: Boolean): Int {
        val item = items.getById(id) ?: return 0
        return items.update(item.copy(isFavorite = favorite))
    }

    suspend fun getTags(): List<ScriptTag> = tags.getAll()

    suspend fun getTagsForScript(scriptId: Long): List<ScriptTag> {
        val tagIds = refs.getByScriptId(scriptId).map { it.tagId }.toSet()
        if (tagIds.isEmpty()) return emptyList()
        return tags.getAll().filter { it.id in tagIds }
    }

    suspend fun insertTag(name: String, color: String = ""): Long {
        return tags.insert(
            ScriptTag(
                uuid = UUID.randomUUID().toString(),
                name = name,
                color = color,
                date = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun assignTag(scriptId: Long, tagId: Long) {
        refs.insert(ScriptTagCrossRef(scriptId, tagId))
    }

    suspend fun unassignTag(scriptId: Long, tagId: Long): Int =
        refs.delete(scriptId, tagId)

    suspend fun getVariables(): List<ScriptVariable> = variables.getAll()

    suspend fun insertVariable(key: String, value: String, isSecure: Boolean): Long {
        return variables.insert(
            ScriptVariable(
                uuid = UUID.randomUUID().toString(),
                key = key,
                value = value,
                isSecure = isSecure,
                date = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteVariable(id: Long): Int = variables.deleteById(id)
}
