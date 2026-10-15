package com.jobeen.ime.data.manager

import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.CandidateSorting
import com.jobeen.ime.data.database.CandidateSortingDao
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 候选排序记录的数据层管理。
 *
 * 职责：把“候选集合 → 无序唯一指纹”映射到持久化的排序记录，
 * 将指纹算法与 Room 访问隔离在业务层之外。
 */
class CandidateSortingManager(private val db: AppDatabase) {

    private val dao: CandidateSortingDao = db.candidateSortingDao()

    /**
     * 保存一次拖拽排序结果。
     *
     * @param candidates 拖拽完成后的候选列表（顺序即用户期望的顺序）。
     *                   记录的是候选**文本哈希**序列，不再是原始序号：
     *                   序号由引擎按当前词频/词库状态编号，词频学习、
     *                   词库更新、删词过滤都会让同一序号改指另一个词，
     *                   存序号的顺序日后会整表错套；文本身份不随编号
     *                   漂移，按文本回套才稳。
     * @param key [candidateSortingKey] 派生的组字串＋方案键。键按组字串
     *            而非候选集合：保存时网格里是全量已加载候选，还原时
     *            引擎按批下发、集合大小不同，集合指纹永远对不上——
     *            这是拖拽排序此前存了也永不生效的根因。组字串两边
     *            一致，保存的顺序可套用到任意子集（缺失的跳过、
     *            未保存的新候选按原序追加，见 applySavedCandidateOrder）。
     */
    suspend fun save(candidates: List<Candidate>, key: String) {
        if (candidates.isEmpty() || key.isEmpty()) return
        purgeLegacyKeysOnce()
        dao.saveSorting(
            CandidateSorting(
                key = key,
                candidateIds = candidates.map { candidateTextHash(it.text) },
            )
        )
    }

    /** 读取某组字串上次保存的排序结果（候选文本哈希序列）；无记录时返回 null。 */
    suspend fun load(key: String): List<Int>? {
        if (key.isEmpty()) return null
        purgeLegacyKeysOnce()
        return dao.loadSorting(key)?.candidateIds
    }

    /** 排序表是否为空（供热路径缓存判空，避免每键一次注定无结果的查询） */
    suspend fun isTableEmpty(): Boolean = dao.count() == 0

    /**
     * 旧格式行一次性清理（进程内只跑一次，首次访问排序表时触发）：
     * 集合指纹键的行自键格式变更后永不再命中，留着只会随备份来回
     * 搬运。失败不置标志、下次访问重试；清理本身失败不影响读写主
     * 路径，故吞异常只记日志。
     */
    private suspend fun purgeLegacyKeysOnce() {
        if (!legacyKeysPurged.compareAndSet(false, true)) return
        try {
            dao.deleteLegacyKeys()
        } catch (e: Exception) {
            legacyKeysPurged.set(false)
            Timber.w(e, "Failed to purge legacy candidate sorting keys")
        }
    }

    private companion object {
        private val legacyKeysPurged = AtomicBoolean(false)
    }
}

/**
 * 拖拽排序记录的键：由当前组字串（preedit）与当前方案（schemaId）
 * 派生，形如 "p:<schemaId>:<组字串指纹>"。旧的集合指纹键（裸十六
 * 进制）不再命中、由 Manager 首次访问时一次性清理；更早的
 * "p:<指纹>" 两段键同样不再命中（新键多了方案段）。
 * 键含方案：同一组字串在全拼/双拼下候选集完全不同，共用一份
 * 顺序必然串扰。组字为空（预测态等无组字场景）返回空串，调用方
 * 据此跳过保存/读取，避免无组字的列表共用一个键互相污染。
 */
internal fun candidateSortingKey(preedit: String, schemaId: String): String =
    if (preedit.isBlank()) "" else "p:$schemaId:" + CandidateSortingKey.compute(listOf(preedit))

/**
 * 候选文本的稳定哈希（FNV-1a 32 位）：排序记录以它代替原始序号
 * 作为候选身份。选哈希而非文本本身：存储列与备份格式沿用既有的
 * Int 列表，零 schema 变更；值由算法定义、跨进程/跨版本恒定，
 * 不依赖 String.hashCode 的实现细节。同一列表内候选数至多数百，
 * 32 位哈希碰撞概率可忽略；真碰撞时也只是该两条相对顺序按原序
 * 落位，不会错套到无关词上（见 applySavedCandidateOrder 的分桶
 * 消费）。
 */
internal fun candidateTextHash(text: String): Int {
    var hash = -2128831035 // FNV-1a 32 位 offset basis 0x811C9DC5
    for (c in text) {
        hash = (hash xor c.code) * 0x01000193 // FNV prime
    }
    return hash
}

/**
 * 按保存的文本哈希顺序重排候选；不在保存列表中的候选保持原有
 * 相对顺序追加到末尾。保存顺序是用户对该候选集的显式意愿，
 * 调用方无论是否启用智能重排都应以此收口，否则拖拽排序存了也
 * 永不生效。
 *
 * 同一文本出现多条时候选（同词不同注释等）按出现次序逐个消费：
 * 保存序里该哈希出现几次，就依次取走原列表中该文本的前几条，
 * 不会把一条候选重复取用、也不会漏掉其余同文候选。
 */
internal fun applySavedCandidateOrder(
    list: List<Candidate>, savedHashes: List<Int>
): List<Candidate> {
    if (list.isEmpty() || savedHashes.isEmpty()) return list
    // 按文本哈希分桶，桶内保持原列表次序
    val buckets = HashMap<Int, MutableList<Candidate>>()
    for (c in list) {
        buckets.getOrPut(candidateTextHash(c.text)) { mutableListOf() }.add(c)
    }
    val consumed = HashMap<Int, Int>()
    val restored = ArrayList<Candidate>(list.size)
    for (hash in savedHashes) {
        val bucket = buckets[hash] ?: continue
        val next = consumed[hash] ?: 0
        if (next < bucket.size) {
            restored.add(bucket[next])
            consumed[hash] = next + 1
        }
    }
    // 未被保存序取走的候选（含取走一部分后的同文余项）按原序追加
    val seen = HashMap<Int, Int>()
    for (c in list) {
        val hash = candidateTextHash(c.text)
        val occurrence = seen[hash] ?: 0
        seen[hash] = occurrence + 1
        if (occurrence >= (consumed[hash] ?: 0)) restored.add(c)
    }
    return restored
}
