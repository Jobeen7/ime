// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import com.jobeen.ime.engine.rime.daemon.RimeSession
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LevelDB 查看（WO52 12-⑭）：
 *
 * - 只读：数据库列表（`*.userdb`）＋键值浏览（分页）；不写任何数据；
 * - 不直连 LevelDB JNI：列表与导出一律复用现有 `RimeApi` 路径
 *   `getUserDictList`／`exportUserDictLive`（导出到缓存临时文件再解析，
 *   读完即删）；
 * - 引擎线程串行访问防损坏：浏览器层全部调用先经单线程分发器串行，
 *   再由 `RimeApi` 内部回到 rime-main 执行——与引擎查词／学词不并发
 *   访问同一个 LevelDB（与 `UserDictManager` 的线程约束同口径）。
 * 本文件纯逻辑（解析＋分页＋串行分发），不碰 View；单测直接覆盖。
 */

/** 词典一条键值：键＝编码、值＝词、权重（无权重时 null）。 */
data class LevelDbEntry(
    val key: String,
    val word: String,
    val weight: Long?,
)

/** 一页浏览结果。 */
data class LevelDbPage(
    val dictName: String,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val entries: List<LevelDbEntry>,
) {
    val totalPages: Int get() = if (total == 0) 0 else (total + pageSize - 1) / pageSize
    val hasPrev: Boolean get() = page > 0
    val hasNext: Boolean get() = page + 1 < totalPages
}

/**
 * 窄口径引擎词典访问：生产实现走 `RimeSession.runOnReady`＋`RimeApi`；
 * 单测用假实现（只断言调用线程与顺序，不碰引擎）。
 */
interface UserDictEngine {
    suspend fun listDicts(): List<String>
    suspend fun exportDict(dictName: String, textFile: String): Int
}

/** 生产实现：经引擎会话的 `runOnReady` 调现有 `RimeApi` 路径。 */
class RimeSessionUserDictEngine(
    private val session: RimeSession,
) : UserDictEngine {
    override suspend fun listDicts(): List<String> =
        session.runOnReady { getUserDictList() }

    override suspend fun exportDict(dictName: String, textFile: String): Int =
        session.runOnReady { exportUserDictLive(dictName, textFile) }
}

object LevelDbTextParser {

    /**
     * 解析导出的 `.userdb.txt` 文本：跳过空行与 `#` 注释行；列序按行判定——
     * 首列像拼音编码且次列含汉字 → 码⇥词⇥权重（Rime 原生导出列序），
     * 反之词⇥码⇥权重；其余情况首列为键、次列为词；第三列数字为权重。
     * 单列行跳过（导出恒为两列以上）。
     */
    fun parse(text: String): List<LevelDbEntry> {
        val out = ArrayList<LevelDbEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 2) continue
            val first = cols[0].trim()
            val second = cols[1].trim()
            if (first.isEmpty() || second.isEmpty()) continue
            val weight = cols.getOrNull(2)?.trim()?.toLongOrNull()
            val (key, word) = when {
                isPinyinCode(first) && containsCjk(second) -> first to second
                containsCjk(first) && isPinyinCode(second) -> second to first
                else -> first to second
            }
            out += LevelDbEntry(key, word, weight)
        }
        return out
    }

    /** 分页：页码夹取到有效范围（越界回末页）。 */
    fun page(entries: List<LevelDbEntry>, page: Int, pageSize: Int): List<LevelDbEntry> {
        if (entries.isEmpty() || pageSize <= 0) return emptyList()
        val totalPages = (entries.size + pageSize - 1) / pageSize
        val clamped = page.coerceIn(0, totalPages - 1)
        val from = clamped * pageSize
        return entries.subList(from, minOf(from + pageSize, entries.size))
    }

    /** 是否像拼音编码：仅含小写字母和空格。 */
    fun isPinyinCode(s: String): Boolean =
        s.isNotEmpty() && s.all { it in 'a'..'z' || it == ' ' }

    /** 是否包含 CJK 汉字（基本区＋扩展 A 区）。 */
    fun containsCjk(s: String): Boolean =
        s.any { it in '㐀'..'䶿' || it in '一'..'鿿' }
}

class LevelDbBrowser(
    private val engine: UserDictEngine,
    /** 串行分发器：缺省单线程（与引擎查词／学词串行，不并发访 LevelDB）。 */
    private val engineDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1),
    /** 导出临时文件目录（生产：cacheDir；单测：临时目录）。 */
    private val tempDir: () -> File,
) {

    /** 每页条数（浏览分页）。 */
    val pageSize: Int = DEFAULT_PAGE_SIZE

    /**
     * 导出结果缓存（L-14）：键＝词库名，值＝解析后的条目表；翻页只读
     * 缓存切片，不再每页导出。窗口即本实例生命周期（LevelDbActivity
     * 按页创建 browser，离开页面即丢弃）。并发 HashMap：browse 经
     * 单线程分发器读写，invalidate 由任意线程调。
     */
    private val entriesCache = java.util.concurrent.ConcurrentHashMap<String, List<LevelDbEntry>>()

    /**
     * 清缓存（null=全清）：词库变更（导入／同步／部署）后调用，
     * 使下次浏览重新导出。调用方须持有 browser 引用；本页内无
     * 词库变更入口，缓存窗口即页面生命周期。
     */
    fun invalidate(dictName: String? = null) {
        if (dictName == null) entriesCache.clear() else entriesCache.remove(dictName)
    }

    /** 数据库列表（`*.userdb` 名，即 `getUserDictList` 原样）。 */
    suspend fun listDatabases(): List<String> =
        withContext(engineDispatcher) { engine.listDicts() }

    /**
     * 键值浏览（只读）：首访该词库时经引擎线程导出到临时文件、解析、
     * 进缓存；再访只读缓存切片、分页，不再导出。导出失败（返回负数）
     * 时抛 [IllegalStateException]，不进缓存。
     */
    suspend fun browse(dictName: String, page: Int): LevelDbPage =
        withContext(engineDispatcher) {
            var entries = entriesCache[dictName]
            if (entries == null) {
                entries = exportAndParse(dictName)
                entriesCache[dictName] = entries
            }
            val totalPages = if (entries.isEmpty()) 0
            else (entries.size + pageSize - 1) / pageSize
            val clamped = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            LevelDbPage(
                dictName = dictName,
                page = clamped,
                pageSize = pageSize,
                total = entries.size,
                entries = LevelDbTextParser.page(entries, clamped, pageSize),
            )
        }

    /** 导出指定词库到临时文件并解析（读完即删临时文件）。 */
    private suspend fun exportAndParse(dictName: String): List<LevelDbEntry> {
        val dir = tempDir().apply { mkdirs() }
        val file = File(dir, "leveldb-$dictName.txt")
        try {
            val count = engine.exportDict(dictName, file.absolutePath)
            if (count < 0 || !file.exists()) {
                throw IllegalStateException("导出词典失败：$dictName")
            }
            return LevelDbTextParser.parse(file.readText(Charsets.UTF_8))
        } finally {
            runCatching { file.delete() }
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
