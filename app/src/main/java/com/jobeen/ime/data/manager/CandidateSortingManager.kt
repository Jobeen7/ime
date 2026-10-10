package com.jobeen.ime.data.manager

import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.CandidateSorting
import com.jobeen.ime.data.database.CandidateSortingDao
import com.jobeen.ime.engine.data.EngineMessage.Candidate

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
     * @param candidates 拖拽完成后的候选列表（顺序即用户期望的顺序）；
     *                   其 [Candidate.index] 为原始序号，记录的是“新顺序里的原始序号”。
     * @param key [candidateSortingKey] 派生的组字串键。键按组字串而非
     *            候选集合：保存时网格里是全量已加载候选，还原时引擎
     *            按批下发、集合大小不同，集合指纹永远对不上——这是
     *            拖拽排序此前存了也永不生效的根因。组字串两边一致，
     *            保存的序号序列可套用到任意子集（缺失序号跳过、
     *            未保存的新候选按原序追加，见 applySavedCandidateOrder）。
     */
    suspend fun save(candidates: List<Candidate>, key: String) {
        if (candidates.isEmpty() || key.isEmpty()) return
        dao.saveSorting(
            CandidateSorting(
                key = key,
                candidateIds = candidates.map { it.index },
            )
        )
    }

    /** 读取某组字串上次保存的排序结果；无记录时返回 null。 */
    suspend fun load(key: String): List<Int>? =
        if (key.isEmpty()) null else dao.loadSorting(key)?.candidateIds

    /** 排序表是否为空（供热路径缓存判空，避免每键一次注定无结果的查询） */
    suspend fun isTableEmpty(): Boolean = dao.count() == 0
}

/**
 * 拖拽排序记录的键：由当前组字串（preedit）派生，前缀 "p:" 与旧的
 * 集合指纹键（裸十六进制）区分——旧行从此不再命中、自然沉底。
 * 组字为空（预测态等无组字场景）返回空串，调用方据此跳过保存/读取，
 * 避免无组字的列表共用一个键互相污染。
 */
internal fun candidateSortingKey(preedit: String): String =
    if (preedit.isBlank()) "" else "p:" + CandidateSortingKey.compute(listOf(preedit))

/**
 * 按保存的原始序号顺序重排候选；不在保存列表中的候选保持原有相对
 * 顺序追加到末尾。保存顺序是用户对该候选集的显式意愿，调用方无论
 * 是否启用智能重排都应以此收口，否则拖拽排序存了也永不生效。
 */
internal fun applySavedCandidateOrder(
    list: List<Candidate>, savedIds: List<Int>
): List<Candidate> {
    val byId = list.associateBy { it.index }
    val savedSet = savedIds.toSet()
    val restored = ArrayList<Candidate>(list.size)
    for (id in savedIds) {
        byId[id]?.let { restored.add(it) }
    }
    for (c in list) {
        if (c.index !in savedSet) restored.add(c)
    }
    return restored
}