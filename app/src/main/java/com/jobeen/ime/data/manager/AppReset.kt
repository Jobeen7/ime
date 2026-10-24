package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.data.DataManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 重置应用设置：回到默认安装状态（初始状态）。
 *
 * 锁死范围（Lee 2026-10-24 定，多目录模型下重定 2026-10-25）：
 * - 只有内置 Lite（tools/wanxiang-builtin/default.yaml schema_list 顺序）
 * - 所有设置回默认（全部 SharedPreferences 清空后重写启用表）
 * - 全部方案目录清掉（方案目录根的全部孩子：自装方案文件、登记、
 *   各目录学习词 LevelDB 全部随目录删除；当前目录指针随偏好清空
 *   回落初始目录，初始目录本身不预建——引擎在空目录上启动即回 Lite）
 * - WebDAV 绑定清掉（属偏好，随全部清空而清；同步 UUID 随之作废，
 *   旧远端文件夹不自动删除，下次同步生成新 UUID）
 * - 学习词清零（各目录用户词库 LevelDB 随目录删除而清）
 *
 * 刻意不动：shared/（内置 Lite 数据住在这里，删了就没兜底）、
 * filesDir（语音纠错词对与语音模型不是方案设置，不在此列）、
 * cacheDir（临时文件）。顺序：先删方案目录根的孩子（失败即中止，
 * 不动偏好），再清偏好、写回启用表，最后重启引擎（新引擎在空
 * 初始目录上启动，shared 层 Lite 即为全部可用方案）。
 */
object AppReset {
    /** 内置 Lite 方案集，顺序与默认 default.yaml 的 schema_list 一致。 */
    val BUILTIN_LITE_IDS = listOf("wanxiang_t9", "wanxiang_lite", "wanxiang_english")

    /** 内置 Lite 主方案（回内置 Lite 的切换目标）。 */
    const val BUILTIN_LITE_ID = "wanxiang_lite"

    suspend fun resetToInitialState(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = DataManager.schemeDirsRoot
                val failures = mutableListOf<String>()
                root.listFiles()?.forEach { child ->
                    if (!child.deleteRecursively()) failures += child.name
                }
                if (failures.isNotEmpty()) {
                    throw IOException("无法清除：${failures.joinToString("、")}")
                }
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                prefsDir.listFiles()
                    ?.filter { it.name.endsWith(".xml") }
                    ?.forEach { xml ->
                        val name = xml.name.removeSuffix(".xml")
                        context.getSharedPreferences(name, Context.MODE_PRIVATE)
                            .edit().clear().commit()
                    }
                // 偏好已全清：当前目录回落初始目录；启用表写初始目录键 + 镜像。
                SchemeDirs.setCurrentDirName(context, SchemeDirs.INITIAL_DIR)
                val schemaPrefs = context.getSharedPreferences(
                    SchemaManager.PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
                SchemaManager.saveEnabledIds(schemaPrefs, SchemeDirs.INITIAL_DIR, BUILTIN_LITE_IDS)
                RimeDaemon.restartRime()
            }
        }
}
