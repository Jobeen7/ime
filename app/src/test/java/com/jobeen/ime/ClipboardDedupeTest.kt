package com.jobeen.ime

import com.jobeen.ime.data.database.ClipboardRecord
import com.jobeen.ime.data.database.dedupeClipboardByText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * text 唯一索引（ClipboardDatabase v4）配套的直插路径去重：
 * 保留口径 pinned 优先 → timestamp 最新 → id 最大，与
 * MIGRATION_3_4 的 SQL 去重必须严格一致（同一规格两个实现，
 * 任一漂移都会让「迁移后无重复」与「还原后无重复」口径分裂）。
 * 迁移 SQL 本身不可在 JVM 测，此处只测纯函数一侧；SQL 侧待
 * 仪器测试/真机升级验证（见批次报告未决项）。
 */
class ClipboardDedupeTest {

    private fun row(
        id: Long,
        text: String,
        timestamp: Long,
        pinned: Boolean = false,
    ) = ClipboardRecord(id = id, text = text, timestamp = timestamp, pinned = pinned)

    @Test
    fun emptyAndSingleton_passThrough() {
        assertEquals(emptyList<ClipboardRecord>(), dedupeClipboardByText(emptyList()))
        val one = listOf(row(1, "a", 100))
        assertSame(one, dedupeClipboardByText(one))
    }

    @Test
    fun distinctTexts_allKeptInOrder() {
        val rows = listOf(row(1, "a", 100), row(2, "b", 90), row(3, "c", 80))
        assertEquals(rows, dedupeClipboardByText(rows))
    }

    @Test
    fun pinnedBeatsNewerUnpinned() {
        // 置顶是用户的显式保留意图，优先级高于时间新旧
        val pinnedOld = row(1, "x", 100, pinned = true)
        val unpinnedNew = row(2, "x", 200)
        assertEquals(listOf(pinnedOld), dedupeClipboardByText(listOf(unpinnedNew, pinnedOld)))
    }

    @Test
    fun samePinned_newestTimestampWins() {
        val older = row(1, "x", 100)
        val newer = row(2, "x", 300)
        val middle = row(3, "x", 200)
        assertEquals(listOf(newer), dedupeClipboardByText(listOf(older, newer, middle)))
    }

    @Test
    fun samePinnedAndTimestamp_largestIdWins() {
        val a = row(1, "x", 100)
        val b = row(7, "x", 100)
        val c = row(3, "x", 100)
        assertEquals(listOf(b), dedupeClipboardByText(listOf(a, b, c)))
    }

    @Test
    fun groupOrderFollowsFirstOccurrence() {
        // 返回顺序保持各 text 首次出现的位置（还原按备份顺序写回，
        // 去重不能把保留行挪到别组的后面去）
        val bOld = row(1, "b", 100)
        val aOnly = row(2, "a", 100)
        val bNew = row(3, "b", 200)
        assertEquals(listOf(bNew, aOnly), dedupeClipboardByText(listOf(bOld, aOnly, bNew)))
    }

    @Test
    fun replacementKeepsOtherFieldsOfWinningRow() {
        // 获胜行的 cloud/sortOrder 等字段原样保留（去重只选行不拼行）
        val loser = ClipboardRecord(
            id = 1, text = "x", timestamp = 300, cloud = false, sortOrder = 5,
        )
        val winner = ClipboardRecord(
            id = 2, text = "x", timestamp = 100, cloud = true,
            pinned = true, sortOrder = -9,
        )
        assertEquals(listOf(winner), dedupeClipboardByText(listOf(loser, winner)))
    }
}
