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
     * 保存候选集合的排序结果。
     *
     * @param candidates 拖拽完成后的候选列表（顺序即用户期望的顺序）；
     *                   其 [Candidate.index] 为原始序号，记录的是“新顺序里的原始序号”。
     */
    suspend fun save(candidates: List<Candidate>) {
        if (candidates.isEmpty()) return
        dao.saveSorting(
            CandidateSorting(
                key = CandidateSortingKey.compute(candidates.map { it.text }),
                candidateIds = candidates.map { it.index },
            )
        )
    }

    /** 读取候选集合上次保存的排序结果；无记录时返回 null。 */
    suspend fun load(candidates: List<Candidate>): List<Int>? =
        dao.loadSorting(CandidateSortingKey.compute(candidates.map { it.text }))?.candidateIds

    /** 排序表是否为空（供热路径缓存判空，避免每键一次注定无结果的查询） */
    suspend fun isTableEmpty(): Boolean = dao.count() == 0
}

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