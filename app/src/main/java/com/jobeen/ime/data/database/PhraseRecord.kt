package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 常用语（WO52 6.1 加列，AppDatabase v15）：
 * - `shortcut` 输入码（2–10 位 a–z，可空串；同码多条），有独立查询索引；
 * - `category` 分类（纯文本列，不另建分类表——与单子一致）；
 * - `tags` 标签（逗号分隔的纯文本列）；
 * - `variables` 变量（JSON 数组串，元素 key/value/isSecure，见 PhraseVariables）；
 * - `lastUsedAt` 最近使用时间（自动排序开关的排序键，见 04 §2.3
 *   “最近使用的排在前面”；0＝从未使用。
 *   单子只列了前四列，本列是实现已定开关语义所必需的唯一追加列）。
 */
@Entity(
    tableName = "phrase_records",
    indices = [Index("shortcut")],
)
data class PhraseRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val label: String,
    val createdAt: Long,
    /** 手动排序序号（拖动排序写入，越小越靠前）；未手动排过时由 -createdAt 保持新建在前 */
    val sortOrder: Long = 0,
    val pinned: Boolean = false,
    val shortcut: String = "",
    val category: String = "",
    val tags: String = "",
    val variables: String = "",
    val lastUsedAt: Long = 0,
)
