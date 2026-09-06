package com.ninthsoft.ime.data.manager

import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.database.CandidateSorting
import com.ninthsoft.ime.data.database.CandidateSortingDao
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate

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
}