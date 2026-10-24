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
 * 锁死范围（Lee 2026-10-24 定）：
 * - 只有内置 Lite（tools/wanxiang-builtin/default.yaml schema_list 顺序）
 * - 所有设置回默认（全部 SharedPreferences 清空后重写启用表）
 * - 自装方案登记和文件清掉（用户目录全部内容）
 * - WebDAV 绑定清掉（属偏好，随全部清空而清）
 * - 归档目录清掉（属用户目录，随全部删除而清；归档功能已去掉）
 * - 学习词清零（用户词库 LevelDB 属用户目录，随全部删除而清）
 *
 * 刻意不动：shared/（内置 Lite 数据住在这里，删了就没兜底）、
 * filesDir（语音纠错词对与语音模型不是方案设置，不在此列）、
 * cacheDir（临时文件）。顺序：先删用户目录（失败即中止，不动偏好），
 * 再清偏好、写回启用表，最后重启引擎（新引擎在空用户目录上启动）。
 */
object AppReset {
    /** 内置 Lite 方案集，顺序与默认 default.yaml 的 schema_list 一致。 */
    val BUILTIN_LITE_IDS = listOf("wanxiang_t9", "wanxiang_lite", "wanxiang_english")

    /** 内置 Lite 主方案（回内置 Lite 的切换目标）。 */
    const val BUILTIN_LITE_ID = "wanxiang_lite"

    suspend fun resetToInitialState(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val userDir = DataManager.userDataDir
                val failures = mutableListOf<String>()
                userDir.listFiles()?.forEach { child ->
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
                context.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(
                        SchemaManager.KEY_ENABLED_IDS,
                        BUILTIN_LITE_IDS.joinToString(","),
                    )
                    .commit()
                RimeDaemon.restartRime()
            }
        }
}
