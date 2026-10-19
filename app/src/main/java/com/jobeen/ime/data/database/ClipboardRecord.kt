package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * text 唯一索引（ClipboardDatabase v4 起）：全部写路径本就以 text 为
 * 逻辑键（置顶/编辑/删除/排序都按 text 定位），唯一性此前只靠 addEntry
 * 的应用层「先删后插」约定维持；备份还原与拆库迁移补行都绕开该约定
 * 直插，同文本两行会让一改多行/一删多行。索引把不变量下沉到库层，
 * 直插路径写库前必须先去重（见 [dedupeClipboardByText]）。
 * 注意：本实体同时被 AppDatabase 共用，加索引后 AppDatabase 侧也必须
 * 配套 bump 版本并在旧表上建同名索引（见 ClipboardDatabase v4 迁移）。
 */
@Entity(
    tableName = "clipboard_records",
    indices = [Index(value = ["text"], unique = true)],
)
data class ClipboardRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val timestamp: Long,
    val cloud: Boolean = false,
    val deleted: Boolean = false,
    val deletedAt: Long = 0,
    val pinned: Boolean = false,
    /** 手动排序序号（拖动排序写入，越小越靠前）；未手动排过时由 -timestamp 保持时间倒序 */
    val sortOrder: Long = 0,
)

/**
 * applyOrder 专用轻量投影：排序重排只需要这五列，不必整行读
 * （单条文本可达 10 万字，全量 SELECT * 只为重编号是纯浪费）。
 */
data class ClipboardOrderRow(
    val id: Long,
    val text: String,
    val pinned: Boolean,
    val sortOrder: Long,
    val timestamp: Long,
)

/**
 * 按 text 去重：每组同 text 行只保留「最佳」一行，返回顺序保持各组
 * 首次出现的相对位置。保留口径必须与 ClipboardDatabase.MIGRATION_3_4
 * 的 SQL 去重严格一致：① pinned 优先；② timestamp 最新；③ id 最大
 * （最后写入的行）。
 *
 * 备份还原、拆库迁移补行等绕开 addEntry「先删后插」约定的直插路径
 * 在写库前必须先过这道去重，否则会撞 text 唯一索引、整事务回滚。
 */
internal fun dedupeClipboardByText(records: List<ClipboardRecord>): List<ClipboardRecord> {
    if (records.size < 2) return records
    val best = LinkedHashMap<String, ClipboardRecord>()
    for (r in records) {
        val cur = best[r.text]
        if (cur == null || isBetterClipboardRow(r, cur)) best[r.text] = r
    }
    return best.values.toList()
}

/** [a] 是否应优先于 [b] 保留（口径见 [dedupeClipboardByText]）。 */
private fun isBetterClipboardRow(a: ClipboardRecord, b: ClipboardRecord): Boolean = when {
    a.pinned != b.pinned -> a.pinned
    a.timestamp != b.timestamp -> a.timestamp > b.timestamp
    else -> a.id > b.id
}
